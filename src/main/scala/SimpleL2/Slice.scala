package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, IDPool}
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

    println(s"[${this.getClass().toString()}] TaskBundle bits:${(new TaskBundle).getWidth}")

    io.tl          <> DontCare
    io.chi         <> DontCare
    io.chiLinkCtrl <> DontCare

    /** TileLink side channels (upstream) */
    val sinkA   = Module(new SinkA)
    val sinkC   = Module(new SinkC)
    val sinkE   = Module(new SinkE)
    val sourceD = Module(new SourceD)
    val sourceB = Module(new SourceB)

    /** CHI side channels (downstream) */
    val txreq = Module(new TXREQ)
    val txrsp = Module(new TXRSP)
    val txdat = Module(new TXDAT)
    val rxdat = Module(new RXDAT)
    val rxrsp = Module(new RXRSP)
    val rxsnp = Module(new RXSNP)

    /** Other modules */
    val reqArb      = Module(new RequestArbiter)
    val dir         = Module(new Directory)
    val ds          = Module(new DataStorage)
    val mainPipe    = Module(new MainPipe)
    val tempDS      = Module(new TempDataStorage)
    val missHandler = Module(new MissHandler)

    // TODO: sinkIdPool backpressure when full
    val sinkIdPool = Module(new IDPool((nrMSHR until (nrMSHR + nrExtraSinkId)).toSet))

    sinkIdPool.io.alloc.valid    := sourceD.io.sinkIdAlloc.valid
    sourceD.io.sinkIdAlloc.idOut := sinkIdPool.io.alloc.idOut
    sinkIdPool.io.free.valid     := sinkE.io.sinkIdFree.valid
    sinkIdPool.io.free.idIn      := sinkE.io.sinkIdFree.idIn

    sinkA.io.a <> io.tl.a
    sinkC.io.c <> io.tl.c
    sinkE.io.e <> io.tl.e

    val replayStationSnoop = Module(new ReplayStation(nrReplayEntry = nrReplayEntrySnoop, nrSubEntry = 2 /* TODO: parameterize it */ )) // for Snoop
    val reqArbTaskSinkA    = WireInit(0.U.asTypeOf(reqArb.io.taskSinkA_s1))
    val reqArbTaskSnoop    = WireInit(0.U.asTypeOf(reqArb.io.taskSnoop_s1))
    arbTask(Seq(Queue(replayStationSnoop.io.req_s1, 1), rxsnp.io.task), reqArbTaskSnoop)

    if (!optParam.sinkaStallOnReqArb) {
        val reqBuf = Module(new RequestBufferV2)

        reqBuf.io.taskIn         <> sinkA.io.task
        reqBuf.io.mpStatus_s123  <> reqArb.io.status
        reqBuf.io.mpStatus_s4567 <> mainPipe.io.status
        reqBuf.io.mshrStatus     <> missHandler.io.mshrStatus
        reqBuf.io.bufferStatus   := sourceD.io.bufferStatus
        reqBuf.io.replay_s4      <> mainPipe.io.reqBufReplay_s4_opt.getOrElse(DontCare)

        reqArb.io.replayFreeCntSinkA := DontCare

        reqArbTaskSinkA <> reqBuf.io.taskOut
    } else {
        val replayStationSinkA = Module(new ReplayStation(nrReplayEntry = nrReplayEntrySinkA, nrSubEntry = nrClients + 1)) // for SinkA
        val reqBuf             = Module(new RequestBuffer)

        reqBuf.io.taskIn                      <> sinkA.io.task
        replayStationSinkA.io.replay_s4       <> mainPipe.io.replay_s4
        replayStationSinkA.io.replay_s4.valid := mainPipe.io.replay_s4.valid && mainPipe.io.replay_s4.bits.task.isChannelA
        replayStationSinkA.io.replay_s4.bits  := mainPipe.io.replay_s4.bits

        reqArb.io.replayFreeCntSinkA := replayStationSinkA.io.freeCnt
        arbTask(Seq(Queue(replayStationSinkA.io.req_s1, 1), reqBuf.io.taskOut), reqArbTaskSinkA)
    }

    reqArb.io.taskCMO_s1               := DontCare // TODO: CMO Task
    reqArb.io.taskMSHR_s0              <> missHandler.io.tasks.mpTask
    reqArb.io.taskSinkA_s1             <> reqArbTaskSinkA
    reqArb.io.taskSnoop_s1             <> reqArbTaskSnoop
    reqArb.io.taskSinkC_s1             <> sinkC.io.task
    reqArb.io.dirRead_s1               <> dir.io.dirRead_s1
    reqArb.io.resetFinish              <> dir.io.resetFinish
    reqArb.io.mpStatus_s4567           <> mainPipe.io.status
    reqArb.io.replayFreeCntSnoop       := replayStationSnoop.io.freeCnt
    reqArb.io.nonDataRespCnt           := sourceD.io.nonDataRespCntSinkC
    reqArb.io.mshrStatus               <> missHandler.io.mshrStatus
    reqArb.io.bufferStatus             := sourceD.io.bufferStatus
    reqArb.io.fromSinkC.willWriteDS_s1 := sinkC.io.toReqArb.willWriteDS_s1
    reqArb.io.fromSinkC.willWriteDS_s2 := sinkC.io.toReqArb.willWriteDS_s2

    mainPipe.io.reqDrop_s2_opt.foreach(_ := reqArb.io.reqDrop_s2_opt.getOrElse(false.B))
    mainPipe.io.mpReq_s2       <> reqArb.io.mpReq_s2
    mainPipe.io.dirResp_s3     <> dir.io.dirResp_s3
    mainPipe.io.replResp_s3    <> dir.io.replResp_s3
    mainPipe.io.mshrFreeOH_s3  := missHandler.io.mshrFreeOH_s3
    mainPipe.io.nonDataRespCnt := sourceD.io.nonDataRespCntMp
    mainPipe.io.txrspCnt       := txrsp.io.txrspCnt

    replayStationSnoop.io.replay_s4.valid := mainPipe.io.replay_s4.valid && mainPipe.io.replay_s4.bits.task.isChannelB
    replayStationSnoop.io.replay_s4.bits  := mainPipe.io.replay_s4.bits

    val cancelRefillWrite_s2 = mainPipe.io.retryTasks.stage2.fire && mainPipe.io.retryTasks.stage2.bits.isRetry_s2
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

    sinkC.io.respMapCancel              <> missHandler.io.respMapCancel
    sinkC.io.respDest_s4                := mainPipe.io.allocDestSinkC_s4
    sinkC.io.fromReqArb.mayReadDS_s1    := reqArb.io.toSinkC.mayReadDS_s1
    sinkC.io.fromReqArb.willRefillDS_s1 := reqArb.io.toSinkC.willRefillDS_s1
    sinkC.io.fromReqArb.mayReadDS_s2    := reqArb.io.toSinkC.mayReadDS_s2
    sinkC.io.fromReqArb.willRefillDS_s2 := reqArb.io.toSinkC.willRefillDS_s2

    missHandler.io.mshrAlloc_s3       <> mainPipe.io.mshrAlloc_s3
    missHandler.io.resps.rxdat        <> rxdat.io.resp
    missHandler.io.resps.rxrsp        <> rxrsp.io.resp
    missHandler.io.resps.sinke        <> sinkE.io.resp
    missHandler.io.resps.sinkc        <> sinkC.io.resp
    missHandler.io.mshrStatus         <> dir.io.mshrStatus
    missHandler.io.replResp_s3        <> dir.io.replResp_s3
    missHandler.io.retryTasks         <> mainPipe.io.retryTasks
    missHandler.io.mshrEarlyNested_s2 <> mainPipe.io.mshrEarlyNested_s2
    missHandler.io.mshrNested_s3      <> mainPipe.io.mshrNested_s3

    txreq.io.mshrTask  <> missHandler.io.tasks.txreq
    txreq.io.mpTask_s3 := DontCare // TODO: connect to MainPipe or remove ?
    txreq.io.sliceId   := io.sliceId

    val cancelData_s2 = reqArb.io.reqDrop_s2_opt.getOrElse(false.B)
    sourceD.io.task_s2          <> mainPipe.io.sourceD_s2
    sourceD.io.data_s2          <> tempDS.io.toSourceD.data_s2
    sourceD.io.data_s2.valid    := tempDS.io.toSourceD.data_s2.valid && !cancelData_s2
    sourceD.io.task_s4          <> mainPipe.io.sourceD_s4
    sourceD.io.task_s6s7        <> mainPipe.io.sourceD_s6s7
    sourceD.io.data_s6s7.valid  := ds.io.toSourceD.dsResp_s6s7.valid
    sourceD.io.data_s6s7.bits   := ds.io.toSourceD.dsResp_s6s7.bits.data
    sourceD.io.grantMapWillFull := sinkE.io.grantMapWillFull

    txrsp.io.mshrTask  <> missHandler.io.tasks.txrsp
    txrsp.io.mpTask_s4 <> mainPipe.io.txrsp_s4

    txdat.io.task_s2         <> mainPipe.io.txdat_s2
    txdat.io.data_s2         <> tempDS.io.toTXDAT.data_s2
    txdat.io.data_s2.valid   := tempDS.io.toTXDAT.data_s2.valid && !cancelData_s2
    txdat.io.task_s6s7       <> mainPipe.io.txdat_s6s7
    txdat.io.data_s6s7.valid := ds.io.toTXDAT.dsResp_s6s7.valid
    txdat.io.data_s6s7.bits  := ds.io.toTXDAT.dsResp_s6s7.bits.data

    sinkE.io.allocGrantMap <> sourceD.io.allocGrantMap

    if (optParam.sourcebHasLatch) {
        sourceB.io.task <> Queue(missHandler.io.tasks.sourceb, 1)
    } else {
        sourceB.io.task <> missHandler.io.tasks.sourceb
    }
    sourceB.io.grantMapStatus <> sinkE.io.grantMapStatus
    sourceB.io.mpStatus_s4567 <> mainPipe.io.status
    sourceB.io.bufferStatus   := sourceD.io.bufferStatus

    io.tl.d      <> sourceD.io.d
    io.tl.b      <> sourceB.io.b
    io.chi.txreq <> txreq.io.out
    io.chi.txrsp <> txrsp.io.out
    io.chi.txdat <> txdat.io.out
    io.chi.rxdat <> rxdat.io.rxdat
    io.chi.rxrsp <> rxrsp.io.rxrsp
    io.chi.rxsnp <> rxsnp.io.rxsnp

    dontTouch(io)
}

object Slice extends App {
    val CFG_CLIENT = sys.env.get("CFG_CLIENT").getOrElse("2")
    println(s"CFG_CLIENT = $CFG_CLIENT")

    val config = new Config((_, _, _) => {
        case L2ParamKey =>
            L2Param(
                nrClients = CFG_CLIENT.toInt,
                optParam = L2OptimizationParam(
                    reqBufOutLatch = false,
                    rxsnpHasLatch = false,
                    sinkcHasLatch = false,
                    sourcebHasLatch = false,
                    sinkaStallOnReqArb = true,
                    mshrStallOnReqArb = true
                )
            )
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Slice()(config), name = "Slice", release = false, split = true)
}
