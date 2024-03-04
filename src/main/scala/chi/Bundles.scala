package chi

import chisel3._
import chisel3.util._
import scala.collection.immutable.ListMap
import freechips.rocketchip.util.CreditedIO

abstract class CHIBundleBase(val params: CHIBundleParameters) extends Bundle {
    //  Common fields
    val pend = Bool()

    val QoS      = UInt(4.W)
    val tgtID    = UInt(params.tgtIDWidth.W)
    val srcID    = UInt(params.srcIDWidth.W)
    val txnID    = UInt(12.W)
    val opcode   = UInt(7.W)
    val pCrdType = UInt(4.W)
}

sealed trait CHIChannel {
    val channelName: String
}

//
// REQ channel
//
class CHIBundleREQ(params: CHIBundleParameters) extends CHIBundleBase(params) with CHIChannel {
    val channelName: String = "REQ channel"

    val size       = UInt(3.W)
    val addr       = UInt(params.reqAddrWidth.W)
    val expCompAck = Bool()
}

//
// RSP channel
//
class CHIBundleRSP(params: CHIBundleParameters) extends CHIBundleBase(params) with CHIChannel {
    val channelName: String = "RSP channel"

    val respErr = UInt(2.W)
    val resp    = UInt(3.W)
    val cBusy   = UInt(3.W)
}

//
// DAT channel
//
class CHIBundleDAT(params: CHIBundleParameters) extends CHIBundleBase(params) with CHIChannel {
    val channelName: String = "DAT channel"

    val homeNID   = UInt(params.homeNIDWidth.W)
    val respErr   = UInt(2.W)
    val dbID      = UInt(12.W)
    val cBusy     = UInt(3.W)
    val resp      = UInt(3.W)
    val dataID    = UInt(2.W)
    val be        = UInt((params.dataWidth / 8).W)
    val data      = UInt(params.dataWidth.W)
    val dataCheck = UInt(if (params.enableDataCheck) (params.dataWidth / 8).W else 0.W)
    val poison    = UInt(if (params.enableDataCheck) (params.dataWidth / 64).W else 0.W)
}

//
// SNP channel
//
class CHIBundleSNP(params: CHIBundleParameters) extends CHIBundleBase(params) with CHIChannel {
    val channelName: String = "SNP channel"

    val addr = UInt(params.snpAddrWidth.W)
}

class CHIBundle(val params: CHIBundleParameters) extends Record {
    val req: CreditedIO[CHIBundleREQ] = CreditedIO(new CHIBundleREQ(params))
    val rsp: CreditedIO[CHIBundleRSP] = Flipped(CreditedIO(new CHIBundleRSP(params)))
    val dat: CreditedIO[CHIBundleDAT] = CreditedIO(new CHIBundleDAT(params))
    val snp: CreditedIO[CHIBundleSNP] = Flipped(CreditedIO(new CHIBundleSNP(params)))

    val elements = ListMap("req" -> req, "rsp" -> rsp, "dat" -> dat, "snp" -> snp)
}

object CHIBundle {
    def apply(params: CHIBundleParameters) = new CHIBundle(params)
}
