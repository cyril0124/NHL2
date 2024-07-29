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

class MshrAllocBundle(implicit p: Parameters) extends L2Bundle {
    val req      = new TaskBundle
    val fsmState = new MshrFsmState
    val dirResp  = new DirResp
}

// TODO: extra MSHR for Snoop, extra MSHR for Release
class MissHandler()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mshrAlloc_s3  = Flipped(Decoupled(new MshrAllocBundle))
        val mshrFreeOH_s3 = Output(UInt(nrMSHR.W))
        val replResp_s3   = Flipped(ValidIO(new DirReplResp))
        val mshrStatus    = Vec(nrMSHR, Output(new MshrStatus))
        val tasks         = new MshrTasks
        val resps         = new MshrResps
        val retryTasks    = Flipped(new MpMshrRetryTasks)
        val mshrNested    = Input(new MshrNestedWriteback)
        val respMapCancel = DecoupledIO(UInt(mshrBits.W)) // to SinkC
    })

    io <> DontCare

    val mshrs        = (0 until nrMSHR).map(i => Module(new MSHR))
    val mshrValidVec = VecInit(mshrs.map(_.io.status.valid)).asUInt
    io.mshrFreeOH_s3 := PriorityEncoderOH(~mshrValidVec)
    assert(PopCount(io.mshrFreeOH_s3) <= 1.U)

    val rxdat           = io.resps.rxdat
    val rxrsp           = io.resps.rxrsp
    val sinke           = io.resps.sinke
    val sinkc           = io.resps.sinkc
    val rxdatMatchOH    = UIntToOH(rxdat.bits.txnID)(nrMSHR - 1, 0)
    val rxrspMatchOH    = UIntToOH(rxrsp.bits.txnID)(nrMSHR - 1, 0)
    val sinkeMatchOH    = UIntToOH(sinke.bits.sink)(nrMSHR - 1, 0)
    val waitProbeAckVec = VecInit(mshrs.map(_.io.status.waitProbeAck)).asUInt
    val sinkcSetMatchOH = VecInit(mshrs.map(_.io.status.set === sinkc.bits.set)).asUInt(nrMSHR - 1, 0)
    val sinkcTagMatchOH = VecInit(mshrs.map { mshr =>
        val matchTag = Mux(mshr.io.status.needsRepl, mshr.io.status.metaTag, mshr.io.status.reqTag)
        matchTag === sinkc.bits.tag
    }).asUInt(nrMSHR - 1, 0)
    val sinkcMatchOH = mshrValidVec & sinkcSetMatchOH & sinkcTagMatchOH & waitProbeAckVec
    assert(!(rxdat.fire && !rxdatMatchOH.orR), "rxdat does not match any mshr! txnID => %d/0x%x", rxdat.bits.txnID, rxdat.bits.txnID)
    assert(!(rxrsp.fire && !rxrspMatchOH.orR), "rxrsp does not match any mshr! txnID => %d/0x%x", rxrsp.bits.txnID, rxrsp.bits.txnID)
    assert(!(sinke.fire && !sinkeMatchOH.orR), "sinke does not match any mshr! sink => %d/0x%x", sinke.bits.sink, sinke.bits.sink)
    assert(
        !(sinkc.fire && !sinkcMatchOH.orR),
        "sinkc does not match any mshr! set => %d/0x%x, tag => %d/0x%x, addr => 0x%x",
        sinkc.bits.set,
        sinkc.bits.set,
        sinkc.bits.tag,
        sinkc.bits.tag,
        Cat(sinkc.bits.tag, sinkc.bits.set, 0.U(6.W))
    )
    assert(!(sinkc.fire && PopCount(sinkcMatchOH) > 1.U), "sinkc matches multiple mshrs! sinkcMatchOH:0b%b", sinkcMatchOH)

    val retryTasksMatchOH_s2 = UIntToOH(io.retryTasks.mshrId_s2)
    val retryTasksMatchOH_s4 = UIntToOH(io.retryTasks.mshrId_s4)

    mshrs.zip(io.mshrFreeOH_s3.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io    <> DontCare
        mshr.io.id := i.U

        mshr.io.alloc_s3.valid := io.mshrAlloc_s3.fire && en
        mshr.io.alloc_s3.bits  := io.mshrAlloc_s3.bits

        mshr.io.replResp_s3.valid := io.replResp_s3.valid && io.replResp_s3.bits.mshrId === i.U
        mshr.io.replResp_s3.bits  := io.replResp_s3.bits

        mshr.io.resps.rxdat.valid := rxdat.valid && rxdatMatchOH(i)
        mshr.io.resps.rxdat.bits  := rxdat.bits
        assert(!(mshr.io.resps.rxdat.valid && !mshr.io.status.valid), s"rxdat valid but mshr_${i} invalid")

        mshr.io.resps.rxrsp.valid := rxrsp.valid && rxrspMatchOH(i)
        mshr.io.resps.rxrsp.bits  := rxrsp.bits
        assert(!(mshr.io.resps.rxrsp.valid && !mshr.io.status.valid), s"rxrsp valid but mshr_${i} invalid")

        mshr.io.resps.sinke.valid := sinke.valid && sinkeMatchOH(i)
        mshr.io.resps.sinke.bits  := sinke.bits
        // assert(!(mshr.io.resps.sinke.valid && !mshr.io.status.valid), s"sinke valid but mshr_${i} invalid")

        mshr.io.resps.sinkc.valid := sinkc.valid && sinkcMatchOH(i)
        mshr.io.resps.sinkc.bits  := sinkc.bits
        assert(!(mshr.io.resps.sinkc.valid && !mshr.io.status.valid), s"sinkc valid but mshr_${i} invalid")

        val retry_s2 = io.retryTasks.stage2
        val retry_s4 = io.retryTasks.stage4
        mshr.io.retryTasks.stage2.valid             := retry_s2.fire && retryTasksMatchOH_s2(i)
        mshr.io.retryTasks.stage2.bits.isRetry_s2   := retry_s2.bits.isRetry_s2
        mshr.io.retryTasks.stage2.bits.accessack_s2 := retry_s2.bits.accessack_s2
        mshr.io.retryTasks.stage2.bits.cbwrdata_s2  := retry_s2.bits.cbwrdata_s2
        mshr.io.retryTasks.stage2.bits.snpresp_s2   := retry_s2.bits.snpresp_s2
        mshr.io.retryTasks.stage2.bits.grant_s2     := retry_s2.bits.grant_s2
        mshr.io.retryTasks.stage4.valid             := retry_s4.fire && retryTasksMatchOH_s4(i)
        mshr.io.retryTasks.stage4.bits.isRetry_s4   := retry_s4.bits.isRetry_s4
        mshr.io.retryTasks.stage4.bits.grant_s4     := retry_s4.bits.grant_s4
        mshr.io.retryTasks.stage4.bits.accessack_s4 := retry_s4.bits.accessack_s4
        mshr.io.retryTasks.stage4.bits.snpresp_s4   := retry_s4.bits.snpresp_s4
        mshr.io.retryTasks.stage4.bits.cbwrdata_s4  := retry_s4.bits.cbwrdata_s4

        mshr.io.nested.isMshr  := io.mshrNested.isMshr
        mshr.io.nested.mshrId  := io.mshrNested.mshrId
        mshr.io.nested.set     := io.mshrNested.set
        mshr.io.nested.tag     := io.mshrNested.tag
        mshr.io.nested.source  := io.mshrNested.source
        mshr.io.nested.snoop   := io.mshrNested.snoop
        mshr.io.nested.release := io.mshrNested.release

        io.mshrStatus(i) := mshr.io.status
    }

    /** Cancle respMap entry at [[SinkC]](no ProbeAck is needed) */
    arbTask(mshrs.map(_.io.respMapCancel), io.respMapCancel)

    /** Check nested behavior. Only one [[MSHR]] can be nested at the same time. */
    val mshrValidNestedVec    = VecInit(mshrs.map(s => s.io.status.valid && s.io.status.isChannelA)).asUInt
    val nestedSetMatchVec     = VecInit(mshrs.map(_.io.status.set === io.mshrNested.set)).asUInt
    val nestedReqTagMatchVec  = VecInit(mshrs.map(m => m.io.status.reqTag === io.mshrNested.tag)).asUInt
    val nestedMetaTagMatchVec = VecInit(mshrs.map(m => m.io.status.metaTag === io.mshrNested.tag && m.io.status.lockWay)).asUInt
    val hasNestedActions      = VecInit(io.mshrNested.snoop.elements.map(_._2).toSeq).asUInt.orR || VecInit(io.mshrNested.release.elements.map(_._2).toSeq).asUInt.orR
    assert(
        !(hasNestedActions && PopCount(nestedSetMatchVec & nestedReqTagMatchVec & mshrValidNestedVec) > 1.U),
        "nested set and reqTag should be unique, %b",
        nestedSetMatchVec & nestedReqTagMatchVec & mshrValidNestedVec
    )
    assert(
        !(hasNestedActions && PopCount(nestedSetMatchVec & nestedMetaTagMatchVec & mshrValidNestedVec) > 1.U),
        "nested set and metaTag should be unique, %b",
        nestedSetMatchVec & nestedMetaTagMatchVec & mshrValidNestedVec
    )

    val mshrCount = PopCount(mshrValidVec)
    val mshrFull  = mshrCount >= nrMSHR.U
    io.mshrAlloc_s3.ready := MuxCase(
        !mshrFull,
        Seq(
            io.mshrAlloc_s3.bits.req.isChannelA -> !(mshrCount >= (nrMSHR - 2).U),
            io.mshrAlloc_s3.bits.req.isChannelC -> !(mshrCount >= (nrMSHR - 1).U), // TODO: Release is always hit, maybe we are not required to do that?
            io.mshrAlloc_s3.bits.req.isSnoop    -> !mshrFull
        )
    )

    fastArb(mshrs.map(_.io.tasks.txreq), io.tasks.txreq)
    fastArb(mshrs.map(_.io.tasks.txrsp), io.tasks.txrsp)
    fastArb(mshrs.map(_.io.tasks.sourceb), io.tasks.sourceb)
    fastArb(mshrs.map(_.io.tasks.mpTask), io.tasks.mpTask)

    dontTouch(io)
}

object MissHandler extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MissHandler()(config), name = "MissHandler", split = false)
}
