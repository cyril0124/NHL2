package SimpleL2.Bundles

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import scala.collection.immutable.ListMap
import SimpleL2._
import SimpleL2.Configs._
import SimpleL2.chi.Resp

class TaskBundle(implicit p: Parameters) extends L2Bundle {
    val isCHIOpcode = Bool()
    val opcode      = UInt(5.W)                                           // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val param       = UInt(math.max(3, Resp.width).W)                     // if isCHIOpcode is true, param is equals to the resp field in CHI
    val channel     = UInt(L2Channel.width.W)
    val set         = UInt(setBits.W)
    val tag         = UInt(tagBits.W)
    val source      = UInt(math.max(tlBundleParams.sourceBits, 12).W)     // CHI RXRSP TxnID ==> 12.W, if isCHIOpcode is true, source is equals to the resp field in CHI
    val isPrefetch  = Bool()
    val corrupt     = Bool()
    val sink        = UInt(math.max(tlBundleParams.sinkBits, mshrBits).W) // also the alias name for mshrId
    val wayOH       = UInt(ways.W)
    val retToSrc    = Bool()
    val aliasOpt    = aliasBitsOpt.map(width => UInt(width.W))
    val isAliasTask = Bool()
    val isMshrTask  = Bool()

    val isReplayTask = Bool() // TODO: this signal is only for debugging, should be removed in the final version

    val readTempDs = Bool()
    val tempDsDest = UInt(DataDestination.width.W)

    val updateDir    = Bool()
    val newMetaEntry = new DirectoryMetaEntryNoTag

    val snpHitWriteBack = Bool() // for Snoop nested MSHR
    val snpGotDirty     = Bool() // for Snoop nested MSHR
    val snpHitReq       = Bool()
    val snpHitMshrId    = UInt(mshrBits.W)

    val getSnpNestedReq = if (mshrStallOnReqArb) None else Some(Bool())

    def resp = param             // alias to opcode, if isCHIOpcode is true
    def txnID = source           // alias to source, if isCHIOpcode is true
    def chiOpcode = opcode       // alias to opcode, if isCHIOpcode is true
    def mshrId = sink            // alias to sink, if isMshrTask is true
    def isReplTask = isAliasTask // alias to isAliasTask, if isMshrTask is true

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
    val last   = Bool()
}
