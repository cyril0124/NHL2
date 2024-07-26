package Utils

import chisel3._
import chisel3.util._
import Utils.GenerateVerilog

class SkidBuffer[T <: Data](gen: T, overrideName: Boolean = true) extends Module {
    override def desiredName = if (overrideName) {
        s"SkidBuffer_${gen.typeName}"
    } else {
        super.desiredName
    }

    val io = IO(new Bundle {
        val enq  = Flipped(Decoupled(gen))
        val deq  = Decoupled(gen)
        val full = Output(Bool())
    })

    val buffer = RegInit(0.U.asTypeOf(gen))
    val full   = RegInit(false.B)
    val stall  = !io.deq.ready && io.deq.valid

    when(stall && io.enq.fire) {
        buffer := io.enq.bits
        full   := true.B
    }.elsewhen(io.deq.fire) {
        full := false.B
    }

    io.enq.ready := !full

    io.deq.valid := full || io.enq.valid
    io.deq.bits  := Mux(full, buffer, io.enq.bits)

    io.full := full
}

// object SkidBuffer {
//     def apply[T <: Data](gen: T, overrideName: Boolean = true): SkidBuffer[T] = {
//         val skidBuffer = Module(new SkidBuffer(gen, overrideName))
//         skidBuffer
//     }
// }

object SkidBuffer extends App {

    class TestBundle extends Bundle {
        val data = UInt(8.W)
    }

    GenerateVerilog(args, () => new SkidBuffer(new TestBundle, overrideName = false), name = "SkidBuffer", split = false)
}
