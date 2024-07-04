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

class Slice()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val tl  = Flipped(TLBundle(tlBundleParams))
        val chi = CHIBundleDecoupled(chiBundleParams)
    })

    io.tl  <> DontCare
    io.chi <> DontCare

    io.tl.e.ready := true.B

    /** TileLink side channels (upstream) */
    val sinkA   = Module(new SinkA)
    val sinkC   = Module(new SinkC)
    val sinkE   = Module(new SinkE)
    val sourceD = Module(new SourceD)

    /** CHI side channels (downstream) */
    val txreq = Module(new TXREQ)
    val txrsp = Module(new TXRSP)
    val rxdat = Module(new RXDAT)
    val rxrsp = Module(new RXRSP)

    /** Other modules */
    val reqArb      = Module(new RequestArbiter)
    val dir         = Module(new Directory)
    val ds          = Module(new DataStorage)
    val mainPipe    = Module(new MainPipe)
    val tempDS      = Module(new TempDataStorage)
    val missHandler = Module(new MissHandler)

    sinkA.io.a <> io.tl.a
    sinkC.io.c <> io.tl.c
    sinkE.io.e <> io.tl.e

    reqArb.io                   <> DontCare
    reqArb.io.taskMSHR_s0       <> missHandler.io.tasks.mpTask
    reqArb.io.taskSinkA_s1      <> sinkA.io.task
    reqArb.io.taskSinkC_s1      <> sinkC.io.task
    reqArb.io.dirRead_s1        <> dir.io.dirRead_s1
    reqArb.io.resetFinish       <> dir.io.resetFinish
    reqArb.io.dsRefillWriteCrdv := ds.io.refillWrite.crdv

    mainPipe.io                       <> DontCare
    mainPipe.io.mpReq_s2              <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3            <> dir.io.dirResp_s3
    mainPipe.io.mshrFreeOH_s3         := missHandler.io.mshrFreeOH_s3
    mainPipe.io.replay_s4.ready       := true.B // TODO:
    mainPipe.io.dsRdCrd               := ds.io.dsRead_s3.crdv
    mainPipe.io.fromTempDS.freeDataId := tempDS.io.freeDataId

    ds.io.dsWrite_s2        <> sinkC.io.dsWrite_s2
    ds.io.dsWrWay_s3        := mainPipe.io.dsWrWay_s3
    ds.io.refillWrite.valid <> tempDS.io.toDS.dsWrite.valid
    ds.io.refillWrite.bits  := tempDS.io.toDS.dsWrite.bits
    ds.io.dsRead_s3.valid   := mainPipe.io.dsRead_s3.valid
    ds.io.dsRead_s3.bits    := mainPipe.io.dsRead_s3.bits

    dir.io.dirWrite_s3 <> mainPipe.io.dirWrite_s3

    tempDS.io.fromDS.dsResp_ds4      := ds.io.toTempDS.dsResp_ds4
    tempDS.io.fromDS.dsDest_ds4      := ds.io.toTempDS.dsDest_ds4
    tempDS.io.fromDS.dsHasDataId_ds4 := ds.io.toTempDS.dsHasDataId_ds4
    tempDS.io.fromDS.dsDataId_ds4    := ds.io.toTempDS.dsDataId_ds4
    tempDS.io.fromReqArb.read        <> reqArb.io.tempDsRead_s1
    tempDS.io.fromReqArb.dsWrSet     := reqArb.io.dsWrSet_s1
    tempDS.io.fromReqArb.dsWrWay     := reqArb.io.dsWrWay_s1
    tempDS.io.fromRXDAT.write        <> rxdat.io.toTempDS.dataWr
    tempDS.io.fromSinkC.write        <> sinkC.io.toTempDS.dataWr
    tempDS.io.flushEntry             := DontCare // TODO:
    tempDS.io.preAlloc               := mainPipe.io.fromTempDS.preAlloc
    // tempDS.io.full TOOD: output

    sinkC.io.respDest := mainPipe.io.allocDestSinkC_s4 // TODO: connect to MissHandler

    missHandler.io.mshrAlloc_s3 <> mainPipe.io.mshrAlloc_s3
    missHandler.io.resps.rxdat  <> rxdat.io.resp
    missHandler.io.resps.rxrsp  <> rxrsp.io.resp
    missHandler.io.resps.sinke  <> sinkE.io.resp
    missHandler.io.resps.sinkc  <> sinkC.io.resp

    txreq.io.mshrTask  <> missHandler.io.tasks.txreq
    txreq.io.mpTask_s3 := DontCare // TODO: connect to MainPipe

    arbTask(Seq(mainPipe.io.sourceD_s2, mainPipe.io.sourceD_s4), sourceD.io.task)
    sourceD.io.beatData     <> tempDS.io.toSourceD.beatData
    sourceD.io.dataId       := tempDS.io.freeDataId
    sourceD.io.tempDataRead <> tempDS.io.fromSourceD.read
    sourceD.io.tempDataResp <> tempDS.io.fromSourceD.resp

    rxdat.io.toTempDS.dataId       := tempDS.io.freeDataId
    rxdat.io.toTempDS.dataWr.ready := true.B // TODO:

    txrsp.io.mshrTask  <> missHandler.io.tasks.txrsp
    txrsp.io.mpTask_s3 := DontCare // TODO:

    io.tl.d      <> sourceD.io.d
    io.tl.b      <> missHandler.io.tasks.sourceb
    io.chi.txreq <> txreq.io.out
    io.chi.txrsp <> txrsp.io.out
    io.chi.rxdat <> rxdat.io.rxdat
    io.chi.rxrsp <> rxrsp.io.rxrsp

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
