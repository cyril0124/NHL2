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
    val sourceD  = Module(new SourceD)
    val reqArb   = Module(new RequestArbiter)
    val dir      = Module(new Directory)
    val ds       = Module(new DataStorage)
    val mainPipe = Module(new MainPipe)
    val tempDS   = Module(new TempDataStorage)

    sourceD.io <> DontCare
    dir.io     <> DontCare
    ds.io      <> DontCare

    sinkA.io.a <> io.tl.a

    reqArb.io              <> DontCare
    reqArb.io.taskSinkA_s1 <> sinkA.io.task
    reqArb.io.dirRead_s1   <> dir.io.dirRead_s1
    reqArb.io.resetFinish  <> dir.io.resetFinish

    mainPipe.io            <> DontCare
    mainPipe.io.mpReq_s2   <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3 <> dir.io.dirResp_s3
    // TODO: ds.io.dsRead_s3.crdv
    mainPipe.io.replay_s4.ready  := true.B // TODO:
    mainPipe.io.sourceD_s4.ready := true.B // TODO:

    ds.io.dsRead_s3.valid := mainPipe.io.dsRead_s3.valid
    ds.io.dsRead_s3.bits  := mainPipe.io.dsRead_s3.bits

    sourceD.io.task         <> mainPipe.io.sourceD_s4
    sourceD.io.data         <> tempDS.io.toSourceD.dataOut
    sourceD.io.dataId       := tempDS.io.toSourceD.dataId
    sourceD.io.tempDataRead <> tempDS.io.fromSoruceD.read
    sourceD.io.tempDataResp <> tempDS.io.fromSoruceD.resp

    tempDS.io.fromDS.dsResp_ds4 := ds.io.toTempDS.dsResp_ds4
    tempDS.io.fromDS.dsDest_ds4 := ds.io.toTempDS.dsDest_ds4
    // ds.io.toTXDAT.dsResp_ds4

    io.tl.d <> sourceD.io.d

    dontTouch(reqArb.io)
    dontTouch(mainPipe.io)

    dontTouch(io)
}

object Slice extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Slice()(config), name = "Slice", split = true)
}
