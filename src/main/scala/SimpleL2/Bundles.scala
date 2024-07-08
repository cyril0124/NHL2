package SimpleL2.Bundles

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import scala.collection.immutable.ListMap
import SimpleL2._
import SimpleL2.Configs._
import scala.annotation.meta.param

class MainPipeRequest(implicit p: Parameters) extends L2Bundle {
    val owner   = UInt(RequestOwner.width.W)
    val opcode  = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val channel = UInt(TLChannel.width.W)
    val source  = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val address = UInt(addressBits.W)
    val dataId  = UInt(log2Ceil(nrTempDataEntry).W)

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === TLChannel.ChannelB
}

class TaskBundle(implicit p: Parameters) extends L2Bundle {
    val owner       = UInt(RequestOwner.width.W)
    val isCHIOpcode = Bool()
    val opcode      = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val param       = UInt(3.W)
    val channel     = L2Channel()
    val set         = UInt(setBits.W)
    val tag         = UInt(tagBits.W)
    val source      = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val isPrefetch  = Bool()
    val corrupt     = Bool()
    val sink        = UInt((tlBundleParams.sinkBits).W)
    val wayOH       = UInt(ways.W)
    val aliasOpt    = aliasBitsOpt.map(width => UInt(width.W))
    val isAliasTask = Bool()
    val isMshrTask  = Bool()
    val isReplTask  = Bool()

    val readTempDs = Bool()
    val tempDsDest = UInt(DataDestination.width.W)

    val updateDir    = Bool()
    val newMetaEntry = new DirectoryMetaEntryNoTag

    def mshrId = sink
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

class CreditIO[T <: Data](gen: T) extends Bundle {
    val crdv  = Input(Bool())
    val valid = Output(Bool())
    val bits  = Output(gen)
}

object CreditIO {
    def apply[T <: Data](gen: T): CreditIO[T] = new CreditIO(gen)
}

class TLRespBundle(params: TLBundleParameters)(implicit p: Parameters) extends L2Bundle {
    val opcode = UInt(3.W)
    val param  = UInt(3.W)
    val source = UInt(params.sourceBits.W)
    val sink   = UInt(params.sinkBits.W)
    val set    = UInt(setBits.W)
    val tag    = UInt(tagBits.W)
    val dataId = UInt(dataIdBits.W)
    val last   = Bool()
}
