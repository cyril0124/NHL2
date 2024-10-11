package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, SkidBuffer, LeakChecker, IDPoolAlloc}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._

// TODO: ReleaseAck on SourceB ?

class BufferStatusSourceD(implicit p: Parameters) extends L2Bundle {
    // SkidBuffer status for RequestArbiter to block the same address SinkA request.
    val valid = Bool()
    val set   = UInt(setBits.W)
    val tag   = UInt(tagBits.W)
}

class SourceD()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val d                   = DecoupledIO(new TLBundleD(tlBundleParams))
        val task_s2             = Flipped(DecoupledIO(new TaskBundle))                  // for non-data resp / data resp
        val data_s2             = Flipped(DecoupledIO(UInt(dataBits.W)))
        val task_s4             = Flipped(DecoupledIO(new TaskBundle))                  // for non-data resp
        val task_s6s7           = Flipped(DecoupledIO(new TaskBundle))                  // for data resp
        val data_s6s7           = Flipped(DecoupledIO(UInt(dataBits.W)))
        val allocGrantMap       = DecoupledIO(new AllocGrantMap)                        // to SinkE
        val grantMapWillFull    = Input(Bool())                                         // from SinkE
        val sinkIdAlloc         = Flipped(new IDPoolAlloc(log2Ceil(nrExtraSinkId + 1))) // to sinkIDPool
        val nonDataRespCntSinkC = Output(UInt(log2Ceil(nrNonDataSourceDEntry + 1).W))   // for RequestArbiter(SinkC task)
        val nonDataRespCntMp    = Output(UInt(log2Ceil(nrNonDataSourceDEntry + 1).W))   // for MainPipe
        val bufferStatus        = Output(new BufferStatusSourceD)
        val prefetchRespOpt     = if (enablePrefetch) Some(DecoupledIO(new PrefetchRespWithSource(tlBundleParams.sourceBits))) else None
    })

    val nonDataRespQueue = Module(
        new Queue(
            new Bundle {
                val task = new TaskBundle
            },
            nrNonDataSourceDEntry,
            flow = true
        )
    )

    val skidBuffer = Module(new SkidBuffer(new Bundle {
        val task = new TaskBundle
        val data = UInt(dataBits.W)
    }))

    // Priority: task_s4 > task_s2 > task_s6s7
    val task          = Mux(io.task_s4.valid, io.task_s4.bits, Mux(io.task_s2.valid, io.task_s2.bits, io.task_s6s7.bits))
    val taskValid     = io.task_s2.valid || io.task_s4.valid || io.task_s6s7.valid
    val taskFire      = io.task_s2.fire || io.task_s4.fire || io.task_s6s7.fire
    val bufferReady   = Mux(needData(task.opcode), skidBuffer.io.enq.ready, nonDataRespQueue.io.enq.ready)
    val grantMapReady = Mux(task.opcode === GrantData || task.opcode === Grant, io.allocGrantMap.ready, true.B)
    io.nonDataRespCntMp    := Mux(io.grantMapWillFull, nrNonDataSourceDEntry.U, nonDataRespQueue.io.count) // If grantMap is not ready, set io.nonDataRespCnt to the max value so that the MainPipe could stall the Grant request.
    io.nonDataRespCntSinkC := nonDataRespQueue.io.count
    io.task_s4.ready       := bufferReady && grantMapReady
    io.task_s2.ready       := bufferReady && grantMapReady && !io.task_s4.valid
    io.task_s6s7.ready     := bufferReady && grantMapReady && !io.task_s4.valid && !io.task_s2.valid
    assert(PopCount(VecInit(Seq(io.task_s2.fire, io.task_s4.fire, io.task_s6s7.fire))) <= 1.U, "only one task can be valid at the same time")
    assert(!(io.task_s4.fire && needData(io.task_s4.bits.opcode)), "task_s4 is not for data resp")
    assert(!(taskFire && (task.opcode === GrantData || task.opcode === Grant) && !io.allocGrantMap.fire), "need to allocate grantMap entry!")

    val sinkId = io.sinkIdAlloc.idOut
    io.sinkIdAlloc.valid := taskFire && (task.opcode === GrantData || task.opcode === Grant) && !task.isMshrTask

    io.data_s2.ready   := skidBuffer.io.enq.ready && needData(task.opcode) && grantMapReady
    io.data_s6s7.ready := skidBuffer.io.enq.ready && needData(task.opcode) && grantMapReady && !io.data_s2.valid

    nonDataRespQueue.io.enq.valid          := taskFire && !needData(task.opcode) && task.opcode =/= HintAck
    nonDataRespQueue.io.enq.bits.task      := task
    nonDataRespQueue.io.enq.bits.task.sink := Mux(task.isMshrTask, task.sink, sinkId)

    skidBuffer.io.enq                <> DontCare
    skidBuffer.io.enq.valid          := taskFire && needData(task.opcode)
    skidBuffer.io.enq.bits.task      := task
    skidBuffer.io.enq.bits.task.sink := Mux(task.isMshrTask, task.sink, sinkId)
    skidBuffer.io.enq.bits.data      := Mux(io.task_s2.valid, io.data_s2.bits, io.data_s6s7.bits)
    assert(!(io.task_s4.fire && !io.task_s6s7.valid && io.data_s6s7.fire), "task_s4 should arrive with data_s6s7!")

    /** Extra buffer status signals for [[RequestArbiter]] */
    io.bufferStatus.valid := skidBuffer.io.full
    io.bufferStatus.set   := RegEnable(skidBuffer.io.enq.bits.task.set, skidBuffer.io.enq.fire)
    io.bufferStatus.tag   := RegEnable(skidBuffer.io.enq.bits.task.tag, skidBuffer.io.enq.fire)

    assert(!(io.task_s2.fire && needData(task.opcode) && !io.data_s2.fire), "data should arrive with task!")
    assert(!(io.data_s2.fire && needData(task.opcode) && !io.task_s2.fire), "task should arrive with data!")
    assert(!(io.task_s6s7.fire && needData(task.opcode) && !io.data_s6s7.fire), "data should arrive with task!")
    assert(!(io.data_s6s7.fire && needData(task.opcode) && !io.task_s6s7.fire), "task should arrive with data!")
    assert(!(needData(io.task_s2.bits.opcode) && (io.task_s2.valid ^ io.data_s2.valid)), "task_s2 should be valid with data_s2 valid!")
    assert(!(needData(io.task_s6s7.bits.opcode) && (io.task_s6s7.valid ^ io.data_s6s7.valid)), "task_s6s7 should be valid with data_s6s7 valid!")
    assert(!(io.data_s2.valid && !io.task_s2.valid), "unnecessary data_s2! task_s2.opcode:%d", io.task_s2.bits.opcode)
    assert(!(io.data_s6s7.valid && !io.task_s6s7.valid), "unnecessary data_s6s7! task_s6s7.opcode:%d", io.task_s6s7.bits.opcode)

    val NonDataReq   = 0.U(1.W)
    val HasDataReq   = 1.U(1.W)
    val select       = RegInit(NonDataReq) // 0: nonDataReq, 1: hasDataReq
    val choseNonData = select === NonDataReq && nonDataRespQueue.io.deq.valid || select === HasDataReq && !skidBuffer.io.deq.valid
    val deqValid     = Mux(choseNonData, nonDataRespQueue.io.deq.valid, skidBuffer.io.deq.valid)
    val deqTask      = Mux(choseNonData, nonDataRespQueue.io.deq.bits.task, skidBuffer.io.deq.bits.task)
    val deqData      = Mux(choseNonData, 0.U, skidBuffer.io.deq.bits.data)
    val deqNeedData  = needData(deqTask.opcode)
    val beatCnt      = RegInit(0.U(log2Ceil(nrBeat).W))
    val first        = beatCnt === 0.U
    val last         = beatCnt === (nrBeat - 1).U

    /**
      * If GrantData/AccessAckData stall for a long time while there is also some non-data response behind them, 
      * the non-data response will be chosen to be sent out.
      */
    val stallCnt = RegInit(0.U(8.W))
    when(stallCnt >= 255.U && nonDataRespQueue.io.deq.valid && first) { // TODO: parameterize
        select   := NonDataReq
        stallCnt := 0.U
    }.elsewhen(select === HasDataReq && io.d.valid && !io.d.ready) {
        stallCnt := stallCnt + 1.U
    }

    when(select === NonDataReq && io.d.fire) {
        when(skidBuffer.io.deq.valid) {
            select := HasDataReq
        }
    }.elsewhen(select === HasDataReq && io.d.fire && last) {
        when(nonDataRespQueue.io.deq.valid) {
            select := NonDataReq
        }
    }

    /** beat counter for GrantData/AccessAckData */
    when(io.d.fire && deqNeedData) {
        when(last) {
            beatCnt := 0.U
        }.otherwise {
            beatCnt := beatCnt + 1.U
        }
    }

    io.d              <> DontCare
    io.d.valid        := deqValid
    io.d.bits.corrupt := DontCare
    io.d.bits.opcode  := deqTask.opcode
    io.d.bits.param   := deqTask.param
    io.d.bits.size    := Mux(needData(deqTask.opcode), 6.U, 5.U)       // TODO: parameterize
    io.d.bits.source  := deqTask.source
    io.d.bits.sink    := deqTask.sink                                  // If deqTask is a MSHR task, the sink id is used for the next incoming GrantAck to Address the matched MSHR, otherwise we should allocate an unique sink id which is not overlapped with mshr id to the Grant/GrantData.
    io.d.bits.data    := Mux(last, deqData(511, 256), deqData(255, 0)) // TODO: parameterize

    io.allocGrantMap.valid         := taskFire && (task.opcode === GrantData || task.opcode === Grant)
    io.allocGrantMap.bits.sink     := Mux(task.isMshrTask, task.sink, sinkId)
    io.allocGrantMap.bits.mshrTask := task.isMshrTask
    io.allocGrantMap.bits.set      := task.set
    io.allocGrantMap.bits.tag      := task.tag

    nonDataRespQueue.io.deq.ready := choseNonData && io.d.ready
    skidBuffer.io.deq.ready       := !choseNonData && io.d.ready && last

    LeakChecker(io.d.valid, io.d.fire, Some("SourceD_io_d_valid"), maxCount = deadlockThreshold)

    /**
     * Send response to [[Prefetcher]].
     * Prefetch response does not need to be sent via SourceD.
     */
    io.prefetchRespOpt.foreach { resp =>
        val pftRespEntry = new Bundle() {
            val tag      = UInt(tagBits.W)
            val set      = UInt(setBits.W)
            val pfSource = UInt(utility.MemReqSource.reqSourceBits.W)
            val source   = UInt(tlBundleParams.sourceBits.W) // sourceId is used for identifying the request client.
            val vaddr    = vaddrBitsOpt.map(_ => UInt(vaddrBitsOpt.get.W))
        }

        // TODO: this may not need 10 entries, but this does not take much space
        val pftQueueLen  = 10
        val pftRespQueue = Module(new Queue(pftRespEntry, entries = pftQueueLen, flow = true))

        pftRespQueue.io.enq.valid         := taskFire && task.opcode === HintAck // && io.d_task.bits.task.fromL2pft.getOrElse(false.B)
        pftRespQueue.io.enq.bits.tag      := task.tag
        pftRespQueue.io.enq.bits.set      := task.set
        pftRespQueue.io.enq.bits.pfSource := DontCare                            // TODO: io.d_task.bits.task.reqSource
        pftRespQueue.io.enq.bits.source   := task.source
        pftRespQueue.io.enq.bits.vaddr.foreach(_ := task.vaddrOpt.getOrElse(0.U))

        resp.valid         := pftRespQueue.io.deq.valid
        resp.bits.tag      := pftRespQueue.io.deq.bits.tag
        resp.bits.set      := pftRespQueue.io.deq.bits.set
        resp.bits.pfSource := pftRespQueue.io.deq.bits.pfSource
        resp.bits.source   := pftRespQueue.io.deq.bits.source
        resp.bits.vaddr.foreach(_ := pftRespQueue.io.deq.bits.vaddr.getOrElse(0.U))
        pftRespQueue.io.deq.ready := resp.ready

        assert(pftRespQueue.io.enq.ready, "pftRespQueue should never be full, no back pressure logic")
    }

    dontTouch(io)
}

object SourceD extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SourceD()(config), name = "SourceD", split = false)
}
