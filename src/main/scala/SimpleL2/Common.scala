package SimpleL2

import chisel3._
import chisel3.util._

object RequestOwner {
    val width      = 2
    val Level1     = "b00".U(width.W)
    val Prefetcher = "b10".U(width.W)
}

object DataDestination {
    val width           = 4
    val TXDAT           = "b0001".U
    val SourceD         = "b0010".U
    val TempDataStorage = "b0100".U
    val DataStorage     = "b1000".U
}

object L2Channel extends ChiselEnum {
    // ChiselEnum value should be strictly increased!
    val TXREQ    = Value("b000".U) // CHI output channels
    val ChannelA = Value("b001".U) // TileLink output channels
    val ChannelB = Value("b010".U) // TileLink output channels
    val TXRSP    = Value("b011".U) // CHI output channels
    val ChannelC = Value("b100".U) // TileLink output channels
    val TXDAT    = Value("b101".U) // CHI output channels
}

object TLChannel {
    val width    = 3
    val ChannelA = "b001".U
    val ChannelB = "b010".U // from CHI RXSNP
    val ChannelC = "b100".U
}

object ReplayReson {
    // val NoSpaceForMSHR =
}
