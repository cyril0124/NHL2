package SimpleL2.chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import scala.collection.immutable.ListMap
import SimpleL2._
import SimpleL2.Configs._

case class CHIBundleParameters(
    nodeIdBits: Int,
    addressBits: Int,
    dataBits: Int,
    dataCheck: Boolean,
    issue: String = "G"
// TODO: has snoop
) {
    val txnIdBits: Int = issue match {
        case "B"       => 8
        case "E" | "G" => 12
        case _         => 12
    }
    val dbIdBits: Int = issue match {
        case "B"       => 8
        case "E" | "G" => 12
        case _         => 12
    }
    val reqOpcodeBits: Int = issue match {
        case "B"       => 6
        case "E" | "G" => 7
        case _         => 7
    }
    val rspOpcodeBits: Int = issue match {
        case "B"       => 4
        case "E" | "G" => 5
        case _         => 5
    }
    val datOpcodeBits: Int = issue match {
        case "B"       => 3
        case "E" | "G" => 4
        case _         => 4
    }
    require(nodeIdBits >= 7 && nodeIdBits <= 11)
    require(addressBits >= 44 && addressBits <= 52)
    require(isPow2(dataBits))
    require(dataBits == 128 || dataBits == 256 || dataBits == 512)
}

object CHIBundleParameters {
    def apply(
        nodeIdBits: Int = 7,
        addressBits: Int = 48,
        dataBits: Int = 256,
        dataCheck: Boolean = false,
        issue: String = "G"
    ): CHIBundleParameters = new CHIBundleParameters(
        nodeIdBits = nodeIdBits,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = dataCheck,
        issue = issue
    )
}

class CHIBundleREQ(params: CHIBundleParameters) extends Bundle {
    val channelName = "'REQ' channel"

    val rsvdc         = UInt(4.W)
    val traceTag      = Bool()
    val expCompAck    = Bool()
    val snoopMe       = Bool()
    val lpID          = UInt(5.W)
    val snpAttr       = UInt(1.W)
    val memAttr       = new MemAttr
    val pCrdType      = UInt(4.W)
    val order         = UInt(2.W)
    val allowRetry    = Bool()
    val likelyshared  = Bool()
    val ns            = Bool()                    // TODO: not use?
    val addr          = UInt(params.addressBits.W)
    val size          = UInt(3.W)
    val opcode        = UInt(params.reqOpcodeBits.W)
    val returnTxnID   = UInt(8.W)
    val stashNIDValid = Bool()
    val returnNID     = UInt(params.nodeIdBits.W) // TODO: not use?
    val txnID         = UInt(params.txnIdBits.W)
    val srcID         = UInt(params.nodeIdBits.W)
    val tgtID         = UInt(params.nodeIdBits.W) // TODO: not use?
    val qos           = UInt(4.W)                 // TODO: not use?
}

class CHIBundleRSP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'RSP' channel"

    val traceTag = Bool()
    val pCrdType = UInt(4.W)
    val dbID     = UInt(params.dbIdBits.W)
    val fwdState = UInt(3.W)                 // Used for DCT
    val resp     = UInt(3.W)
    val respErr  = UInt(2.W)
    val opcode   = UInt(params.rspOpcodeBits.W)
    val txnID    = UInt(params.txnIdBits.W)
    val srcID    = UInt(params.nodeIdBits.W)
    val tgtID    = UInt(params.nodeIdBits.W) // TODO: not use?
    val qos      = UInt(4.W)                 // TODO: not use?
}

class CHIBundleSNP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'SNP' channel"

    val traceTag    = Bool()
    val retToSrc    = Bool()
    val doNotGoToSD = Bool()
    val ns          = Bool()
    val addr        = UInt((params.addressBits - 3).W)
    val opcode      = UInt(5.W)
    val fwdTxnID    = UInt(params.txnIdBits.W)  // Used for DCT
    val fwdNID      = UInt(params.nodeIdBits.W) // Used for DCT
    val txnID       = UInt(params.txnIdBits.W)
    val srcID       = UInt(params.nodeIdBits.W)
    val qos         = UInt(4.W)                 // TODO: not use?
}

