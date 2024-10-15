package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import SimpleL2.Configs._
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeRSP._

/**
 *  [[RetryHelper]] for CHI transaction retry mechanism.
 *  This module do the following things:
 *  1. If the rxrsp is not PCrdGrant transaction, it will pass through.
 *  2. If the rxrsp is PCrdGrant transaction, it will check the PCrdRetryInfoVecs.
 *     If the PCrdRetryInfoVecs is valid, it will pass through.
 *     If the PCrdRetryInfoVecs is invalid, it will buffer the transaction and waiting for RetryAck. 
 *     After RetryAck is received, it will resend the PCrdGrant transaction.
 */
class RetryHelper(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxrspIn           = Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))
        val pCrdRetryInfoVecs = Input(Vec(nrSlice, Vec(nrMSHR, new PCrdRetryInfo)))

        val rxrspOut = Decoupled(new CHIBundleRSP(chiBundleParams))
        val sliceID  = Output(UInt(nrSlice.W))
    })

    val rxrsp = io.rxrspIn

    /** PCrdGrant needs to match the pCrdRetryInfoVec provided by each [[Slice]] to determine which [[Slice]] to send the PCrdGrant to. */
    val rxrspIsPCrdGrant  = rxrsp.bits.opcode === PCrdGrant
    val pCrdGrantMatchVec = VecInit(io.pCrdRetryInfoVecs.map(s => s.map(r => r.pCrdType === rxrsp.bits.pCrdType && r.srcID === rxrsp.bits.srcID && r.valid).reduce(_ || _))).asUInt
    val pCrdGrantArb      = Module(new RRArbiter(Bool(), nrSlice)) // If there is more than one Slice that matches the PCrdGrant, use a round-robin arbiter to choose one Slice for the PCrdGrant go in. This would be a fair policy for each Slice.
    val pCrdGrantSliceID  = pCrdGrantArb.io.chosen
    pCrdGrantArb.io.out.ready := true.B
    pCrdGrantArb.io.in.zipWithIndex.foreach { case (in, i) =>
        in.valid := pCrdGrantMatchVec(i)
        in.bits  := DontCare
    }

    /**
     * PCrdGrant did not match any [[Slice]], we should buffer it until the corresponding RetryAck is arrive.
     * After the RetryAck arrives, we can resend the PCrdGrant to the corresponding [[Slice]].
     */
    val pendingPCrdGrant  = RegInit(VecInit(Seq.fill(nrMSHR * nrSlice)(0.U.asTypeOf(Valid(new CHIBundleRSP(chiBundleParams))))))
    val pCrdGrantResendQ  = Module(new Queue(new CHIBundleRSP(chiBundleParams), 2 /* TODO: Parameterize this */ ))
    val isResendPCrdGrant = WireInit(false.B)
    val resendPCrdGrant   = WireInit(0.U.asTypeOf(Valid(new CHIBundleRSP(chiBundleParams))))

    when(rxrsp.fire && rxrspIsPCrdGrant && !pCrdGrantMatchVec.orR) {
        val freeVec      = VecInit(pendingPCrdGrant.map(!_.valid)).asUInt
        val freeOH       = PriorityEncoderOH(freeVec)
        val hasFreeEntry = freeVec.orR

        pendingPCrdGrant.zip(freeOH.asBools).foreach { case (entry, en) =>
            when(en) {
                entry.valid := true.B
                entry.bits  := rxrsp.bits
            }
        }

        assert(hasFreeEntry, "No free entry in pendingPCrdGrant")
    }

    /** Timeout check for pendingPCrdGrant */
    pendingPCrdGrant.zipWithIndex.foreach { case (entry, i) =>
        val cnt = RegInit(0.U(64.W))

        when(entry.valid) {
            cnt := cnt + 1.U
        }.elsewhen(!entry.valid) {
            cnt := 0.U
        }

        assert(
            cnt <= deadlockThreshold.U,
            s"pendingGrant[${i}] timeout! srcID => %d/0x%x pCrdType => %d/0x%x",
            entry.bits.srcID,
            entry.bits.srcID,
            entry.bits.pCrdType,
            entry.bits.pCrdType
        )
    }

    /** The later arrive RetryAck should match one and only one of the [[pendingPCrdGrant]] entries. */
    val retryAckMatchOH  = VecInit(pendingPCrdGrant.map(p => p.valid && p.bits.pCrdType === rxrsp.bits.pCrdType && p.bits.srcID === rxrsp.bits.srcID)).asUInt
    val retryAckHasMatch = retryAckMatchOH.orR
    when(rxrsp.fire && rxrsp.bits.opcode === RetryAck) {
        pendingPCrdGrant.zip(retryAckMatchOH.asBools).foreach { case (entry, en) =>
            when(en) {
                entry.valid := false.B
            }
        }

        assert(PopCount(retryAckMatchOH) <= 1.U, "RetryAck match multiple pendingPCrdGrant entry! 0b%b", retryAckMatchOH)
    }

    /** This latency is added for rxrsp channel. [[RXSRSP]] will be latched for one cycle to eliminate timming issue so the resent PCrdRGrant should delay for one cycle to provide enough time for Slice to update io.pCrdRetryInfoVec. */
    val enqLatency = 1
    val rxrspEnq   = Pipe(rxrsp.fire && rxrsp.bits.opcode === RetryAck && retryAckHasMatch, Mux1H(retryAckMatchOH, pendingPCrdGrant.map(_.bits)), enqLatency)
    pCrdGrantResendQ.io.enq.valid := rxrspEnq.valid
    pCrdGrantResendQ.io.enq.bits  := rxrspEnq.bits
    assert(!(pCrdGrantResendQ.io.enq.valid && !pCrdGrantResendQ.io.enq.ready), "pCrdGrantResendQ should always ready for enq!")

    /** Once there is no rxrspIn request, it is time for the [[pCrdGrantResendQ]] to resend the saved PCrdGrant transaction. */
    resendPCrdGrant.valid         := pCrdGrantResendQ.io.deq.valid
    resendPCrdGrant.bits          := pCrdGrantResendQ.io.deq.bits
    pCrdGrantResendQ.io.deq.ready := io.rxrspOut.ready && !io.rxrspIn.valid
    isResendPCrdGrant             := pCrdGrantResendQ.io.deq.valid && !io.rxrspIn.valid

    /** The resend PCrdGrant can also match multiple valid [[Slice]]s. To handle this, we should round-robin choose one [[Slice]] to consume the PCrdGrant transaction. */
    val pCrdGrantMatchVec_resend = VecInit(io.pCrdRetryInfoVecs.map(s => s.map(r => r.pCrdType === resendPCrdGrant.bits.pCrdType && r.srcID === resendPCrdGrant.bits.srcID && r.valid).reduce(_ || _))).asUInt
    val pCrdGrantArb_resend      = Module(new RRArbiter(Bool(), nrSlice)) // If there is more than one Slice that matches the PCrdGrant, use a round-robin arbiter to choose one Slice for the PCrdGrant go in. This would be a fair policy for each Slice.
    val pCrdGrantSliceID_resend  = pCrdGrantArb_resend.io.chosen
    pCrdGrantArb_resend.io.out.ready := io.rxrspOut.ready
    pCrdGrantArb_resend.io.in.zipWithIndex.foreach { case (in, i) =>
        in.valid := pCrdGrantMatchVec_resend(i)
        in.bits  := DontCare
    }
    assert(!(isResendPCrdGrant && io.rxrspOut.fire && !pCrdGrantMatchVec_resend.orR), "Resend PCrdGrant does not match any PCrdRetryInfo!")

    val rxrspBypass = rxrsp.valid && (pCrdGrantMatchVec.orR || rxrsp.bits.opcode =/= PCrdGrant) // The resent PCrdGrant transaction has lower priority than the valid PCrdGrant transaction(no reordering is needed) or non-PCrdGrant transaction.
    io.rxrspOut.valid := rxrspBypass || isResendPCrdGrant
    io.rxrspOut.bits  := Mux(rxrspBypass, io.rxrspIn.bits, resendPCrdGrant.bits)
    io.sliceID        := Mux(rxrspBypass, Mux(rxrspIsPCrdGrant, pCrdGrantSliceID, getSliceID(rxrsp.bits.txnID)), pCrdGrantSliceID_resend)

    /** If optParam.rxrspHasLatch is true, we should block the consective PCrdGrant that matches the last fired RetryAck since rxrsp will be latched for one cycle and the correponding io.pCrrdRetryInfoVec can then be updated.  */
    val lastValid        = RegNext(io.rxrspOut.fire, false.B)
    val lastRXRSP        = RegEnable(io.rxrspOut.bits, io.rxrspOut.fire)
    val shouldBlockRXRSP = rxrsp.bits.opcode === PCrdGrant && lastRXRSP.opcode === RetryAck && lastRXRSP.srcID === rxrsp.bits.srcID && lastRXRSP.pCrdType === rxrsp.bits.pCrdType && lastValid && optParam.rxrspHasLatch.B

    io.rxrspIn.ready := io.rxrspOut.ready && !isResendPCrdGrant && !shouldBlockRXRSP
}
