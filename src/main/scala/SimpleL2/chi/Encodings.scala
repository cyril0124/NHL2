package SimpleL2.chi

import chisel3._
import chisel3.util._

object LinkState {
    val width      = 2
    val STOP       = "b00".U(width.W)
    val ACTIVATE   = "b10".U(width.W)
    val RUN        = "b11".U(width.W)
    val DEACTIVATE = "b01".U(width.W)
}

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

    def setPassDirty(resp: UInt, passDirty: Bool) = {
        require(resp.getWidth == width)
        Cat(passDirty, resp(1, 0))
    }
}

object Order {
    val width = 2

    val None            = "b00".U(width.W)
    val RequestAccepted = "b01".U(width.W)
    val RequestOrder    = "b10".U(width.W)
    val OWO             = "b10".U(width.W) // Ordered Write Observation
    val EndpointOrder   = "b11".U(width.W)

    def isRequestOrder(order: UInt): Bool = order >= RequestOrder
}

class MemAttr extends Bundle {
    // The Allocate attribute is a an allocation hint.
    // It indicates the recommended allocation policy for a transaction.
    val allocate = Bool()
    // The Cacheable attribute indicates if a transaction must perform a cache lookup.
    val cacheable = Bool()
    // Device attribute indicates if the memory type is either Device or Normal.
    val device = Bool()
    // Early Write Acknowledge (EWA)
    // EWA indicates whether the write completion response for a transaction:
    // If true, comp is permitted to come from an intermediate point in the interconnect, such as a Home Node.
    // If false, comp must come from the final endpoint that a transaction is destined for.
    val ewa = Bool()
}

object MemAttr {
    def apply(allocate: Bool, cacheable: Bool, device: Bool, ewa: Bool): MemAttr = {
        val memAttr = Wire(new MemAttr)
        memAttr.allocate  := allocate
        memAttr.cacheable := cacheable
        memAttr.device    := device
        memAttr.ewa       := ewa
        memAttr
    }
    def apply(): MemAttr = apply(false.B, false.B, false.B, false.B)
}