class CHIBundleDAT(params: CHIBundleParameters) extends Bundle {
    val channelName = "'DAT' channel"

    val poison    = if (params.dataCheck) Some(UInt((params.dataBits / 64).W)) else None
    val dataCheck = if (params.dataCheck) Some(UInt((params.dataBits / 8).W)) else None
    val data      = UInt(params.dataBits.W)
    val be        = UInt((params.dataBits / 8).W)
    val rsvdc     = UInt(4.W)
    val traceTag  = Bool()
    val dataID    = UInt(2.W)
    val ccID      = UInt(2.W)                 // TODO: not use?
    val dbID      = UInt(params.dbIdBits.W)
    val fwdState  = UInt(3.W)                 // Used for DCT
    val resp      = UInt(3.W)
    val respErr   = UInt(2.W)
    val opcode    = UInt(params.datOpcodeBits.W)
    val homeNID   = UInt(params.nodeIdBits.W) // Used for DCT
    val txnID     = UInt(params.txnIdBits.W)
    val srcID     = UInt(params.nodeIdBits.W)
    val tgtID     = UInt(params.nodeIdBits.W) // TODO: not use?
    val qos       = UInt(4.W)                 // TODO: not use?
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
    val txreq: CHIChannelIO[CHIBundleREQ] = CHIChannelIO(new CHIBundleREQ(params), aggregateIO)
    val txdat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val txrsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)

    val rxrsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))
    val rxdat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val rxsnp: CHIChannelIO[CHIBundleSNP] = Flipped(CHIChannelIO(new CHIBundleSNP(params), aggregateIO))

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
    val txreq: CHIChannelIO[CHIBundleREQ] = Flipped(CHIChannelIO(new CHIBundleREQ(params), aggregateIO))
    val txdat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val txrsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))

    val rxrsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)
    val rxdat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val rxsnp: CHIChannelIO[CHIBundleSNP] = CHIChannelIO(new CHIBundleSNP(params), aggregateIO)

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

class CHIBundleDecoupled(params: CHIBundleParameters) extends Bundle {
    val txreq = Decoupled(new CHIBundleREQ(params))
    val txdat = Decoupled(new CHIBundleDAT(params))
    val txrsp = Decoupled(new CHIBundleRSP(params))

    val rxrsp = Flipped(Decoupled(new CHIBundleRSP(params)))
    val rxdat = Flipped(Decoupled(new CHIBundleDAT(params)))
    val rxsnp = Flipped(Decoupled(new CHIBundleSNP(params)))
}

object CHIBundleDownstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleDownstream = new CHIBundleDownstream(params, aggregateIO)
}

object CHIBundleUpstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleUpstream = new CHIBundleUpstream(params, aggregateIO)
}

object CHIBundleDecoupled {
    def apply(params: CHIBundleParameters): CHIBundleDecoupled = new CHIBundleDecoupled(params)
}

class CHILinkCtrlIO extends Bundle {
    val txsactive = Output(Bool())
    val rxsactive = Input(Bool())

    val txactivereq = Output(Bool())
    val txactiveack = Input(Bool())

    val rxactivereq = Input(Bool())
    val rxactiveack = Output(Bool())
}

class CHIRespBundle(params: CHIBundleParameters)(implicit p: Parameters) extends L2Bundle {
    val last      = Bool()
    val srcID     = UInt(chiBundleParams.nodeIdBits.W)
    val homeNID   = UInt(chiBundleParams.nodeIdBits.W)
    val txnID     = UInt(chiBundleParams.txnIdBits.W)
    val dbID      = UInt(chiBundleParams.dbIdBits.W)
    val chiOpcode = UInt(7.W)
    val respErr   = UInt(2.W)
    val resp      = UInt(3.W)
    val pCrdType  = UInt(4.W)
}
