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
        val a              = Flipped(DecoupledIO(new TLBundleA(tlBundleParams)))
        val prefetchReqOpt = if (enablePrefetch) Some(Flipped(DecoupledIO(new coupledL2.prefetch.PrefetchReq))) else None
        val task           = DecoupledIO(new TaskBundle)
        val sliceId        = Input(UInt(bankBits.W))
    })

    io      <> DontCare
    io.a    <> DontCare
    io.task <> DontCare

    if (enablePrefetch) {
        val (tag, set, offset) = parseAddress(io.a.bits.address)
        assert(offset === 0.U)

        io.task.valid        := io.a.valid || io.prefetchReqOpt.get.valid
        io.task.bits.channel := L2Channel.ChannelA

        when(io.a.valid) {
            io.task.bits.opcode := io.a.bits.opcode
            io.task.bits.param  := io.a.bits.param
            io.task.bits.source := io.a.bits.source
            io.task.bits.set    := set
            io.task.bits.tag    := tag

            io.task.bits.vaddrOpt.foreach(_ := io.a.bits.user.lift(coupledL2.VaddrKey).getOrElse(0.U))
            io.task.bits.needHintOpt.foreach(_ := io.a.bits.user.lift(huancun.PrefetchKey).getOrElse(false.B))
            io.task.bits.aliasOpt.foreach(_ := io.a.bits.user.lift(AliasKey).getOrElse(0.U))
        }.otherwise {
            val req    = io.prefetchReqOpt.get.bits
            val bankId = req.set(bankBits - 1, 0)
            assert(!(io.prefetchReqOpt.get.fire && bankId =/= io.sliceId), "[prefetchReq] bankId:%d =/= sliceId:%d", bankId, io.sliceId)

            io.task.bits.opcode := TLMessages.Hint
            io.task.bits.param  := Mux(req.needT, TLHints.PREFETCH_WRITE, TLHints.PREFETCH_READ)
            io.task.bits.source := req.source
            io.task.bits.set    := req.set >> bankBits.U
            io.task.bits.tag    := req.tag

            io.task.bits.vaddrOpt.foreach(_ := req.vaddr.getOrElse(0.U))
            io.task.bits.needHintOpt.foreach(_ := false.B)
            io.task.bits.aliasOpt.foreach(_ := 0.U)
        }

        io.a.ready := io.task.ready
        assert(!(io.a.fire && io.a.bits.size =/= log2Ceil(blockBytes).U), "size:%d", io.a.bits.size)
        LeakChecker(io.a.valid, io.a.fire, Some("SinkA_io_a_valid"), maxCount = deadlockThreshold)

        io.prefetchReqOpt.foreach { req =>
            req.ready := io.task.ready && !io.a.valid
            LeakChecker(req.valid, req.fire, Some("prefetch_valid"), maxCount = deadlockThreshold)
        }
    } else {
        val (tag, set, offset) = parseAddress(io.a.bits.address)
        assert(offset === 0.U)

        io.task.valid        := io.a.valid
        io.task.bits.channel := L2Channel.ChannelA
        io.task.bits.opcode  := io.a.bits.opcode
        io.task.bits.param   := io.a.bits.param
        io.task.bits.source  := io.a.bits.source
        io.task.bits.set     := set
        io.task.bits.tag     := tag

        io.task.bits.vaddrOpt.foreach(_ := io.a.bits.user.lift(coupledL2.VaddrKey).getOrElse(0.U))
        io.task.bits.needHintOpt.foreach(_ := io.a.bits.user.lift(huancun.PrefetchKey).getOrElse(false.B))
        io.task.bits.aliasOpt.foreach(_ := io.a.bits.user.lift(AliasKey).getOrElse(0.U))

        io.a.ready := io.task.ready

        assert(!(io.a.fire && io.a.bits.size =/= log2Ceil(blockBytes).U), "size:%d", io.a.bits.size)
        LeakChecker(io.a.valid, io.a.fire, Some("SinkA_io_a_valid"), maxCount = deadlockThreshold)
    }

    dontTouch(io)
}

object SinkA extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkA()(config), name = "SinkA", split = false)
}
