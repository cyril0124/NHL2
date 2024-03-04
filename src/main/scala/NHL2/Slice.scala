package NHL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import org.chipsalliance.cde.config.Parameters

class Slice(parentName: String = "Unknown")(implicit p: Parameters) extends NHL2Module {
    val io = IO(new Bundle {
        val in = Flipped(TLBundle(edgeIn.bundle))
    })

    io.in <> DontCare
    dontTouch(io)
}
