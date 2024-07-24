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
class SourceD()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val d              = DecoupledIO(new TLBundleD(tlBundleParams))
        val task_s2        = Flipped(DecoupledIO(new TaskBundle))
        val data_s2        = Flipped(DecoupledIO(UInt(dataBits.W)))
        val task_s6s7      = Flipped(DecoupledIO(new TaskBundle))
        val data_s6s7      = Flipped(DecoupledIO(UInt(dataBits.W)))
        val allocGrantMap  = DecoupledIO(new AllocGrantMap)                        // to SinkE
        val sinkIdAlloc    = Flipped(new IDPoolAlloc(log2Ceil(nrExtraSinkId + 1))) // to sinkIDPool
        val nonDataRespCnt = Output(UInt(log2Ceil(nrNonDataSourceDEntry + 1).W))
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
    io.nonDataRespCnt := nonDataRespQueue.io.count

    val skidBuffer = Module(new SkidBuffer(new Bundle {
        val task = new TaskBundle
        val data = UInt(dataBits.W)
    }))

    val task = Mux(io.task_s2.valid, io.task_s2.bits, io.task_s6s7.bits)
    io.task_s2.ready   := Mux(needData(task.opcode), skidBuffer.io.enq.ready, nonDataRespQueue.io.enq.ready)
    io.task_s6s7.ready := Mux(needData(task.opcode), skidBuffer.io.enq.ready, nonDataRespQueue.io.enq.ready) && !io.task_s2.valid

    val sinkId = io.sinkIdAlloc.idOut
    io.sinkIdAlloc.valid := (io.task_s2.fire || io.task_s6s7.fire) && (task.opcode === GrantData || task.opcode === Grant) && !task.isMshrTask

    io.data_s2.ready   := skidBuffer.io.enq.ready
    io.data_s6s7.ready := skidBuffer.io.enq.ready && needData(task.opcode) && !io.data_s2.valid

    nonDataRespQueue.io.enq.valid          := (io.task_s2.valid || io.task_s6s7.valid) && !needData(task.opcode)
    nonDataRespQueue.io.enq.bits.task      := task
    nonDataRespQueue.io.enq.bits.task.sink := Mux(task.isMshrTask, task.sink, sinkId)

    skidBuffer.io.enq                <> DontCare
    skidBuffer.io.enq.valid          := (io.task_s2.valid || io.task_s6s7.valid) && needData(task.opcode)
    skidBuffer.io.enq.bits.task      := task
    skidBuffer.io.enq.bits.task.sink := Mux(task.isMshrTask, task.sink, sinkId)
    skidBuffer.io.enq.bits.data      := Mux(io.task_s2.valid, io.data_s2.bits, io.data_s6s7.bits)

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
    io.d.valid        := deqValid && io.allocGrantMap.ready
    io.d.bits.corrupt := DontCare
    io.d.bits.opcode  := deqTask.opcode
    io.d.bits.param   := deqTask.param
    io.d.bits.size    := Mux(needData(deqTask.opcode), 6.U, 5.U)       // TODO: parameterize
    io.d.bits.source  := deqTask.source
    io.d.bits.sink    := deqTask.sink                                  // If deqTask is a MSHR task, the sink id is used for the next incoming GrantAck to Address the matched MSHR, otherwise we should allocate an unique sink id which is not overlapped with mshr id to the Grant/GrantData.
    io.d.bits.data    := Mux(last, deqData(511, 256), deqData(255, 0)) // TODO: parameterize

    // TODO: consider requests in stage6 and stage7
    io.allocGrantMap.valid         := (io.task_s2.fire || io.task_s6s7.fire) && (task.opcode === GrantData || task.opcode === Grant)
    io.allocGrantMap.bits.sink     := Mux(task.isMshrTask, task.sink, sinkId)
    io.allocGrantMap.bits.mshrTask := task.isMshrTask
    io.allocGrantMap.bits.set      := task.set
    io.allocGrantMap.bits.tag      := task.tag

    nonDataRespQueue.io.deq.ready := choseNonData && io.d.ready && io.allocGrantMap.ready
    skidBuffer.io.deq.ready       := !choseNonData && io.d.ready && last && io.allocGrantMap.ready

    LeakChecker(io.d.valid, io.d.fire, Some("SourceD_io_d_valid"), maxCount = deadlockThreshold)

    dontTouch(io)
}

object SourceD extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SourceD()(config), name = "SourceD", split = false)
}
