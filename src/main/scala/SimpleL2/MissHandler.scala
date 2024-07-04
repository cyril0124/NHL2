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

        val tasks = new MshrTasks
        val resps = new MshrResps
    })

    io <> DontCare

    val mshrs        = (0 until nrMSHR).map(i => Module(new MSHR(i)))
    val mshrValidVec = VecInit(mshrs.map(_.io.status.valid)).asUInt
    io.mshrFreeOH_s3 := PriorityEncoderOH(~mshrValidVec)
    assert(PopCount(io.mshrFreeOH_s3) <= 1.U)

    mshrs.zip(io.mshrFreeOH_s3.asBools).foreach { case (mshr, en) =>
        mshr.io <> DontCare

        mshr.io.alloc_s3.valid := io.mshrAlloc_s3.valid && en
        mshr.io.alloc_s3.bits  := io.mshrAlloc_s3.bits
    }

    val rxdat        = io.resps.rxdat
    val rxdatMatchOH = UIntToOH(rxdat.bits.txnID)
    mshrs.zip(rxdatMatchOH.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.resps.rxdat.valid := rxdat.valid && en
        mshr.io.resps.rxdat.bits  := rxdat.bits

        assert(!(mshr.io.resps.rxdat.valid && !mshr.io.status.valid), s"rxdat valid but mshr_${i} invalid")
    }
    assert(!(rxdat.fire && !rxdatMatchOH.orR), "rxdat does not match any mshr! txnID => %d/0x%x", rxdat.bits.txnID, rxdat.bits.txnID)

    val rxrsp        = io.resps.rxrsp
    val rxrspMatchOH = UIntToOH(rxrsp.bits.txnID)
    mshrs.zip(rxrspMatchOH.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.resps.rxrsp.valid := rxrsp.valid && en
        mshr.io.resps.rxrsp.bits  := rxrsp.bits

        assert(!(mshr.io.resps.rxrsp.valid && !mshr.io.status.valid), s"rxrsp valid but mshr_${i} invalid")
    }

    val sinke        = io.resps.sinke
    val sinkeMatchOH = UIntToOH(sinke.bits.sink)
    mshrs.zip(sinkeMatchOH.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.resps.sinke.valid := sinke.valid && en
        mshr.io.resps.sinke.bits  := sinke.bits

        assert(!(mshr.io.resps.sinke.valid && !mshr.io.status.valid), s"sinke valid but mshr_${i} invalid")
    }
    assert(!(sinke.fire && !sinkeMatchOH.orR), "sinke does not match any mshr! sink => %d/0x%x", sinke.bits.sink, sinke.bits.sink)

    val sinkc           = io.resps.sinkc
    val sinkcSetMatchOH = VecInit(mshrs.map(_.io.status.set === sinkc.bits.set)).asUInt
    val sinkcTagMatchOH = VecInit(mshrs.map(_.io.status.tag === sinkc.bits.tag)).asUInt
    val sinkcMatchOH    = mshrValidVec & sinkcSetMatchOH & sinkcTagMatchOH
    mshrs.zip(sinkcMatchOH.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.resps.sinkc.valid := sinkc.valid && en
        mshr.io.resps.sinkc.bits  := sinkc.bits

        assert(!(mshr.io.resps.sinkc.valid && !mshr.io.status.valid), s"sinkc valid but mshr_${i} invalid")
    }
    assert(
        !(sinkc.fire && !sinkcMatchOH.orR),
        "sinkc does not match any mshr! set => %d/0x%x, tag => %d/0x%x",
        sinkc.bits.set,
        sinkc.bits.set,
        sinkc.bits.tag,
        sinkc.bits.tag
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
