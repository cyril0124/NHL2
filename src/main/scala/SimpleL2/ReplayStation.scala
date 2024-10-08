package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import xs.utils.FastArbiter
import Utils.GenerateVerilog
import SimpleL2.chi.Resp
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqToAugmentedSeq

object ReplayResion {
    val width            = 3
    val NoFreeMSHR       = "b001".U(width.W)
    val NoDataBufSourceD = "b010".U(width.W)
    val NoDataBufTXDAT   = "b011".U(width.W)
    val NoSpaceTXRSP     = "b100".U(width.W)
    val BufferRequest    = "b101".U(width.W)
}

class ReplayRequest(implicit p: Parameters) extends L2Bundle {
    val task   = new TaskBundle
    val reason = UInt(ReplayResion.width.W)
}

class ReplaySubEntry(implicit p: Parameters) extends L2Bundle {
    val channel     = UInt(L2Channel.width.W)
    val isCHIOpcode = Bool()
    val opcode      = UInt(setBits.W)
    val param       = UInt(math.max(3, Resp.width).W)                 // if isCHIOpcode is true, param is equals to the resp field in CHI
    val source      = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W, if isCHIOpcode is true, source is equals to the resp field in CHI
    val srcID       = UInt(chiBundleParams.nodeIdBits.W)
    val aliasOpt    = aliasBitsOpt.map(width => UInt(width.W))
    val isAliasTask = Bool()
    val retToSrc    = Bool()

    val fwdState_opt = if (supportDCT) Some(UInt(3.W)) else None
    val fwdNID_opt   = if (supportDCT) Some(UInt(chiBundleParams.nodeIdBits.W)) else None
    val fwdTxnID_opt = if (supportDCT) Some(UInt(chiBundleParams.txnIdBits.W)) else None

    def resp = param
    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
}
class ReplayEntry(nrSubEntry: Int)(implicit p: Parameters) extends L2Bundle {
    val set        = UInt(setBits.W)
    val tag        = UInt(tagBits.W)
    val subEntries = Vec(nrSubEntry, Valid(new ReplaySubEntry))
    val enqIdx     = new Counter(nrSubEntry)
    val deqIdx     = new Counter(nrSubEntry)

    def subValidVec = VecInit(subEntries.map(_.valid)).asUInt
}

