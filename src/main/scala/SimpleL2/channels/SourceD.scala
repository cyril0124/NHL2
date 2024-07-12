package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, SkidBuffer}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._

// TODO: ReleaseAck on SourceB ?
class SourceD()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val d         = DecoupledIO(new TLBundleD(tlBundleParams))
        val task_s2   = Flipped(DecoupledIO(new TaskBundle))
        val data_s2   = Flipped(DecoupledIO(UInt(dataBits.W)))
        val task_s6s7 = Flipped(DecoupledIO(new TaskBundle))
        val data_s6s7 = Flipped(DecoupledIO(UInt(dataBits.W)))
    })

    val skidBuffer = Module(new SkidBuffer(new Bundle {
        val task = new TaskBundle
        val data = UInt(dataBits.W)
    }))

    val task = Mux(io.task_s2.valid, io.task_s2.bits, io.task_s6s7.bits)
    io.task_s2.ready   := skidBuffer.io.enq.ready
    io.task_s6s7.ready := skidBuffer.io.enq.ready && !io.task_s2.valid

    io.data_s2.ready   := skidBuffer.io.enq.ready
    io.data_s6s7.ready := skidBuffer.io.enq.ready && needData(skidBuffer.io.enq.bits.task.opcode) && !io.data_s2.valid

    skidBuffer.io.enq           <> DontCare
    skidBuffer.io.enq.valid     := io.task_s2.valid || io.task_s6s7.valid
    skidBuffer.io.enq.bits.task := task
    skidBuffer.io.enq.bits.data := Mux(io.task_s6s7.valid, io.data_s6s7.bits, io.data_s2.bits)

    assert(!(io.task_s2.fire && needData(task.opcode) && !io.data_s2.fire), "data should arrive with task!")
    assert(!(io.data_s2.fire && needData(task.opcode) && !io.task_s2.fire), "task should arrive with data!")
    assert(!(io.task_s6s7.fire && needData(task.opcode) && !io.data_s6s7.fire), "data should arrive with task!")
    assert(!(io.data_s6s7.fire && needData(task.opcode) && !io.task_s6s7.fire), "task should arrive with data!")
    assert(!(needData(io.task_s2.bits.opcode) && (io.task_s2.valid ^ io.data_s2.valid)), "task_s2 should be valid with data_s2 valid!")
    assert(!(needData(io.task_s6s7.bits.opcode) && (io.task_s6s7.valid ^ io.data_s6s7.valid)), "task_s6s7 should be valid with task_s6s7 valid!")
    assert(!(io.data_s2.valid && !io.task_s2.valid), "unnecessary data_s2! task_s2.opcode:%d", io.task_s2.bits.opcode)
    assert(!(io.data_s6s7.valid && !io.task_s6s7.valid), "unnecessary data_s6s7! task_s6s7.opcode:%d", io.task_s6s7.bits.opcode)

    val deq         = skidBuffer.io.deq
    val deqData     = deq.bits.data
    val deqNeedData = needData(deq.bits.task.opcode)
    val beatCnt     = RegInit(0.U(log2Ceil(nrBeat).W))
    val last        = beatCnt === (nrBeat - 1).U

    when(io.d.fire && deqNeedData) {
        when(last) {
            beatCnt := 0.U
        }.otherwise {
            beatCnt := beatCnt + 1.U
        }
    }

    io.d              <> DontCare
    io.d.valid        := deq.valid
    io.d.bits.corrupt := DontCare
    io.d.bits.opcode  := deq.bits.task.opcode
    io.d.bits.param   := deq.bits.task.param
    io.d.bits.size    := Mux(needData(deq.bits.task.opcode), 6.U, 5.U) // TODO:
    io.d.bits.source  := deq.bits.task.source
    io.d.bits.sink    := deq.bits.task.sink
    io.d.bits.data    := Mux(last, deqData(511, 256), deqData(255, 0)) // TODO: parameterize
    deq.ready         := !deqNeedData && io.d.ready || deqNeedData && io.d.ready && last
}

object SourceD extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SourceD()(config), name = "SourceD", split = false)
}
