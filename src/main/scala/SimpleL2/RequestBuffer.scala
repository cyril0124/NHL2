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
import xs.utils.FastArbiter

class RequestBufferEntry(implicit p: Parameters) extends L2Bundle {
    val task  = new TaskBundle
    val ready = Bool()
}

class RequestBuffer()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val taskIn  = Flipped(DecoupledIO(new TaskBundle))
        val taskOut = DecoupledIO(new TaskBundle)
    })

    val issueArb = Module(new FastArbiter(new TaskBundle, nrReqBufEntry))
    val valids   = RegInit(VecInit(Seq.fill(nrReqBufEntry)(false.B)))
    val buffers  = RegInit(VecInit(Seq.fill(nrReqBufEntry)(0.U.asTypeOf(new RequestBufferEntry))))
    val freeVec  = ~valids.asUInt
    val hasEntry = freeVec.orR
    val insertOH = PriorityEncoderOH(freeVec)

    val storeTask = io.taskIn.valid && !io.taskOut.ready
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

    io.taskOut            <> issueArb.io.out
    io.taskOut.bits       := Mux(io.taskIn.fire, io.taskIn.bits, issueArb.io.out.bits)
    io.taskOut.valid      := io.taskIn.fire || issueArb.io.out.valid
    issueArb.io.out.ready := io.taskOut.ready && !io.taskIn.fire

    dontTouch(storeTask)
}

object RequestBuffer extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestBuffer()(config), name = "RequestBuffer", split = false)
}
