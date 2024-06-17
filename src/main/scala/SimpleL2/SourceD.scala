package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, MultiDontTouch}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import coursier.Fetch

object OutState extends ChiselEnum {
    val Normal    = Value(0.U)
    val Stall     = Value(1.U)
    val FetchData = Value(2.U)
}

class SourceD()(implicit p: Parameters) extends L2Module {
    import OutState._

    val io = IO(new Bundle {
        val d            = DecoupledIO(new TLBundleD(tlBundleParams))
        val task         = Flipped(DecoupledIO(new TaskBundle))
        val data         = Flipped(DecoupledIO(new DataSelectorOut))
        val dataId       = Input(UInt(dataIdBits.W))
        val tempDataRead = Flipped(new TempDataRead)
        val tempDataResp = Flipped(ValidIO(UInt(dataBits.W)))
    })

    io <> DontCare

    /** tasks will be buffered in [[taskQueue]] if and only if data cannot be sent in current cycle */
    val taskQueue = Module(new Queue(new TaskBundle, nrSourceDTaskQueueEntry, flow = true))

    /** 
     * [[dataIdQueue]] is used for storing the [[dataId]] of the incoming 
     * datas which cannot be stored in both [[tmpDataBuffer]] or send out directly 
     */
    val dataIdQueue = Module(new Queue(UInt(dataIdBits.W), nrSourceDTaskQueueEntry, flow = false))

    val deqNeedData = WireInit(false.B)
    taskQueue.io.enq <> io.task

    assert(!(taskQueue.io.count === 0.U && io.data.valid), "should alloc task before data arrive!")

    /** [[tmpDataBuffer]] is used for storing the temporary data from both [[TempDataStorage]] or [[DataStorage]] */
    val tmpDataBuffer     = Reg(UInt(dataBits.W))
    val tmpDataBufferFull = RegInit(false.B)

    /** a FSM is used to control the flow of data */
    val stall        = io.d.valid && !io.d.ready
    val dataIdCount  = WireInit(0.U((log2Ceil(nrSourceDTaskQueueEntry) + 1).W))
    val outState     = RegInit(Normal)
    val nextOutState = WireInit(Normal)

    /**
      * beat counter([[beatCnt]]) indicate the current data beat of the tl_d channel,
      * a complete CacheLine is consist of two data beat since CacheLine
      * is 64Bytes while data beat is 32Bytes which is specified by the bus width => 256-bits
      */
    val beatCnt       = RegInit(0.U(1.W))
    val isLastOutData = beatCnt === 1.U && io.d.fire
    when(io.d.fire && deqNeedData) {
        beatCnt := beatCnt + 1.U
    }

    MultiDontTouch(tmpDataBuffer, stall, outState, nextOutState)

    /** FSM state transition */
    switch(outState) {
        is(Normal) {
            when(stall) {
                nextOutState := Stall
            }.otherwise {
                nextOutState := Normal
            }
        }

        is(Stall) {
            when(isLastOutData) {
                nextOutState := Normal

                when(dataIdCount >= 1.U) {
                    nextOutState := FetchData
                }
            }.otherwise {
                nextOutState := Stall
            }
        }

        is(FetchData) {
            nextOutState := FetchData
            when(dataIdCount <= 1.U && io.tempDataResp.fire) {
                nextOutState := Stall
            }
        }
    }

    outState := nextOutState

    assert(RegNext(nextOutState) === outState)

    /** deal with dequeue */
    val deqTask       = taskQueue.io.deq
    val deqTaskOpcode = deqTask.bits.opcode
    deqNeedData := deqTaskOpcode === GrantData || deqTaskOpcode === AccessAckData
    val readyToDeq = Mux(
        deqNeedData,
        MuxCase(
            false.B,
            Seq(
                (outState === Normal && nextOutState === Normal)   -> (io.data.valid && io.data.bits.last),
                (outState === Normal && nextOutState === Stall)    -> false.B,
                (outState === Stall && nextOutState === Normal)    -> true.B,
                (outState === Stall && nextOutState === FetchData) -> isLastOutData,
                (outState === FetchData && nextOutState === Stall) -> isLastOutData
            )
        ),
        io.d.fire
    )
    deqTask.ready := io.d.ready && readyToDeq
    // assert(!(outState === FetchData && !deqNeedData))

