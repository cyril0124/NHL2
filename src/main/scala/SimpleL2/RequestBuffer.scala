package SimpleL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import Utils.GenerateVerilog
import SimpleL2.Configs.L2CacheConfig

object RequestOwner {
    val OwnerWidth = 2
    def Level1 = "b00".U(2.W)
    def Prefetcher = "b10".U(2.W)
}

class RequestBufferEntry extends Bundle {
    val owner = UInt(RequestOwner.OwnerWidth.W)
    val opcode = UInt(3.W)
    val source = UInt(L2CacheConfig.tlBundleParams.sourceBits.W)
}

class RequestBuffer extends Module {
    val io = IO(new Bundle{
        val sinkA = Flipped(Decoupled(new TLBundleA(L2CacheConfig.tlBundleParams)))
        val owner = Input(UInt(RequestOwner.OwnerWidth.W))
    })

    io.sinkA <> DontCare
    io.sinkA.ready := true.B

    val entrySize = L2CacheConfig.nrRequestBufferEntry

    val valids = RegInit(VecInit(Seq.fill(entrySize)(false.B)))
    val buffers = RegInit(VecInit(Seq.fill(entrySize)(0.U.asTypeOf(new RequestBufferEntry))))
    dontTouch(valids)
    dontTouch(buffers)

    val insertVec = VecInit(PriorityEncoderOH((~valids.asUInt).asBools))
    assert(!(PopCount(insertVec.asUInt) > 1.U))
    dontTouch(insertVec)

    buffers.zip(insertVec).foreach { 
        case(buf, chosen) =>
            when(chosen && io.sinkA.fire) {
                buf.opcode := io.sinkA.bits.opcode
                buf.source := io.sinkA.bits.source
                buf.owner := io.owner
            }
    }

    valids.zip(insertVec).foreach {
        case (valid, chosen) =>
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
    GenerateVerilog(args, () => new RequestBuffer)
}