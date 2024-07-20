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

class AllocRespSinkE(implicit p: Parameters) extends L2Bundle {
    val sink = UInt(tlBundleParams.sinkBits.W)
}

class SinkE()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val e              = Flipped(DecoupledIO(new TLBundleE(tlBundleParams)))
        val resp           = ValidIO(new TLRespBundle(tlBundleParams))
        val allocRespSinkE = Flipped(ValidIO(new AllocRespSinkE))
    })

    // TODO: Consider using OneHot
    val respMap = RegInit(VecInit(Seq.fill(nrMSHR)(0.U.asTypeOf(new Bundle {
        val valid = Bool()
        val sink  = UInt(tlBundleParams.sinkBits.W)
    }))))
    val respMapMatchOH = VecInit(respMap.map(e => e.sink === io.e.bits.sink && e.valid)).asUInt
    val hasMatch       = respMapMatchOH.orR
    val matchEntry     = Mux1H(respMapMatchOH, respMap)
    assert(PopCount(respMapMatchOH) <= 1.U)

    when(io.allocRespSinkE.fire) {
        val entry = respMap(io.allocRespSinkE.bits.sink)
        entry.valid := true.B
        entry.sink  := io.allocRespSinkE.bits.sink
        assert(!entry.valid)
    }

    when(io.resp.fire) {
        val entry = respMap(io.resp.bits.sink)
        entry.valid := false.B
        assert(entry.valid)
    }
    assert(!(io.allocRespSinkE.fire && io.resp.fire && io.allocRespSinkE.bits.sink === io.resp.bits.sink))

    io.e.ready        := true.B
    io.resp.valid     := io.e.valid && hasMatch
    io.resp.bits      := DontCare
    io.resp.bits.sink := Mux(hasMatch, matchEntry.sink, 0.U)
}

object SinkE extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkE()(config), name = "SinkE", split = false)
}
