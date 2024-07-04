package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class TXRSP()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mpTask_s3 = Flipped(DecoupledIO(new CHIBundleRSP(chiBundleParams)))
        val mshrTask  = Flipped(DecoupledIO(new CHIBundleRSP(chiBundleParams)))
        val out       = DecoupledIO(new CHIBundleRSP(chiBundleParams))
    })

    io <> DontCare

    val nrEntry = 16
    val queue   = Module(new Queue(new CHIBundleRSP(chiBundleParams), nrEntry))
    queue.io.enq.valid := io.mpTask_s3.valid || io.mshrTask.valid
    queue.io.enq.bits  := Mux(io.mpTask_s3.valid, io.mpTask_s3.bits, io.mshrTask.bits)

    io.mpTask_s3.ready := queue.io.enq.ready
    io.mshrTask.ready  := !io.mpTask_s3.valid && queue.io.enq.ready

    io.out <> queue.io.deq

    dontTouch(io)
}

object TXRSP extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new TXRSP()(config), name = "TXRSP", split = false)
}
