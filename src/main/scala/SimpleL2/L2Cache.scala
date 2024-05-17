package SimpleL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import Utils.GenerateVerilog
import SimpleL2.Configs.L2CacheConfig
import SimpleL2.Bundles.{CHIBundleDownstream, CHILinkCtrlIO}

class L2Cache extends Module {
    val io_tl = IO(TLBundle(L2CacheConfig.tlBundleParams)).suggestName("master_port_0_0")
    val io = IO(new Bundle {
        val chi         = CHIBundleDownstream(L2CacheConfig.chiBundleParams)
        val chiLinkCtrl = new CHILinkCtrlIO()
    })

    val tl  = io_tl
    val chi = io.chi

    tl <> DontCare
    chi <> DontCare

    val chiBridge = Module(new CHIBridge)
    chiBridge.io.chi <> chi
    io.chiLinkCtrl <> chiBridge.io.chiLinkCtrl

    val slice = Module(new Slice)
    slice.io.tl <> tl
    // slice.io.chi <> chi

    dontTouch(io)
}

object L2Cache extends App {
    GenerateVerilog(args, () => new L2Cache)
}
