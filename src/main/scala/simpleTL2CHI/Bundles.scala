package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class TaskBundle(implicit p: Parameters) extends SimpleTL2CHIBundle {
    val address = UInt(addressBits.W)
    val opcode  = UInt(3.W)
    val param   = UInt(3.W)
    val source  = UInt(tlBundleParams.sourceBits.W)
    val data    = UInt(dataBits.W)
    val mask    = UInt((dataBits / 8).W)
}
