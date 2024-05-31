package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLPermissions._
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import SimpleL2.Bundles.CHIBundleParameters

case object L2ParamKey extends Field[L2Param](L2Param())

case class L2Param(
    ways: Int = 8,
    sets: Int = 256,
    blockBytes: Int = 64,
    beatBytes: Int = 32,
    dataBits: Int = 64 * 8, // 64 Byte
    addressBits: Int = 44,
    enableClockGate: Boolean = true,
    nrMSHR: Int = 16,
    nrTmpDataEntry: Int = 16,
    nrRequestBufferEntry: Int = 4,
    rxrspCreditMAX: Int = 4,
    rxsnpCreditMAX: Int = 4,
    rxdatCreditMAX: Int = 2,
    replacementPolicy: String = "plru"
) {
    require(dataBits == 64 * 8)
    require(nrMSHR == nrTmpDataEntry)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
}

trait HasL2Param {
    val p: Parameters
    val l2param = p(L2ParamKey)

    val ways        = l2param.ways
    val sets        = l2param.sets
    val addressBits = l2param.addressBits
    val dataBits    = l2param.dataBits
    val beatBytes   = l2param.beatBytes
    val setBits     = log2Ceil(l2param.sets)
    val offsetBits  = log2Ceil(l2param.blockBytes)
    val tagBits     = l2param.addressBits - setBits - offsetBits

    val enableClockGate      = l2param.enableClockGate
    val nrTmpDataEntry       = l2param.nrTmpDataEntry
    val nrRequestBufferEntry = l2param.nrRequestBufferEntry

    val rxrspCreditMAX = l2param.rxrspCreditMAX
    val rxsnpCreditMAX = l2param.rxsnpCreditMAX
    val rxdatCreditMAX = l2param.rxdatCreditMAX

    val replacementPolicy = l2param.replacementPolicy

    val aliasBitsOpt = Some(4)

    // @formatter:off
    val tlBundleParams = TLBundleParameters(
        addressBits = addressBits,
        dataBits = beatBytes * 8,
        sourceBits = 7,
        sinkBits = 7,
        sizeBits = 3,
        echoFields = Nil,
        requestFields = Nil,
        responseFields = Nil,
        hasBCE = true
    )
    // @formatter:on

    // @formatter:off
    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = false
    )
    // @formatter:on

    val bankBits = 0 // TODO: multi-bank

    def parseAddress(x: UInt): (UInt, UInt, UInt) = {
        val offset = x
        val set    = offset >> (offsetBits + bankBits)
        val tag    = set >> setBits
        (tag(tagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }

    def fastArb[T <: Bundle](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }

    def arbTask[T <: Bundle](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new Arbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }

    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

    def needT(param: UInt): Bool = {
        param === NtoT || param === BtoT
    }
}
