package SimpleL2.chi

import chisel3._
import chisel3.util._
import freechips.rocketchip.regmapper.RegField.w

object RespErr {
    val width = 2

    val NormalOkay    = "b00".U(width.W)
    val ExclusiveOkay = "b01".U(width.W)
    val DataError     = "b10".U(width.W)
    val NonDataError  = "b11".U(width.W)
}

object Resp {
    val width = 3

    val I     = "b000".U(width.W)
    val SC    = "b001".U(width.W)
    val UC    = "b010".U(width.W)
    val UD    = "b010".U(width.W) // for Snoop responses
    val SD    = "b011".U(width.W) // for Snoop responses
    val I_PD  = "b100".U(width.W) // for Snoop responses
    val SC_PD = "b101".U(width.W) // for Snoop responses
    val UC_PD = "b110".U(width.W) // for Snoop responses
    val UD_PD = "b110".U(width.W)
    val SD_PD = "b111".U(width.W)
}
