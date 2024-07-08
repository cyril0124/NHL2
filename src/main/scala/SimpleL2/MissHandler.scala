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

        val tasks = new MshrTasks
        val resps = new MshrResps
    })

    io <> DontCare

    val mshrs        = (0 until nrMSHR).map(i => Module(new MSHR(i)))
    val mshrValidVec = VecInit(mshrs.map(_.io.status.valid)).asUInt
    io.mshrFreeOH_s3 := PriorityEncoderOH(~mshrValidVec)
    assert(PopCount(io.mshrFreeOH_s3) <= 1.U)

    val rxdat           = io.resps.rxdat
    val rxrsp           = io.resps.rxrsp
    val sinke           = io.resps.sinke
    val sinkc           = io.resps.sinkc
    val rxdatMatchOH    = UIntToOH(rxdat.bits.txnID)
    val rxrspMatchOH    = UIntToOH(rxrsp.bits.txnID)
    val sinkeMatchOH    = UIntToOH(sinke.bits.sink)
    val sinkcSetMatchOH = VecInit(mshrs.map(_.io.status.set === sinkc.bits.set)).asUInt
    val sinkcTagMatchOH = VecInit(mshrs.map { mshr =>
        val matchTag = Mux(mshr.io.status.needsRepl, mshr.io.status.metaTag, mshr.io.status.reqTag)
        matchTag === sinkc.bits.tag
    }).asUInt
    val sinkcMatchOH = mshrValidVec & sinkcSetMatchOH & sinkcTagMatchOH
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

    mshrs.zip(io.mshrFreeOH_s3.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io <> DontCare

        mshr.io.alloc_s3.valid := io.mshrAlloc_s3.valid && en
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
        assert(!(mshr.io.resps.sinke.valid && !mshr.io.status.valid), s"sinke valid but mshr_${i} invalid")

        mshr.io.resps.sinkc.valid := sinkc.valid && sinkcMatchOH(i)
        mshr.io.resps.sinkc.bits  := sinkc.bits
        assert(!(mshr.io.resps.sinkc.valid && !mshr.io.status.valid), s"sinkc valid but mshr_${i} invalid")

        io.mshrStatus(i) := mshr.io.status
    }

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

    lfsrArb(mshrs.map(_.io.tasks.txreq), io.tasks.txreq)
    lfsrArb(mshrs.map(_.io.tasks.txrsp), io.tasks.txrsp)
    lfsrArb(mshrs.map(_.io.tasks.sourceb), io.tasks.sourceb)
    lfsrArb(mshrs.map(_.io.tasks.mpTask), io.tasks.mpTask)

    dontTouch(io)
}

object MissHandler extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MissHandler()(config), name = "MissHandler", split = false)
}