class ReplayStation(nrReplayEntry: Int = 4, nrSubEntry: Int = 4)(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val replay_s4 = Flipped(ValidIO(new ReplayRequest))
        val req_s1    = DecoupledIO(new TaskBundle)
        val freeCnt   = Output(UInt((log2Ceil(nrReplayEntry) + 1).W))
    })

    println(s"[${this.getClass().toString()}] nrReplayEntry:${nrReplayEntry} nrSubEntry:${nrSubEntry}")

    val entries = Seq.fill(nrReplayEntry) { RegInit(0.U.asTypeOf(Valid(new ReplayEntry(nrSubEntry)))) }
    val freeVec = VecInit(entries.map(!_.valid)).asUInt
    val freeOH  = PriorityEncoderOH(freeVec)
    val freeIdx = OHToUInt(freeOH)

    entries.foreach(dontTouch(_))

    val validVec    = VecInit(entries.map(_.valid)).asUInt
    val matchSetVec = VecInit(entries.map(_.bits.set === io.replay_s4.bits.task.set)).asUInt
    val matchTagVec = VecInit(entries.map(_.bits.tag === io.replay_s4.bits.task.tag)).asUInt
    val matchVec    = validVec & matchSetVec & matchTagVec
    val hasMatch    = matchVec.orR
    assert(PopCount(matchVec) <= 1.U, "Multiple match")

    when(io.replay_s4.fire) {
        when(hasMatch) {
            entries.zip(matchVec.asBools).foreach { case (entry, en) =>
                when(en) {
                    val subEntry = entry.bits.subEntries(entry.bits.enqIdx.value)
                    subEntry.valid            := true.B
                    subEntry.bits.isCHIOpcode := io.replay_s4.bits.task.isCHIOpcode
                    subEntry.bits.opcode      := io.replay_s4.bits.task.opcode
                    subEntry.bits.param       := io.replay_s4.bits.task.param
                    subEntry.bits.channel     := io.replay_s4.bits.task.channel
                    subEntry.bits.source      := io.replay_s4.bits.task.source
                    subEntry.bits.retToSrc    := io.replay_s4.bits.task.retToSrc
                    subEntry.bits.srcID       := io.replay_s4.bits.task.srcID
                    subEntry.bits.isAliasTask := io.replay_s4.bits.task.isAliasTask
                    subEntry.bits.aliasOpt.foreach(_ := io.replay_s4.bits.task.aliasOpt.get)

                    if (supportDCT) {
                        subEntry.bits.fwdState_opt.get := io.replay_s4.bits.task.fwdState_opt.get
                        subEntry.bits.fwdNID_opt.get   := io.replay_s4.bits.task.fwdNID_opt.get
                        subEntry.bits.fwdTxnID_opt.get := io.replay_s4.bits.task.fwdTxnID_opt.get
                    }

                    entry.bits.enqIdx.inc()

                    assert(!subEntry.valid)
                    assert(entry.valid)
                }
            }
        }.otherwise {
            assert(freeVec.orR, "No free replay entry")

            val entry    = entries(freeIdx)
            val subEntry = entry.bits.subEntries.head
            entries.zip(freeOH.asBools).zipWithIndex.foreach { case ((entry, en), i) =>
                when(en) {
                    val subEntry = entry.bits.subEntries(entry.bits.enqIdx.value)
                    entry.valid               := true.B
                    entry.bits.set            := io.replay_s4.bits.task.set
                    entry.bits.tag            := io.replay_s4.bits.task.tag
                    subEntry.valid            := true.B
                    subEntry.bits.isCHIOpcode := io.replay_s4.bits.task.isCHIOpcode
                    subEntry.bits.opcode      := io.replay_s4.bits.task.opcode
                    subEntry.bits.param       := io.replay_s4.bits.task.param
                    subEntry.bits.channel     := io.replay_s4.bits.task.channel
                    subEntry.bits.source      := io.replay_s4.bits.task.source
                    subEntry.bits.retToSrc    := io.replay_s4.bits.task.retToSrc
                    subEntry.bits.srcID       := io.replay_s4.bits.task.srcID
                    subEntry.bits.isAliasTask := io.replay_s4.bits.task.isAliasTask
                    subEntry.bits.aliasOpt.foreach(_ := io.replay_s4.bits.task.aliasOpt.get)

                    if (supportDCT) {
                        subEntry.bits.fwdState_opt.get := io.replay_s4.bits.task.fwdState_opt.get
                        subEntry.bits.fwdNID_opt.get   := io.replay_s4.bits.task.fwdNID_opt.get
                        subEntry.bits.fwdTxnID_opt.get := io.replay_s4.bits.task.fwdTxnID_opt.get
                    }

                    entry.bits.enqIdx.inc()
                }
            }
            assert(!entry.valid, "entry should be invalid, freeIdx:%d, freeVec:%b, freeOH:%b", freeIdx, freeVec, freeOH)
            assert(!subEntry.valid, "subEntry should be invalid, freeIdx:%d, freeVec:%b, freeOH:%b", freeIdx, freeVec, freeOH)
        }
    }

    val arb = Module(new FastArbiter(new TaskBundle, nrReplayEntry))
    arb.io.in.zipWithIndex.foreach { case (in, i) =>
        val entry    = entries(i)
        val deqOH    = UIntToOH(entry.bits.deqIdx.value)
        val subEntry = Mux1H(deqOH, entry.bits.subEntries)
        in.valid             := entry.valid && subEntry.valid
        in.bits              := DontCare
        in.bits.set          := entry.bits.set
        in.bits.tag          := entry.bits.tag
        in.bits.isCHIOpcode  := subEntry.bits.isCHIOpcode
        in.bits.opcode       := subEntry.bits.opcode
        in.bits.param        := subEntry.bits.param
        in.bits.channel      := subEntry.bits.channel
        in.bits.source       := subEntry.bits.source
        in.bits.retToSrc     := subEntry.bits.retToSrc
        in.bits.srcID        := subEntry.bits.srcID
        in.bits.isAliasTask  := subEntry.bits.isAliasTask
        in.bits.isReplayTask := true.B
        in.bits.aliasOpt.foreach(_ := subEntry.bits.aliasOpt.get)

        if (supportDCT) {
            in.bits.fwdState_opt.get := subEntry.bits.fwdState_opt.get
            in.bits.fwdNID_opt.get   := subEntry.bits.fwdNID_opt.get
            in.bits.fwdTxnID_opt.get := subEntry.bits.fwdTxnID_opt.get
        }

        when(in.fire) {
            val conflictWithInput = io.replay_s4.fire && hasMatch && matchVec(i)

            entry.bits.deqIdx.inc()
            when(PopCount(entry.bits.subValidVec) === 1.U && !conflictWithInput) {
                entry.valid := false.B
            }

            assert(!(in.fire && in.bits.channel === TLChannel.ChannelC), "ChannelC should not be replayed")
        }
    }

    when(arb.io.out.fire) {
        entries.zipWithIndex.foreach { case (entry, i) =>
            when(i.U === arb.io.chosen) {
                val deqSubEntry = entry.bits.subEntries(entry.bits.deqIdx.value)
                deqSubEntry.valid := false.B

                assert(deqSubEntry.valid)
                assert(entry.valid)
            }
        }
    }

    io.req_s1 <> arb.io.out

    io.freeCnt := PopCount(freeVec)

    dontTouch(io)
}

object ReplayStation extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new ReplayStation()(config), name = "ReplayStation", split = false)
}
