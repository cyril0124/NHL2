package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class TXREQ()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mpTask_s3 = Flipped(DecoupledIO(new CHIBundleREQ(chiBundleParams)))
        val mshrTask  = Flipped(DecoupledIO(new CHIBundleREQ(chiBundleParams)))
        val out       = DecoupledIO(new CHIBundleREQ(chiBundleParams))
        val sliceId   = Input(UInt(bankBits.W))
    })

    val nrEntry = 16
    val queue   = Module(new Queue(new CHIBundleREQ(chiBundleParams), nrEntry))
    queue.io.enq.valid := io.mpTask_s3.valid || io.mshrTask.valid
    queue.io.enq.bits  := Mux(io.mpTask_s3.valid, io.mpTask_s3.bits, io.mshrTask.bits)

    io.mpTask_s3.ready := queue.io.enq.ready
    io.mshrTask.ready  := !io.mpTask_s3.valid && queue.io.enq.ready

    io.out <> queue.io.deq

    if (nrSlice > 1) {
        val addr = queue.io.deq.bits.addr
        val set  = addr(setBits + offsetBits - 1, offsetBits)
        val tag  = addr(tagBits + setBits + offsetBits - 1, setBits + offsetBits)
        require(io.sliceId.getWidth == bankBits)
        io.out.bits.addr := Cat(tag, set, io.sliceId, 0.U(offsetBits.W))
    }

    LeakChecker(io.out.valid, io.out.fire, Some("TXREQ_valid"), maxCount = deadlockThreshold)
}

object TXREQ extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new TXREQ()(config), name = "TXREQ", split = false)
}
