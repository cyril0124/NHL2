package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqToAugmentedSeq

class MshrAllocBundle(implicit p: Parameters) extends L2Bundle {
    val opcode   = UInt(5.W)
    val channel  = UInt(TLChannel.width.W)
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
    val source   = UInt(math.max(tlBundleParams.sourceBits, 12).W)
    val fsmState = new MshrFsmState
    val dirResp  = new DirResp

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === TLChannel.ChannelB
    def isChannelA = channel(0)
    def isChannelB = channel(1)
    def isChannelC = channel(2)
}

class MissHandler()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mshrAlloc_s3  = Flipped(Decoupled(new MshrAllocBundle))
        val mshrFreeOH_s3 = Output(UInt(nrMSHR.W))
    })

    io <> DontCare

    val mshrs        = Seq.fill(nrMSHR)(Module(new MSHR))
    val mshrValidVec = VecInit(mshrs.map(_.io.status.valid)).asUInt
    io.mshrFreeOH_s3 := PriorityEncoderOH(~mshrValidVec)
    assert(PopCount(io.mshrFreeOH_s3) <= 1.U)

    mshrs.zip(io.mshrFreeOH_s3.asBools).foreach { case (mshr, en) =>
        mshr.io <> DontCare

        mshr.io.alloc_s3.valid := io.mshrAlloc_s3.valid && en
        mshr.io.alloc_s3.bits  := io.mshrAlloc_s3.bits
    }

    val mshrCount = PopCount(mshrValidVec)
    val mshrFull  = mshrCount >= nrMSHR.U
    io.mshrAlloc_s3.ready := MuxCase(
        !mshrFull,
        Seq(
            io.mshrAlloc_s3.bits.isChannelA -> !(mshrCount >= (nrMSHR - 2).U),
            io.mshrAlloc_s3.bits.isChannelC -> !(mshrCount >= (nrMSHR - 1).U),
            io.mshrAlloc_s3.bits.isSnoop    -> !mshrFull
        )
    )

    dontTouch(io)
}

object MissHandler extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MissHandler()(config), name = "MissHandler", split = false)
}
