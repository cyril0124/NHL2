package SimpleL2.Bundles

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import scala.collection.immutable.ListMap
import SimpleL2._
import SimpleL2.Configs._

case class CHIBundleParameters(
    nodeIdBits: Int,
    addressBits: Int,
    dataBits: Int,
    dataCheck: Boolean
// TODO: has snoop
) {
    require(nodeIdBits >= 7 && nodeIdBits <= 11)
    require(addressBits >= 44 && addressBits <= 52)
    require(isPow2(dataBits))
    require(dataBits == 128 || dataBits == 256 || dataBits == 512)
}

object CHIBundleParameters {
    def apply(
        nodeIdBits: Int = 7,
        addressBits: Int = 44,
        dataBits: Int = 256,
        dataCheck: Boolean = false
    ): CHIBundleParameters = new CHIBundleParameters(
        nodeIdBits = nodeIdBits,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = dataCheck
    )
}

class CHIBundleREQ(params: CHIBundleParameters) extends Bundle {
    val channelName = "'REQ' channel"

    val qos          = UInt(4.W)
    val tgtID        = UInt(params.nodeIdBits.W)
    val srcID        = UInt(params.nodeIdBits.W)
    val txnID        = UInt(12.W)
    val returnNID    = UInt(params.nodeIdBits.W)
    val opcode       = UInt(7.W)
    val size         = UInt(3.W)
    val addr         = UInt(params.addressBits.W)
    val ns           = Bool()
    val nse          = Bool()
    val likelyShared = Bool()
    val allowRetry   = Bool()
    val order        = UInt(2.W)
    val pCrdType     = UInt(4.W)
    val memAttr      = UInt(4.W)
    val snpAttr      = UInt(1.W)
    val cah          = Bool()
    // val excl         = cah
    // val snoopMe      = cah
    val expCompAck = Bool()
}

class CHIBundleRSP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'RSP' channel"

    val qos      = UInt(4.W)
    val tgtID    = UInt(params.nodeIdBits.W)
    val srcID    = UInt(params.nodeIdBits.W)
    val txnID    = UInt(12.W)
    val opcode   = UInt(5.W)
    val respErr  = UInt(2.W)
    val resp     = UInt(3.W)
    val cBusy    = UInt(3.W)
    val dbID     = UInt(12.W)
    val pCrdType = UInt(4.W)
}

class CHIBundleSNP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'SNP' channel"

    val qos         = UInt(4.W)
    val srcID       = UInt(params.nodeIdBits.W)
    val txnID       = UInt(12.W)
    val fwdNID      = UInt(params.nodeIdBits.W)
    val fwdTxnID    = UInt(12.W)
    val opcode      = UInt(5.W)
    val addr        = UInt((params.addressBits - 3).W)
    val ns          = Bool()
    val nse         = Bool()
    val doNotGoToSD = Bool()
    val retToSrc    = Bool()
}

class CHIBundleDAT(params: CHIBundleParameters) extends Bundle {
    val channelName = "'DAT' channel"

    val qos       = UInt(4.W)
    val tgtID     = UInt(params.nodeIdBits.W)
    val srcID     = UInt(params.nodeIdBits.W)
    val txnID     = UInt(12.W)
    val homeNID   = UInt(params.nodeIdBits.W)
    val opcode    = UInt(4.W)
    val respErr   = UInt(2.W)
    val resp      = UInt(3.W)
    val cBusy     = UInt(3.W)
    val dbID      = UInt(12.W)
    val ccID      = UInt(2.W)
    val dataID    = UInt(2.W)
    val cah       = Bool()
    val be        = UInt((params.dataBits / 8).W)
    val data      = UInt(params.dataBits.W)
    val dataCheck = if (params.dataCheck) Some(UInt((params.dataBits / 8).W)) else None
    val poison    = if (params.dataCheck) Some(UInt((params.dataBits / 64).W)) else None
}

class CHIChannelIO[T <: Data](gen: T, aggregateIO: Boolean = false) extends Bundle {
    val flitpend = Output(Bool())
    val flitv    = Output(Bool())
    val flit     = if (aggregateIO) Output(UInt(gen.getWidth.W)) else Output(gen)
    val lcrdv    = Input(Bool())
}

object CHIChannelIO {
    def apply[T <: Data](gen: T, aggregateIO: Boolean = false): CHIChannelIO[T] = new CHIChannelIO(gen, aggregateIO)
}

