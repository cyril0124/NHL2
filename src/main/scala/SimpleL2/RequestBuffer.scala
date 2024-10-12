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
import xs.utils.FastArbiter

class RequestBufferEntry(implicit p: Parameters) extends L2Bundle {
    val task  = new TaskBundle
    val ready = Bool()
}

class RequestBuffer()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val taskIn  = Flipped(DecoupledIO(new TaskBundle))
        val taskOut = DecoupledIO(new TaskBundle)

        val mshrStatus = Vec(nrMSHR, Input(new MshrStatus))
    })

    val issueArb = Module(new FastArbiter(new TaskBundle, nrReqBufEntry))
    val valids   = RegInit(VecInit(Seq.fill(nrReqBufEntry)(false.B)))
    val buffers  = RegInit(VecInit(Seq.fill(nrReqBufEntry)(0.U.asTypeOf(new RequestBufferEntry))))
    val freeVec  = ~valids.asUInt
    val hasEntry = freeVec.orR
    val insertOH = PriorityEncoderOH(freeVec)

    val dupVec = VecInit(
        io.mshrStatus.map { s =>
            s.valid &&
            s.reqTag === io.taskIn.bits.tag && s.set === io.taskIn.bits.set &&
            (s.opcode === Hint || s.opcode === AcquireBlock || s.opcode === AcquirePerm)
        } ++ buffers.zipWithIndex.map { case (b, i) =>
            valids(i) &&
            b.task.set === io.taskIn.bits.set && b.task.tag === io.taskIn.bits.tag
        }
    ).asUInt
    val isDupPrefetch = io.taskIn.bits.opcode === Hint && dupVec.orR // Duplicate prefetch requests will be ignored

    val taskOut   = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    val storeTask = io.taskIn.valid && !taskOut.ready && !(enablePrefetch.B && isDupPrefetch)
    io.taskIn.ready := hasEntry

    buffers.zipWithIndex.zip(insertOH.asBools).foreach { case ((buf, i), en) =>
        when(en && storeTask && io.taskIn.fire) {
            valids(i) := true.B
            buf.task  := io.taskIn.bits
            assert(!valids(i))
        }
    }

    issueArb.io.in.zipWithIndex.foreach { case (in, i) =>
        in.valid := valids(i) // && buffers(i).ready // TODO: ready
        in.bits  := buffers(i).task

        when(in.fire) {
            valids(i) := false.B
            assert(valids(i))
        }
    }

    taskOut               <> issueArb.io.out
    taskOut.bits          := Mux(io.taskIn.fire, io.taskIn.bits, issueArb.io.out.bits)
    taskOut.valid         := io.taskIn.fire && !(enablePrefetch.B && isDupPrefetch) || issueArb.io.out.valid
    issueArb.io.out.ready := taskOut.ready && !io.taskIn.fire

    println(s"[${this.getClass().toString()}] reqBufOutLatch:${optParam.reqBufOutLatch}")

    if (optParam.reqBufOutLatch) {
        io.taskOut <> Queue(taskOut, 1)
    } else {
        io.taskOut <> taskOut
    }

    dontTouch(storeTask)
}

object RequestBuffer extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestBuffer()(config), name = "RequestBuffer", split = false)
}
