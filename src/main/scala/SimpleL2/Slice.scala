package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import SimpleL2.Configs._
import SimpleL2.Bundles.CHIBundleDownstream

class Slice()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val tl = TLBundle(tlBundleParams)
        // val chi = CHIBundleDownstream(L2CacheConfig.chiBundleParams)
    })

    io.tl <> DontCare
    // io.chi <> DontCare

    dontTouch(io)
}
