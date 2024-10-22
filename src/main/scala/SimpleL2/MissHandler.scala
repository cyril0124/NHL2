package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.SeqToAugmentedSeq
import xs.utils.ResetRRArbiter
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeRSP._

class MshrAllocBundle(implicit p: Parameters) extends L2Bundle {
    val req      = new TaskBundle
    val fsmState = new MshrFsmState
    val dirResp  = new DirResp
    val mshrId   = UInt(mshrBits.W)

    // for reallocation(snpresp)
    val realloc     = Bool()
    val snpGotDirty = Bool()
}

// TODO: extra MSHR for Snoop, extra MSHR for Release
class MissHandler()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mshrEarlyNested_s2 = Input(new MshrEarlyNested)
        val mshrAlloc_s3       = Flipped(Decoupled(new MshrAllocBundle))
        val mshrFreeOH_s3      = Output(UInt(nrMSHR.W))
        val replResp_s3        = Flipped(ValidIO(new DirReplResp))
        val mshrNested_s3      = Input(new MshrNestedWriteback)
        val mshrStatus         = Vec(nrMSHR, Output(new MshrStatus))
        val tasks              = new MshrTasks
        val resps              = new MshrResps
        val retryTasks         = Flipped(new MpMshrRetryTasks)
        val respMapCancel      = DecoupledIO(UInt(mshrBits.W))          // to SinkC
        val pCrdRetryInfoVec   = Output(Vec(nrMSHR, new PCrdRetryInfo)) // used by L2 top to match the desired Slice
        val sliceId            = Input(UInt(bankBits.W))
    })

    io <> DontCare

    val mshrs        = (0 until nrMSHR).map(i => Module(new MSHR))
    val mshrValidVec = VecInit(mshrs.map(_.io.status.valid)).asUInt
    io.mshrFreeOH_s3 := PriorityEncoderOH(~mshrValidVec)
    assert(PopCount(io.mshrFreeOH_s3) <= 1.U)

    val rxdat           = io.resps.rxdat
    val sinke           = io.resps.sinke
    val sinkc           = io.resps.sinkc
    val rxdatMatchOH    = UIntToOH(rxdat.bits.txnID)(nrMSHR - 1, 0)
    val sinkeMatchOH    = UIntToOH(sinke.bits.sink)(nrMSHR - 1, 0)
    val waitProbeAckVec = VecInit(mshrs.map(_.io.status.waitProbeAck)).asUInt
    val sinkcSetMatchOH = VecInit(mshrs.map(_.io.status.set === sinkc.bits.set)).asUInt(nrMSHR - 1, 0)
    val sinkcTagMatchOH = VecInit(mshrs.map { mshr =>
        val matchTag = Mux(mshr.io.status.needsRepl, mshr.io.status.metaTag, mshr.io.status.reqTag)
        matchTag === sinkc.bits.tag
    }).asUInt(nrMSHR - 1, 0)
    val sinkcMatchOH = mshrValidVec & sinkcSetMatchOH & sinkcTagMatchOH & waitProbeAckVec
    assert(!(rxdat.fire && !rxdatMatchOH.orR), "rxdat does not match any mshr! txnID => %d/0x%x", rxdat.bits.txnID, rxdat.bits.txnID)
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

    /**
     * RXRSP is special compared to other channels because it would receive PCrdGrant which does not contain a valid TxnID that can be used to determine which MSHR this PCrdGrant is for.
     * Therefore, we need to use the PCrdRetryInfo of each MSHR to determine which MSHR this PCrdGrant is for.
     */
    val rxrsp             = io.resps.rxrsp
    val rxrspIsPCrdGrant  = rxrsp.bits.chiOpcode === PCrdGrant
    val pCrdGrantMatchVec = VecInit(mshrs.map(m => m.io.pCrdRetryInfo.valid && m.io.pCrdRetryInfo.pCrdType === rxrsp.bits.pCrdType && m.io.pCrdRetryInfo.srcID === rxrsp.bits.srcID)).asUInt
    val pCrdGrantArb      = Module(new ResetRRArbiter(Bool(), nrMSHR)) // If there is more than one MSHR that matches the PCrdGrant, use a round-robin arbiter to choose one MSHR for the PCrdGrant go in. This would be a fair policy for each MSHR.
    val pCrdGrantMatchOH  = UIntToOH(pCrdGrantArb.io.chosen)(nrMSHR - 1, 0)
    val rxrspMatchOH      = Mux(rxrspIsPCrdGrant, pCrdGrantMatchOH, UIntToOH(rxrsp.bits.txnID)(nrMSHR - 1, 0))
    pCrdGrantArb.io.in.zipWithIndex.foreach { case (in, i) =>
        in.valid := rxrsp.valid && pCrdGrantMatchVec(i)
        in.bits  := DontCare
    }
    pCrdGrantArb.io.out.ready := true.B

    assert(
        !(rxrsp.fire && !rxrspMatchOH.orR),
        "rxrsp does not match any mshr! txnID => %d/0x%x opcode => %d/0x%x isPCrdGrant => %d",
        rxrsp.bits.txnID,
        rxrsp.bits.txnID,
        rxrsp.bits.chiOpcode,
        rxrsp.bits.chiOpcode,
        rxrspIsPCrdGrant
    )

    mshrs.zip(UIntToOH(io.mshrAlloc_s3.bits.mshrId).asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.id      := i.U
        mshr.io.sliceId := io.sliceId

        mshr.io.alloc_s3.valid := io.mshrAlloc_s3.fire && en
        mshr.io.alloc_s3.bits  := io.mshrAlloc_s3.bits

        mshr.io.replResp_s3.valid := io.replResp_s3.valid && io.replResp_s3.bits.mshrId === i.U
        mshr.io.replResp_s3.bits  := io.replResp_s3.bits

        mshr.io.resps.rxdat.valid := rxdat.valid && rxdatMatchOH(i)
        mshr.io.resps.rxdat.bits  := rxdat.bits
        assert(!(mshr.io.resps.rxdat.valid && !mshr.io.status.valid), s"rxdat valid but mshr_${i} invalid")

        mshr.io.resps.rxrsp.valid := rxrsp.valid && rxrspMatchOH(i)
        mshr.io.resps.rxrsp.bits  := rxrsp.bits
        assert(
            !(mshr.io.resps.rxrsp.valid && !mshr.io.status.valid),
            s"rxrsp valid but mshr_${i} invalid rxrspIsPCrdGrant:%d rxrspMatchOH:0b%b",
            rxrspIsPCrdGrant,
            rxrspMatchOH
        )

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
        mshr.io.retryTasks.stage2.bits.compdat_opt_s2.foreach(_ := retry_s2.bits.compdat_opt_s2.getOrElse(false.B))

        mshr.io.retryTasks.stage4.valid             := retry_s4.fire && retryTasksMatchOH_s4(i)
        mshr.io.retryTasks.stage4.bits.isRetry_s4   := retry_s4.bits.isRetry_s4
        mshr.io.retryTasks.stage4.bits.grant_s4     := retry_s4.bits.grant_s4
        mshr.io.retryTasks.stage4.bits.accessack_s4 := retry_s4.bits.accessack_s4
        mshr.io.retryTasks.stage4.bits.snpresp_s4   := retry_s4.bits.snpresp_s4
        mshr.io.retryTasks.stage4.bits.cbwrdata_s4  := retry_s4.bits.cbwrdata_s4
        mshr.io.retryTasks.stage4.bits.compdat_opt_s4.foreach(_ := retry_s4.bits.compdat_opt_s4.getOrElse(false.B))

        mshr.io.earlyNested := io.mshrEarlyNested_s2

        mshr.io.nested.isMshr  := io.mshrNested_s3.isMshr
        mshr.io.nested.mshrId  := io.mshrNested_s3.mshrId
        mshr.io.nested.set     := io.mshrNested_s3.set
        mshr.io.nested.tag     := io.mshrNested_s3.tag
        mshr.io.nested.source  := io.mshrNested_s3.source
        mshr.io.nested.snoop   := io.mshrNested_s3.snoop
        mshr.io.nested.release := io.mshrNested_s3.release

        io.mshrStatus(i) := mshr.io.status

        io.pCrdRetryInfoVec(i) := mshr.io.pCrdRetryInfo
    }

    /** Cancle respMap entry at [[SinkC]](no ProbeAck is needed) */
    arbTask(mshrs.map(_.io.respMapCancel), io.respMapCancel)

    /** Check nested behavior. Only one [[MSHR]] can be nested at the same time. */
    val mshrValidNestedVec    = VecInit(mshrs.map(s => s.io.status.valid && s.io.status.isChannelA && !s.io.status.state.isInvalid)).asUInt
    val nestedSetMatchVec     = VecInit(mshrs.map(_.io.status.set === io.mshrNested_s3.set)).asUInt
    val nestedReqTagMatchVec  = VecInit(mshrs.map(m => m.io.status.reqTag === io.mshrNested_s3.tag)).asUInt
    val nestedMetaTagMatchVec = VecInit(mshrs.map(m => m.io.status.metaTag === io.mshrNested_s3.tag && m.io.status.lockWay)).asUInt
    val hasNestedActions      = VecInit(io.mshrNested_s3.snoop.elements.map(_._2).toSeq).asUInt.orR || VecInit(io.mshrNested_s3.release.elements.map(_._2).toSeq).asUInt.orR
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
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new MissHandler()(config), name = "MissHandler", split = false)
}
