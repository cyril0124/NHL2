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

    val sinkA       = Module(new SinkA)
    val sinkC       = Module(new SinkC)
    val sourceD     = Module(new SourceD)
    val reqArb      = Module(new RequestArbiter)
    val dir         = Module(new Directory)
    val ds          = Module(new DataStorage)
    val mainPipe    = Module(new MainPipe)
    val tempDS      = Module(new TempDataStorage)
    val missHandler = Module(new MissHandler)

    sourceD.io     <> DontCare
    dir.io         <> DontCare
    ds.io          <> DontCare
    tempDS.io      <> DontCare
    missHandler.io <> DontCare

    sinkA.io.a <> io.tl.a
    sinkC.io.c <> io.tl.c

    reqArb.io              <> DontCare
    reqArb.io.taskSinkA_s1 <> sinkA.io.task
    reqArb.io.taskSinkC_s1 <> sinkC.io.task
    reqArb.io.dataSinkC_s1 := sinkC.io.taskData
    reqArb.io.dirRead_s1   <> dir.io.dirRead_s1
    reqArb.io.resetFinish  <> dir.io.resetFinish
    reqArb.io.dsWrCrd      := ds.io.dsWrite_s2.crdv

    mainPipe.io                  <> DontCare
    mainPipe.io.mpReq_s2         <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3       <> dir.io.dirResp_s3
    mainPipe.io.mshrFreeOH_s3    := missHandler.io.mshrFreeOH_s3
    mainPipe.io.replay_s4.ready  := true.B // TODO:
    mainPipe.io.sourceD_s4.ready := true.B // TODO:
    mainPipe.io.dsRdCrd          := ds.io.dsRead_s3.crdv

    ds.io.dsWrite_s2.valid := reqArb.io.dsWrite_s2.valid
    ds.io.dsWrite_s2.bits  := reqArb.io.dsWrite_s2.bits
    ds.io.dsWrWay_s3       := mainPipe.io.dsWrWay_s3
    ds.io.dsRead_s3.valid  := mainPipe.io.dsRead_s3.valid
    ds.io.dsRead_s3.bits   := mainPipe.io.dsRead_s3.bits

    dir.io.dirWrite_s3 <> mainPipe.io.dirWrite_s3

    sourceD.io.task         <> mainPipe.io.sourceD_s4
    sourceD.io.data         <> tempDS.io.toSourceD.dataOut
    sourceD.io.dataId       := tempDS.io.toSourceD.dataId
    sourceD.io.tempDataRead <> tempDS.io.fromSourceD.read
    sourceD.io.tempDataResp <> tempDS.io.fromSourceD.resp

    tempDS.io.fromDS.dsResp_ds4 := ds.io.toTempDS.dsResp_ds4
    tempDS.io.fromDS.dsDest_ds4 := ds.io.toTempDS.dsDest_ds4
    // ds.io.toTXDAT.dsResp_ds4

    missHandler.io.mshrAlloc_s3 <> mainPipe.io.mshrAlloc_s3

    io.tl.d <> sourceD.io.d

    dontTouch(reqArb.io)
    dontTouch(mainPipe.io)

    dontTouch(io)
}

object Slice extends App {
    val CFG_CLIENT = sys.env.get("CFG_CLIENT").getOrElse("2")
    println(s"CFG_CLIENT = $CFG_CLIENT")

    val config = new Config((_, _, _) => {
        case L2ParamKey =>
            L2Param(
                nrClients = CFG_CLIENT.toInt
            )
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Slice()(config), name = "Slice", split = true)
}
