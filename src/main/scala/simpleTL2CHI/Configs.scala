package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.FastArbiter
import SimpleL2.chi._

case object EdgeInKey extends Field[TLEdgeIn]

case object SimpleTL2CHIParamKey extends Field[SimpleTL2CHIParam](SimpleTL2CHIParam())

case class SimpleTL2CHIParam(
    name: String = "TL2CHI",
    dataBits: Int = 8 * 8, // 64 Byte
    chiDataBits: Int = 256,
    addressBits: Int = 44,
    nrMachine: Int = 4,
    chiBundleParams: Option[CHIBundleParameters] = None,
    useDiplomacy: Boolean = false
) {}

trait HasSimpleTL2CHIParameters {
    val p: Parameters
    val params = p(SimpleTL2CHIParamKey)

    val dataBits    = params.dataBits
    val chiDataBits = params.chiDataBits
    val addressBits = params.addressBits
    val nrMachine   = params.nrMachine

    val deadlockThreshold = 10000

    lazy val edgeIn = p(EdgeInKey)

    // @formatter:off
    val _tlBundleParams = TLBundleParameters(
        addressBits = addressBits,
        dataBits = dataBits,
        sourceBits = 6, // TODO: Parameterize it
        sinkBits = 7,
        sizeBits = 3,
        echoFields = Nil,
        requestFields = Nil,
        responseFields = Nil,
        hasBCE = false
    )
    // @formatter:on

    def tlBundleParams: TLBundleParameters = if (params.useDiplomacy) {
        edgeIn.bundle
    } else {
        _tlBundleParams
    }

    val chiBundleParams = if (params.chiBundleParams.isDefined) {
        params.chiBundleParams.get
    } else {
        CHIBundleParameters(
            nodeIdBits = 7,
            addressBits = addressBits,
            dataBits = chiDataBits,
            dataCheck = false,
            issue = "G"
        )
    }

    def fastArb[T <: Data](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }
}
