package Utils

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram._

// divide SRAM into nrBank banks
// use lower-bits of setIdx to select bank
// allow parallel accesses to different banks
class BankedSRAM[T <: Data](
    gen: T,
    sets: Int,
    ways: Int,
    nrBank: Int = 1,
    shouldReset: Boolean = false,
    holdRead: Boolean = false,
    singlePort: Boolean = false,
    bypassWrite: Boolean = false,
    hasClkGate: Boolean = true,
    clk_div_by_2: Boolean = false
)(implicit p: Parameters)
    extends Module {
    val io = IO(new Bundle() {
        val r = Flipped(new SRAMReadBus(gen, sets, ways))
        val w = Flipped(new SRAMWriteBus(gen, sets, ways))
    })

    val innerSet     = sets / nrBank
    val bankBits     = log2Ceil(nrBank)
    val innerSetBits = log2Up(sets) - bankBits
    val r_setIdx     = io.r.req.bits.setIdx.head(innerSetBits)
    val r_bankSel    = if (nrBank == 1) 0.U else io.r.req.bits.setIdx(bankBits - 1, 0)
    val w_setIdx     = io.w.req.bits.setIdx.head(innerSetBits)
    val w_bankSel    = if (nrBank == 1) 0.U else io.w.req.bits.setIdx(bankBits - 1, 0)

    val banks = (0 until nrBank).map { i =>
        val ren = if (nrBank == 1) true.B else i.U === r_bankSel
        val wen = if (nrBank == 1) true.B else i.U === w_bankSel
        val sram = Module(
            new SRAMTemplate(
                gen,
                innerSet,
                ways,
                shouldReset = shouldReset,
                holdRead = holdRead,
                singlePort = true,
                bypassWrite = bypassWrite,
                hasClkGate = hasClkGate,
                clk_div_by_2 = clk_div_by_2
            )
        )
        sram.io.r.req.valid := io.r.req.valid && ren
        sram.io.r.req.bits.apply(r_setIdx)
        sram.io.w.req.valid := io.w.req.valid && wen
        sram.io.w.req.bits.apply(io.w.req.bits.data, w_setIdx, io.w.req.bits.waymask.getOrElse(1.U))
        sram
    }

    val ren_vec_0 = VecInit(banks.map(_.io.r.req.fire))
    val ren_vec_1 = RegNext(ren_vec_0, 0.U.asTypeOf(ren_vec_0))
    val ren_vec = if (clk_div_by_2) {
        RegNext(ren_vec_1, 0.U.asTypeOf(ren_vec_0))
    } else ren_vec_1

    // only one read/write
    assert({ PopCount(ren_vec) <= 1.U })
    assert({ PopCount(Cat(banks.map(_.io.r.req.fire))) <= 1.U })

    // block read when there is write request of the same bank
    io.r.req.ready := Cat(banks.map(s => s.io.r.req.ready && !s.io.w.req.valid).reverse)(r_bankSel)
    // TODO: r.ready low when w.valid is also guaranteed in SRAMTemplate
    io.r.resp.data := Mux1H(ren_vec, banks.map(_.io.r.resp.data))

    io.w.req.ready := Cat(banks.map(_.io.w.req.ready).reverse)(w_bankSel)
}