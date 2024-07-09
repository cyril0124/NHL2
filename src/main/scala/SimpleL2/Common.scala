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
    val TXDAT           = "b0001".U(width.W)
    val SourceD         = "b0010".U(width.W)
    val TempDataStorage = "b0100".U(width.W)
    val DataStorage     = "b1000".U(width.W)
}

object L2Channel {
    val width    = 3
    val TXREQ    = "b000".U(width.W) // CHI output channels
    val ChannelA = "b001".U(width.W) // TileLink output channels
    val ChannelB = "b010".U(width.W) // TileLink output channels
    val TXRSP    = "b011".U(width.W) // CHI output channels
    val ChannelC = "b100".U(width.W) // TileLink output channels
    val TXDAT    = "b101".U(width.W) // CHI output channels
}

object CHIChannel {
    val width    = 3
    val TXREQ    = "b000".U(width.W) // CHI output channels
    val ChannelA = "b001".U(width.W) // TileLink output channels
    val ChannelB = "b010".U(width.W) // TileLink output channels
    val TXRSP    = "b011".U(width.W) // CHI output channels
    val ChannelC = "b100".U(width.W) // TileLink output channels
    val TXDAT    = "b101".U(width.W) // CHI output channels
}

object TLChannel {
    val width    = 3
    val ChannelA = "b001".U(width.W)
    val ChannelB = "b010".U(width.W) // from CHI RXSNP
    val ChannelC = "b100".U(width.W)
}

object ReplayReson {
    // val NoSpaceForMSHR =
}
