package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.chi._

abstract class L2Module(implicit val p: Parameters) extends Module with HasL2Param with HadMixedStateOps
abstract class L2Bundle(implicit val p: Parameters) extends Bundle with HasL2Param

class L2Cache()(implicit p: Parameters) extends L2Module {
    val io_tl = IO(Flipped(TLBundle(tlBundleParams))).suggestName("master_port_0_0")
    val io = IO(new Bundle {
        val chi         = CHIBundleDownstream(chiBundleParams)
        val chiLinkCtrl = new CHILinkCtrlIO()
        val nodeID      = Input(UInt(12.W))
    })

    val tl  = io_tl
    val chi = io.chi

    tl  <> DontCare
    chi <> DontCare

    val slices      = Seq.fill(nrSlice)(Module(new Slice))
    val linkMonitor = Module(new LinkMonitor)

    if (nrSlice == 1) {
        slices(0).io.tl                <> tl
        linkMonitor.io.nodeID          := io.nodeID
        linkMonitor.io.in.chi          <> slices(0).io.chi
        linkMonitor.io.out.chi         <> chi
        linkMonitor.io.out.chiLinkCtrl <> io.chiLinkCtrl
    } else {
        assert(false, "L2Cache without diplomacy only support up to 1 Slice!")
    }

    dontTouch(io)
}

object L2Cache extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param(nrSlice = 1)
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new L2Cache()(config), name = "L2Cache", split = true)
}