    /** temporary data will be stored into [[tmpDataBuffer]] */
    val isNotLastData = io.data.fire && !io.data.bits.last
    val isLastData    = io.data.fire && io.data.bits.last
    assert(!(isLastData && RegNext(isLastData)), "io.data.bits.last is ASSERTED for continuous cycles!")
    when(outState === Normal && nextOutState === Stall && isNotLastData) {
        assert(deqNeedData)
        tmpDataBuffer := Cat(tmpDataBuffer(511, 256), io.data.bits.data)
    }.elsewhen(outState === Stall && isLastData) {
        assert(deqNeedData)
        tmpDataBuffer     := Cat(io.data.bits.data, tmpDataBuffer(255, 0))
        tmpDataBufferFull := true.B
    }.elsewhen(outState === Stall && nextOutState === Stall && isNotLastData) {
        assert(deqNeedData)
        tmpDataBuffer := Cat(tmpDataBuffer(511, 256), io.data.bits.data)
        // tmpDataBufferFull := true.B
    }.elsewhen(outState === FetchData && io.tempDataResp.fire) {
        tmpDataBuffer     := io.tempDataResp.bits
        tmpDataBufferFull := true.B
    }

    when(outState === Stall && nextOutState === Normal || outState === FetchData && nextOutState === Stall || io.tempDataRead.fire) {
        tmpDataBufferFull := false.B
    }

    /** back-pressure for the incoming datas */
    io.data.ready := ~tmpDataBufferFull
    dontTouch(tmpDataBufferFull)

    /** 
     * [[dataIdQueue]] is used for storing the [[dataId]] of the incoming 
     * datas which cannot be stored in both [[tmpDataBuffer]] or send out directly 
     */
    dataIdQueue.io.enq.valid := io.data.valid && !io.data.ready && io.data.bits.last
    dataIdQueue.io.enq.bits  := io.dataId
    dataIdQueue.io.deq.ready := deqTask.valid && io.tempDataRead.fire
    dataIdCount              := dataIdQueue.io.count
    assert(!(dataIdQueue.io.enq.valid && !dataIdQueue.io.enq.ready))

    /** read data back from [[TempDataBuffer]] if [[tmpDataBuffer]] is empty */
    io.tempDataRead.valid  := (outState === FetchData && nextOutState =/= Stall || outState === Stall && nextOutState === FetchData) && isLastOutData && dataIdQueue.io.count =/= 0.U
    io.tempDataRead.dataId := dataIdQueue.io.deq.bits
    assert(!(io.tempDataRead.fire && RegNext(io.tempDataRead.fire) && io.tempDataRead.dataId === RegNext(io.tempDataRead.dataId)), "try to read the same dataId twice!")
    assert(!(io.tempDataRead.fire && !dataIdQueue.io.deq.fire || !io.tempDataRead.fire && dataIdQueue.io.deq.fire))

    /** send out tilelink transaction */
    val beatDatas = VecInit(Seq(tmpDataBuffer(255, 0), tmpDataBuffer(511, 256)))
    io.d.valid := deqTask.valid && Mux(
        deqNeedData,
        MuxCase(
            false.B,
            Seq(
                (outState === Normal)    -> io.data.valid,
                (outState === Stall)     -> true.B,
                (outState === FetchData) -> (io.tempDataResp.fire || tmpDataBufferFull)
            )
        ),
        taskQueue.io.deq.valid
    )
    io.d.bits.sink   := deqTask.bits.sink
    io.d.bits.opcode := deqTask.bits.opcode
    io.d.bits.param  := deqTask.bits.param
    io.d.bits.data := Mux(
        deqNeedData,
        MuxCase(
            io.data.bits.data,
            Seq(
                (outState === Normal)                                                              -> io.data.bits.data,
                (outState === Stall)                                                               -> beatDatas(beatCnt),
                (outState === FetchData && io.d.ready && !io.tempDataResp.valid)                   -> beatDatas(beatCnt),
                (outState === FetchData && io.d.ready && io.tempDataResp.valid && beatCnt === 0.U) -> io.tempDataResp.bits(255, 0),
                (outState === FetchData && io.d.ready && io.tempDataResp.valid && beatCnt === 1.U) -> beatDatas(beatCnt)
            )
        ),
        0.U
    )
    io.d.bits.size    := 5.U // 32 bytes
    io.d.bits.source  := deqTask.bits.source
    io.d.bits.denied  := false.B
    io.d.bits.corrupt := deqTask.bits.corrupt

    dontTouch(io)
}

object SourceD extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SourceD()(config), name = "SourceD", split = false)
}
