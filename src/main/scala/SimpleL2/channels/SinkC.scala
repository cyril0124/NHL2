package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class SinkC()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val c        = Flipped(DecoupledIO(new TLBundleC(tlBundleParams)))
        val task     = DecoupledIO(new TaskBundle)
        val taskData = Output(UInt((beatBytes * 8).W))
    })

    io      <> DontCare
    io.c    <> DontCare
    io.task <> DontCare

    val (tag, set, offset) = parseAddress(io.c.bits.address)
    assert(offset === 0.U)

    io.task.valid           := io.c.valid
    io.task.bits.channel    := L2Channel.ChannelC
    io.task.bits.opcode     := io.c.bits.opcode
    io.task.bits.param      := io.c.bits.param
    io.task.bits.source     := io.c.bits.source
    io.task.bits.isPrefetch := false.B
    io.task.bits.tmpDataID  := DontCare
    io.task.bits.set        := set
    io.task.bits.tag        := tag
    io.taskData             := io.c.bits.data

    io.c.ready := io.task.ready

    assert(!(io.c.fire && io.c.bits.size =/= log2Ceil(beatBytes).U))

    dontTouch(io)
}

object SinkC extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkC()(config), name = "SinkC", split = false)
}
