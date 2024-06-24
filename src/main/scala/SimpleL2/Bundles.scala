package SimpleL2.Bundles

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import scala.collection.immutable.ListMap
import SimpleL2._
import SimpleL2.Configs._

object RequestOwner {
    val width      = 3
    val Level1     = "b001".U
    val CMO        = "b010".U
    val Prefetcher = "b011".U
    val Snoop      = "b100".U
    val MSHR       = "b101".U
}

object TLChannel {
    val width    = 3
    val ChannelA = "b001".U
    val ChannelB = "b010".U // from CHI RXSNP
    val ChannelC = "b100".U
}

object L2Channel extends ChiselEnum {
    // ChiselEnum should be strictly increase!
    val TXREQ    = Value("b000".U) // CHI output channels
    val ChannelA = Value("b001".U) // TileLink output channels
    val ChannelB = Value("b010".U) // TileLink output channels
    val TXRSP    = Value("b011".U) // CHI output channels
    val ChannelC = Value("b100".U) // TileLink output channels
    val TXDAT    = Value("b101".U) // CHI output channels
}

class MainPipeRequest(implicit p: Parameters) extends L2Bundle {
    val owner     = UInt(RequestOwner.width.W)
    val opcode    = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val channel   = UInt(TLChannel.width.W)
    val source    = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val address   = UInt(addressBits.W)
    val tmpDataID = UInt(log2Ceil(nrTempDataEntry).W)

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === TLChannel.ChannelB
}

class TaskBundle(implicit p: Parameters) extends L2Bundle {
    val owner      = UInt(RequestOwner.width.W)
    val opcode     = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val param      = UInt(3.W)
    val channel    = L2Channel()
    val set        = UInt(setBits.W)
    val tag        = UInt(tagBits.W)
    val source     = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val isPrefetch = Bool()
    val tmpDataID  = UInt(log2Ceil(nrTempDataEntry).W)
    val corrupt    = Bool()
    val sink       = UInt((tlBundleParams.sinkBits).W)
    val wayOH      = UInt(ways.W)
    val aliasOpt   = aliasBitsOpt.map(width => UInt(width.W))
    val isMshrTask = Bool()

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === L2Channel.ChannelB && !isMshrTask
    def isChannelA = channel.asUInt(0) && !isMshrTask
    def isChannelB = channel.asUInt(1) && !isMshrTask
    def isChannelC = channel.asUInt(2) && !isMshrTask
    def isTXREQ = channel === L2Channel.TXREQ && !isMshrTask
    def isTXRSP = channel === L2Channel.TXRSP && !isMshrTask
    def isTXDAT = channel === L2Channel.TXDAT && !isMshrTask
}

object ReplayReson {
    // val NoSpaceForMSHR =
}

class CreditIO[T <: Data](gen: T) extends Bundle {
    val crdv  = Input(Bool())
    val valid = Output(Bool())
    val bits  = Output(gen)
}

object CreditIO {
    def apply[T <: Data](gen: T): CreditIO[T] = new CreditIO(gen)
}
