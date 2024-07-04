package SimpleL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.Random
import Utils.GenerateVerilog
import chisel3.util.random.LFSR

class LFSRArbiter[T <: Data](val gen: T, val n: Int, val overrideName: Boolean = true, val lfsrWidth: Option[Int] = None) extends Module {
    override def desiredName = if (overrideName) {
        s"LFSRArbiter${n}_${gen.typeName}"
    } else {
        super.desiredName
    }

    val io = IO(new ArbiterIO(gen, n))

    io.chosen := (n - 1).asUInt

    val validVec     = VecInit(io.in.map(_.valid)).asUInt
    val hasValid     = validVec.orR
    val chosenOH     = WireInit(1.U(n.W))
    val lfsr         = LFSR(lfsrWidth.getOrElse(n), hasValid)
    val lfsrOH       = UIntToOH(Random(io.in.length, lfsr))
    val lfsrMatchVec = lfsrOH & validVec
    val lfsrHasMatch = lfsrMatchVec.orR
    assert(PopCount(lfsrMatchVec) <= 1.U, "lfsrMatchVec: 0b%b validVec: 0b%b lfsrOH: 0b%b", PopCount(lfsrMatchVec), validVec, lfsrOH)
    assert(PopCount(chosenOH) <= 1.U, "chosenOH: 0b%b", PopCount(chosenOH))

    io.in.zip(chosenOH.asBools).foreach { case (in, grant) =>
        in.ready := io.out.ready & grant
    }

    chosenOH     := Mux(lfsrHasMatch, lfsrMatchVec, PriorityEncoderOH(validVec))
    io.out.valid := hasValid
    io.out.bits  := Mux1H(chosenOH, io.in.map(_.bits))
    io.chosen    := OHToUInt(chosenOH)
}

object LFSRArbiter extends App {

    class TestBundle extends Bundle {
        val data = UInt(8.W)
    }

    GenerateVerilog(args, () => new LFSRArbiter(new TestBundle, 4, overrideName = false), name = "LFSRArbiter", split = false)
}
