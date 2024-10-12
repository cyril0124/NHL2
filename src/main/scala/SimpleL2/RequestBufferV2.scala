package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqToAugmentedSeq
import xs.utils.{FastArbiter, ParallelMax}
import Utils.LeakChecker

object ReqBufState {
    val width   = 2
    val INVALID = "b00".U(width.W) // 0
    val WAIT    = "b01".U(width.W) // 1
    val SENT    = "b10".U(width.W) // 2
}

class RequestBufferEntryV2(implicit p: Parameters) extends L2Bundle {
    val age   = UInt(log2Ceil(nrReqBufEntry).W)
    val state = UInt(ReqBufState.width.W)
    val task  = new TaskBundle
    val ready = Bool()
}

class ReqBufReplay(implicit p: Parameters) extends L2Bundle {
    val shouldReplay = Bool()
    val source       = UInt(tlBundleParams.sourceBits.W)
}

/**
 * [[RequestBufferV2]] is different from [[RequestBuffer]] for the following main reasons:
 * 1. It manage the internal buffers using state machine (the state is shown in object [[ReqBufState]]).
 * 2. Support replay for the requests that cannot be accepted by the [[MainPipe]].
 * 3. It reads all the status signals from other module and update the internal ready state every cycle for any valid entry.
 */
class RequestBufferV2()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val taskIn    = Flipped(DecoupledIO(new TaskBundle))
        val taskOut   = DecoupledIO(new TaskBundle)
        val replay_s4 = Flipped(ValidIO(new ReqBufReplay))

        val mshrStatus     = Vec(nrMSHR, Input(new MshrStatus))
        val mpStatus_s123  = Input(new MpStatus123)
        val mpStatus_s4567 = Input(new MpStatus4567)
        val bufferStatus   = Input(new BufferStatusSourceD) // from SourceD
    })

    val issueArb = Module(new FastArbiter(new TaskBundle, nrReqBufEntry))
    val buffers  = RegInit(VecInit(Seq.fill(nrReqBufEntry)(0.U.asTypeOf(new RequestBufferEntryV2))))
    val freeVec  = VecInit(buffers.map(_.state === ReqBufState.INVALID)).asUInt
    val hasEntry = freeVec.orR
    val insertOH = PriorityEncoderOH(freeVec)

    val taskOut = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    io.taskIn.ready := hasEntry

    val replayMatchVec = VecInit(buffers.map(buf => buf.task.source === io.replay_s4.bits.source && buf.state =/= ReqBufState.INVALID)).asUInt
    assert(!(io.replay_s4.fire && PopCount(replayMatchVec) > 1.U), "replay_s4 match multiple buffers: %b source: %d", replayMatchVec, io.replay_s4.bits.source)

    def addrConflict(set: UInt, tag: UInt): Bool = {
        val mshrAddrConflict = VecInit(io.mshrStatus.map { case s =>
            s.valid && s.set === set && (s.reqTag === tag || s.lockWay && s.metaTag === tag)
        }).asUInt.orR

        // io.mpStatus_s4567 provides stage info from stage 4 to stage 7.
        val mpAddrConflict = VecInit(io.mpStatus_s4567.elements.map { case (name: String, stage: MpStageInfo) =>
            stage.valid && stage.isRefill && stage.set === set && stage.tag === tag
        }.toSeq).asUInt.orR

        val bufferAddrConflict = io.bufferStatus.valid && io.bufferStatus.set === set && io.bufferStatus.tag === tag

        mshrAddrConflict || mpAddrConflict || bufferAddrConflict
    }

    val dupVec = VecInit(
        io.mshrStatus.map { s =>
            s.valid &&
            s.reqTag === io.taskIn.bits.tag && s.set === io.taskIn.bits.set &&
            (s.opcode === Hint || s.opcode === AcquireBlock || s.opcode === AcquirePerm)
        } ++ buffers.map { b =>
            b.state =/= ReqBufState.INVALID &&
            b.task.set === io.taskIn.bits.set && b.task.tag === io.taskIn.bits.tag
        }
    ).asUInt
    val isDupPrefetch = io.taskIn.bits.opcode === Hint && dupVec.orR // Duplicate prefetch requests will be ignored

    buffers.zipWithIndex.zip(insertOH.asBools).foreach { case ((buf, i), en) =>
        when(en && io.taskIn.fire && !(enablePrefetch.B && isDupPrefetch)) {
            buf.state := ReqBufState.WAIT
            buf.task  := io.taskIn.bits
            assert(buf.state === ReqBufState.INVALID)
        }

        when(buf.state =/= ReqBufState.INVALID) {
            when(io.replay_s4.fire && io.replay_s4.bits.source === buf.task.source) {
                when(io.replay_s4.bits.shouldReplay) {
                    buf.state             := ReqBufState.WAIT
                    buf.task.isReplayTask := true.B
                }.otherwise {
                    buf.state := ReqBufState.INVALID
                }
            }

            val buffersExceptMe    = buffers.zipWithIndex.filter(_._2 != i).collect(_._1)
            val addrConflict_all   = addrConflict(buf.task.set, buf.task.tag)
            val setConflict_reqBuf = buffersExceptMe.map(b => b.state === ReqBufState.SENT && b.task.set === buf.task.set).reduce(_ || _) // Check for set conflict with other buffers. If there has any buffer in the same set, then there is a set conflict.
            val mshrSameSetVec     = VecInit(io.mshrStatus.map(s => s.valid && s.isChannelA && s.set === buf.task.set))
            val noFreeWay          = (PopCount(mshrSameSetVec) + setConflict_reqBuf) >= ways.U                                            // If there is no free way, the request should stay in the buffer.
            val setConflict_mp     = VecInit(io.mpStatus_s123.elements.map { case (name: String, stage: MpStageInfo) => stage.valid && stage.set === buf.task.set }.toSeq).asUInt.orR

            // Update the ready signal for this buffer based on conflict checks
            buf.ready := !addrConflict_all && !setConflict_reqBuf && !setConflict_mp && !noFreeWay
        }
    }

    // If the input has multiple valid, choose the one with the oldest age
    val inputValidVec = VecInit(buffers.map(buf => buf.state === ReqBufState.WAIT && buf.ready)).asUInt
    val maxAge        = ParallelMax(buffers.map(buf => buf.age))
    val inputMaskVec  = VecInit(inputValidVec.asBools.zipWithIndex.map { case (valid, i) => valid && buffers(i).age === maxAge }).asUInt

    issueArb.io.in.zipWithIndex.zip(buffers).foreach { case ((in, i), buf) =>
        in.valid := buf.state === ReqBufState.WAIT && buf.ready && inputMaskVec(i)
        in.bits  := buf.task

        when(in.fire) {
            buf.age   := 0.U
            buf.state := ReqBufState.SENT
            assert(buf.state === ReqBufState.WAIT, "buf.state => 0b%b", buf.state)

            val buffersExceptMe = buffers.zipWithIndex.filter(_._2 != i).collect(_._1)
            buffersExceptMe.foreach { buf =>
                when(buf.state === ReqBufState.WAIT && buf.ready && buf.age < (nrReqBufEntry - 1).U) {
                    // The buffer has not been chosen as candidate for output, so its age is incremented
                    buf.age := buf.age + 1.U
                }
            }
        }
    }

    taskOut <> issueArb.io.out

    println(s"[${this.getClass().toString()}] reqBufOutLatch:${optParam.reqBufOutLatch}")

    if (optParam.reqBufOutLatch) {
        val chosenQ = Module(
            new Queue(
                new Bundle {
                    val task  = new TaskBundle
                    val bufId = UInt(log2Ceil(nrReqBufEntry).W)
                },
                entries = 1,
                pipe = false, // TODO:
                flow = false
            )
        )
        val cancel = !buffers(chosenQ.io.deq.bits.bufId).ready

        chosenQ.io.enq.valid      := taskOut.valid
        chosenQ.io.enq.bits.task  := taskOut.bits
        chosenQ.io.enq.bits.bufId := issueArb.io.chosen
        taskOut.ready             := chosenQ.io.enq.ready

        chosenQ.io.deq.ready := io.taskOut.ready || cancel
        io.taskOut.valid     := chosenQ.io.deq.valid && !cancel
        io.taskOut.bits      := chosenQ.io.deq.bits.task

        when(chosenQ.io.deq.fire && cancel) {
            buffers(chosenQ.io.deq.bits.bufId).state := ReqBufState.WAIT
            assert(buffers(chosenQ.io.deq.bits.bufId).state === ReqBufState.SENT, "buf.state => 0b%b", buffers(chosenQ.io.deq.bits.bufId).state)
        }
    } else {
        io.taskOut <> taskOut
    }

    buffers.zipWithIndex.foreach { case (buf, i) =>
        LeakChecker(buf.state =/= ReqBufState.INVALID, buf.state === ReqBufState.INVALID, Some(s"buffers_valid_${i}"), maxCount = deadlockThreshold)
    }
}

object RequestBufferV2 extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestBufferV2()(config), name = "RequestBufferV2", split = false)
}
