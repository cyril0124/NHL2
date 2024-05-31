package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles.{CHIBundleDownstream, CHILinkCtrlIO}

abstract class L2Module(implicit val p: Parameters) extends Module with HasL2Param
abstract class L2Bundle(implicit val p: Parameters) extends Bundle with HasL2Param

class L2Cache()(implicit p: Parameters) extends L2Module {
    val io_tl = IO(Flipped(TLBundle(tlBundleParams))).suggestName("master_port_0_0")
    val io = IO(new Bundle {
        val chi         = CHIBundleDownstream(chiBundleParams)
        val chiLinkCtrl = new CHILinkCtrlIO()
    })

    val tl  = io_tl
    val chi = io.chi

    tl  <> DontCare
    chi <> DontCare

    val chiBridge = Module(new CHIBridge)
    chi            <> chiBridge.io.out.chi
    io.chiLinkCtrl <> chiBridge.io.out.chiLinkCtrl

    val slice = Module(new Slice)
    slice.io.tl <> tl
    // slice.io.chi <> chi

    dontTouch(io)
}

object L2Cache extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new L2Cache()(config), name = "L2Cache", split = true)
}
