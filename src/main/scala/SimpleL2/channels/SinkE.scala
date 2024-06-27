package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class SinkE()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val e    = Flipped(DecoupledIO(new TLBundleE(tlBundleParams)))
        val resp = ValidIO(new TLRespBundle(tlBundleParams))
    })

    io.e.ready        := true.B
    io.resp.valid     := io.e.valid
    io.resp.bits.sink := io.e.bits.sink
}

object SinkE extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkE()(config), name = "SinkE", split = false)
}
