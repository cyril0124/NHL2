package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class Slice()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val tl  = Flipped(TLBundle(tlBundleParams))
        val chi = CHIBundleDecoupled(chiBundleParams)
    })

    io.tl  <> DontCare
    io.chi <> DontCare

    val sinkA    = Module(new SinkA)
    val reqArb   = Module(new RequestArbiter)
    val dir      = Module(new Directory)
    val mainPipe = Module(new MainPipe)

    dir.io <> DontCare

    sinkA.io.a <> io.tl.a

    reqArb.io              <> DontCare
    reqArb.io.taskSinkA_s1 <> sinkA.io.task
    reqArb.io.dirRead_s1   <> dir.io.dirRead_s1
    reqArb.io.resetFinish  <> dir.io.resetFinish

    mainPipe.io            <> DontCare
    mainPipe.io.mpReq_s2   <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3 <> dir.io.dirResp_s3

    dontTouch(reqArb.io)
    dontTouch(mainPipe.io)

    dontTouch(io)
}

object Slice extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Slice()(config), name = "Slice", split = false)
}
