package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._

class SinkA()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val a    = Flipped(DecoupledIO(new TLBundleA(tlBundleParams)))
        val task = DecoupledIO(new TaskBundle)
    })

    io      <> DontCare
    io.a    <> DontCare
    io.task <> DontCare

    val (tag, set, offset) = parseAddress(io.a.bits.address)
    assert(offset === 0.U)

    io.task.valid           := io.a.valid
    io.task.bits.channel    := L2Channel.ChannelA
    io.task.bits.opcode     := io.a.bits.opcode
    io.task.bits.param      := io.a.bits.param
    io.task.bits.source     := io.a.bits.source
    io.task.bits.isPrefetch := false.B
    io.task.bits.set        := set
    io.task.bits.tag        := tag
    io.task.bits.aliasOpt.map(_ := io.a.bits.user.lift(AliasKey).getOrElse(0.U))

    io.a.ready := io.task.ready

    assert(!(io.a.fire && io.a.bits.size =/= log2Ceil(blockBytes).U), "size:%d", io.a.bits.size)
    LeakChecker(io.a.valid, io.a.fire, Some("SinkA_io_a_valid"), maxCount = deadlockThreshold)

    dontTouch(io)
}

object SinkA extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkA()(config), name = "SinkA", split = false)
}
