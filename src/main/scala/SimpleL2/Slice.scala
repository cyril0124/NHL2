package SimpleL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import SimpleL2.Configs.L2CacheConfig
import SimpleL2.Bundles.CHIBundleDownstream

class Slice extends Module {
    val io = IO(new Bundle {
        val tl = TLBundle(L2CacheConfig.tlBundleParams)
        // val chi = CHIBundleDownstream(L2CacheConfig.chiBundleParams)
    })

    io.tl <> DontCare
    // io.chi <> DontCare

    dontTouch(io)
}
