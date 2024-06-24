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

class MissHandler()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mshrAlloc_s3  = Flipped(Decoupled(new MshrAllocBundle))
        val mshrFreeOH_s3 = Output(UInt(nrMSHR.W))

        val mpTask = DecoupledIO(new TaskBundle)
        val txreq  = DecoupledIO(new CHIBundleREQ(chiBundleParams))
        val txrsp  = DecoupledIO(new CHIBundleRSP(chiBundleParams))
        val rxdat  = Flipped(ValidIO(new CHIRespBundle(chiBundleParams)))
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

    val rxdatMatchOH = UIntToOH(io.rxdat.bits.txnID)
    mshrs.zip(rxdatMatchOH.asBools).zipWithIndex.foreach { case ((mshr, en), i) =>
        mshr.io.resps.rxdat.valid := io.rxdat.valid && en
        mshr.io.resps.rxdat.bits  := io.rxdat.bits

        assert(!(mshr.io.resps.rxdat.valid && !mshr.io.status.valid), s"rxdat valid but mshr_${i} invalid")
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

    arbTask(mshrs.map(_.io.tasks.txreq), io.txreq)
    arbTask(mshrs.map(_.io.tasks.txrsp), io.txrsp)
    arbTask(mshrs.map(_.io.tasks.mpTask), io.mpTask)

    dontTouch(io)
}

object MissHandler extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MissHandler()(config), name = "MissHandler", split = false)
}