class CHIBundleDownstream(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Record {
    val txreq: CHIChannelIO[UInt] = CHIChannelIO(UInt(82.W), aggregateIO)
    val txdat: CHIChannelIO[UInt] = CHIChannelIO(UInt(314.W), aggregateIO)
    val txrsp: CHIChannelIO[UInt] = CHIChannelIO(UInt(18.W), aggregateIO)

    val rxrsp: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(29.W), aggregateIO))
    val rxdat: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(292.W), aggregateIO))
    val rxsnp: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(64.W), aggregateIO))

    // @formatter:off
    val elements = ListMap(
        "txreq" -> txreq,
        "txdat" -> txdat,
        "txrsp" -> txrsp,
        "rxrsp" -> rxrsp,
        "rxdat" -> rxdat,
        "rxsnp" -> rxsnp
    )
    // @formatter:on
}

class CHIBundleUpstream(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Record {
    val txreq: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(82.W), aggregateIO))
    val txdat: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(314.W), aggregateIO))
    val txrsp: CHIChannelIO[UInt] = Flipped(CHIChannelIO(UInt(18.W), aggregateIO))

    val rxrsp: CHIChannelIO[UInt] = CHIChannelIO(UInt(29.W), aggregateIO)
    val rxdat: CHIChannelIO[UInt] = CHIChannelIO(UInt(292.W), aggregateIO)
    val rxsnp: CHIChannelIO[UInt] = CHIChannelIO(UInt(64.W), aggregateIO)

    // @formatter:off
    val elements = ListMap(
        "txreq" -> txreq,
        "txdat" -> txdat,
        "txrsp" -> txrsp,
        "rxrsp" -> rxrsp,
        "rxdat" -> rxdat,
        "rxsnp" -> rxsnp
    )
    // @formatter:on
}

object CHIBundleDownstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleDownstream = new CHIBundleDownstream(params, aggregateIO)
}

object CHIBundleUpstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleUpstream = new CHIBundleUpstream(params, aggregateIO)
}

class CHILinkCtrlIO extends Bundle {
    val txsactive = Output(Bool())
    val rxsactive = Input(Bool())

    val txactivereq = Output(Bool())
    val txactiveack = Input(Bool())

    val rxactivereq = Input(Bool())
    val rxactiveack = Output(Bool())
}

object RequestOwner {
    val width      = 3
    val Level1     = "b001".U
    val CMO        = "b010".U
    val Prefetcher = "b011".U
    val Snoop      = "b100".U
}

object TLChannel {
    val width    = 3
    val ChannelA = "b001".U
    val ChannelB = "b010".U // from CHI RXSNP
    val ChannelC = "b100".U
}

// trait HasSetAndTag {
//     val set = UInt(L2CacheConfig.setBits.W)
//     val tag = UInt(L2CacheConfig.tagBits.W)
// }

class MainPipeRequest(implicit p: Parameters) extends L2Bundle {
    val owner     = UInt(RequestOwner.width.W)
    val opcode    = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val channel   = UInt(TLChannel.width.W)
    val source    = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val address   = UInt(addressBits.W)
    val tmpDataID = UInt(log2Ceil(nrTmpDataEntry).W)

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === TLChannel.ChannelB
}

class TaskBundle(implicit p: Parameters) extends L2Bundle {
    val opcode     = UInt(5.W)                                       // TL Opcode ==> 3.W    CHI RXRSP Opcode ==> 5.W
    val channel    = UInt(TLChannel.width.W)
    val set        = UInt(setBits.W)
    val tag        = UInt(tagBits.W)
    val source     = UInt(math.max(tlBundleParams.sourceBits, 12).W) // CHI RXRSP TxnID ==> 12.W
    val isPrefetch = Bool()
    val tmpDataID  = UInt(log2Ceil(nrTmpDataEntry).W)

    def txnID = source     // alias to source
    def chiOpcode = opcode // alias to opcode
    def isSnoop = channel === TLChannel.ChannelB
}

// abstract class TaskBundle extends Bundle with HasSetAndTag

// class TaskSinkA extends TaskBundle {
//     val params = L2CacheConfig.tlBundleParams
//     val opcode = UInt(3.W)

//     val source     = UInt(params.sourceBits.W)
//     val isPrefetch = Bool()
//     // TODO: alias
// }

// class TaskSinkC extends TaskBundle {
//     val params    = L2CacheConfig.tlBundleParams
//     val opcode    = UInt(3.W)
//     val source    = UInt(params.sourceBits.W)
//     val tmpDataID = UInt(log2Ceil(L2CacheConfig.nrTmpDataEntry).W)
// }

// class TaskSnoop extends TaskBundle {
//     val params = L2CacheConfig.chiBundleParams
//     val opcode = UInt(5.W)
//     val txnID  = UInt(12.W)
// }

// class TaskCMO extends TaskBundle {
//     // TODO:
// }

// class TaskReplay extends TaskBundle {
//     // TODO:
// }

// class TaskMSHR extends TaskBundle {
//     val tmpDataID = UInt(log2Ceil(L2CacheConfig.nrTmpDataEntry).W)
// }

class DirRead(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}
