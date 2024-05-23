package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._

object RequestOwner {
    val OwnerWidth = 2
    def Level1 = "b00".U(2.W)
    def Prefetcher = "b10".U(2.W)
}

class RequestBufferEntry(implicit p: Parameters) extends L2Bundle {
    val owner  = UInt(RequestOwner.OwnerWidth.W)
    val opcode = UInt(3.W)
    val source = UInt(tlBundleParams.sourceBits.W)
}

class RequestBuffer()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val sinkA = Flipped(Decoupled(new TLBundleA(tlBundleParams)))
        val owner = Input(UInt(RequestOwner.OwnerWidth.W))
    })

    io.sinkA       <> DontCare
    io.sinkA.ready := true.B

    val valids  = RegInit(VecInit(Seq.fill(nrRequestBufferEntry)(false.B)))
    val buffers = RegInit(VecInit(Seq.fill(nrRequestBufferEntry)(0.U.asTypeOf(new RequestBufferEntry))))
    dontTouch(valids)
    dontTouch(buffers)

    val insertVec = VecInit(PriorityEncoderOH((~valids.asUInt).asBools))
    assert(!(PopCount(insertVec.asUInt) > 1.U))
    dontTouch(insertVec)

    buffers.zip(insertVec).foreach { case (buf, chosen) =>
        when(chosen && io.sinkA.fire) {
            buf.opcode := io.sinkA.bits.opcode
            buf.source := io.sinkA.bits.source
            buf.owner  := io.owner
        }
    }

    valids.zip(insertVec).foreach { case (valid, chosen) =>
        when(chosen && io.sinkA.fire) {
            assert(valid === false.B)

            valid := true.B
        }
    }

    // TODO: LFSR Arbiter output         ||
    //       Normal Arbiter output       ||
    //       Round Robin Arbiter Output

    // TODO: bypass

    dontTouch(io)
}

object RequestBuffer extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestBuffer()(config), name = "RequestBuffer", split = false)
}
