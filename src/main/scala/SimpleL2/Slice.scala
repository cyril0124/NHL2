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
        val tl          = Flipped(TLBundle(tlBundleParams))
        val chi         = CHIBundleDecoupled(chiBundleParams)
        val chiLinkCtrl = new CHILinkCtrlIO()
        val sliceId     = Input(UInt(bankBits.W))
    })

    io.tl          <> DontCare
    io.chi         <> DontCare
    io.chiLinkCtrl <> DontCare

    io.tl.e.ready := true.B

    /** TileLink side channels (upstream) */
    val sinkA   = Module(new SinkA)
    val sinkC   = Module(new SinkC)
    val sinkE   = Module(new SinkE)
    val sourceD = Module(new SourceD)

    /** CHI side channels (downstream) */
    val txreq = Module(new TXREQ)
    val txrsp = Module(new TXRSP)
    val txdat = Module(new TXDAT)
    val rxdat = Module(new RXDAT)
    val rxrsp = Module(new RXRSP)
    val rxsnp = Module(new RXSNP)

    /** Other modules */
    val reqArb        = Module(new RequestArbiter)
    val dir           = Module(new Directory)
    val ds            = Module(new DataStorage)
    val mainPipe      = Module(new MainPipe)
    val tempDS        = Module(new TempDataStorage)
    val missHandler   = Module(new MissHandler)
    val replayStation = Module(new ReplayStation)

    sinkA.io.a <> io.tl.a
    sinkC.io.c <> io.tl.c
    sinkE.io.e <> io.tl.e

    reqArb.io               <> DontCare
    reqArb.io.taskMSHR_s0   <> missHandler.io.tasks.mpTask
    reqArb.io.taskReplay_s1 <> replayStation.io.req_s1
    reqArb.io.taskSinkA_s1  <> sinkA.io.task
    reqArb.io.taskSinkC_s1  <> sinkC.io.task
    reqArb.io.taskSnoop_s1  <> rxsnp.io.task
    reqArb.io.dirRead_s1    <> dir.io.dirRead_s1
    reqArb.io.resetFinish   <> dir.io.resetFinish
    reqArb.io.mpStatus      <> mainPipe.io.status

    mainPipe.io                <> DontCare
    mainPipe.io.mpReq_s2       <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3     <> dir.io.dirResp_s3
    mainPipe.io.replResp_s3    <> dir.io.replResp_s3
    mainPipe.io.mshrFreeOH_s3  := missHandler.io.mshrFreeOH_s3
    mainPipe.io.replay_s4      <> replayStation.io.replay_s4
    mainPipe.io.willFull_txrsp := txrsp.io.willFull

    val cancelRefillWrite_s2 = mainPipe.io.retryTasks.stage2.fire
    ds.io.dsWrite_s2                  <> sinkC.io.dsWrite_s2
    ds.io.refillWrite_s2.valid        := tempDS.io.toDS.refillWrite_s2.valid && !cancelRefillWrite_s2
    ds.io.refillWrite_s2.bits         := tempDS.io.toDS.refillWrite_s2.bits
    ds.io.fromMainPipe.dsRead_s3      <> mainPipe.io.toDS.dsRead_s3
    ds.io.fromMainPipe.dsWrWayOH_s3   <> mainPipe.io.toDS.dsWrWayOH_s3
    ds.io.fromMainPipe.mshrId_s3      := mainPipe.io.toDS.mshrId_s3
    ds.io.toTXDAT.dsResp_s6s7.ready   := txdat.io.data_s6s7.ready
    ds.io.toSourceD.dsResp_s6s7.ready := sourceD.io.data_s6s7.ready

    dir.io.dirWrite_s3 <> mainPipe.io.dirWrite_s3

    tempDS.io.fromDS.write_s5         <> ds.io.toTempDS.write_s5
    tempDS.io.fromRXDAT.write         <> rxdat.io.toTempDS.write
    tempDS.io.fromSinkC.write         <> sinkC.io.toTempDS.write
    tempDS.io.fromReqArb.read_s1      <> reqArb.io.tempDsRead_s1
    tempDS.io.fromReqArb.dsWrSet_s1   := reqArb.io.dsWrSet_s1
    tempDS.io.fromReqArb.dsWrWayOH_s1 := reqArb.io.dsWrWayOH_s1

    sinkC.io.respDest_s4 := mainPipe.io.allocDestSinkC_s4

    missHandler.io.mshrAlloc_s3 <> mainPipe.io.mshrAlloc_s3
    missHandler.io.resps.rxdat  <> rxdat.io.resp
    missHandler.io.resps.rxrsp  <> rxrsp.io.resp
    missHandler.io.resps.sinke  <> sinkE.io.resp
    missHandler.io.resps.sinkc  <> sinkC.io.resp
    missHandler.io.mshrStatus   <> dir.io.mshrStatus
    missHandler.io.replResp_s3  <> dir.io.replResp_s3
    missHandler.io.retryTasks   <> mainPipe.io.retryTasks

    txreq.io.mshrTask  <> missHandler.io.tasks.txreq
    txreq.io.mpTask_s3 := DontCare // TODO: connect to MainPipe or remove ?
    txreq.io.sliceId   := io.sliceId

    sourceD.io                 <> DontCare
    sourceD.io.task_s2         <> mainPipe.io.sourceD_s2
    sourceD.io.data_s2         <> tempDS.io.toSourceD.data_s2
    sourceD.io.task_s6s7       <> mainPipe.io.sourceD_s6s7
    sourceD.io.data_s6s7.valid := ds.io.toSourceD.dsResp_s6s7.valid
    sourceD.io.data_s6s7.bits  := ds.io.toSourceD.dsResp_s6s7.bits.data

    txrsp.io.mshrTask  <> missHandler.io.tasks.txrsp
    txrsp.io.mpTask_s4 <> mainPipe.io.txrsp_s4

    txdat.io                 <> DontCare
    txdat.io.task_s2         <> mainPipe.io.txdat_s2
    txdat.io.data_s2         <> tempDS.io.toTXDAT.data_s2
    txdat.io.task_s6s7       <> mainPipe.io.txdat_s6s7
    txdat.io.data_s6s7.valid := ds.io.toTXDAT.dsResp_s6s7.valid
    txdat.io.data_s6s7.bits  := ds.io.toTXDAT.dsResp_s6s7.bits.data

    io.tl.d      <> sourceD.io.d
    io.tl.b      <> missHandler.io.tasks.sourceb
    io.chi.txreq <> txreq.io.out
    io.chi.txrsp <> txrsp.io.out
    io.chi.txdat <> txdat.io.out
    io.chi.rxdat <> rxdat.io.rxdat
    io.chi.rxrsp <> rxrsp.io.rxrsp
    io.chi.rxsnp <> rxsnp.io.rxsnp

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
