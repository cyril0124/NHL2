package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLPermissions._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.util.{BundleField, BundleFieldBase, BundleKeyBase, ControlKey}
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import SimpleL2.Bundles.CHIBundleParameters

case object AliasKey extends ControlKey[UInt]("alias")
case class AliasField(width: Int) extends BundleField[UInt](AliasKey, Output(UInt(width.W)), _ := 0.U(width.W))

case object L2ParamKey extends Field[L2Param](L2Param())

case class L2Param(
    ways: Int = 8,
    sets: Int = 256,
    blockBytes: Int = 64,
    beatBytes: Int = 32,
    dataBits: Int = 64 * 8, // 64 Byte
    addressBits: Int = 44,
    nrClients: Int = 2, // number of L1 DCache
    enableClockGate: Boolean = true,
    nrMSHR: Int = 16,
    nrTempDataEntry: Int = 16,
    nrRequestBufferEntry: Int = 4,
    nrSourceDTaskQueueEntry: Int = 4,
    rdQueueEntries: Int = 2,
    rxrspCreditMAX: Int = 4,
    rxsnpCreditMAX: Int = 4,
    rxdatCreditMAX: Int = 2,
    replacementPolicy: String = "plru"
) {
    require(dataBits == 64 * 8)
    require(nrMSHR == nrTempDataEntry)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
    require(nrClients >= 1)
}

trait HasL2Param {
    val p: Parameters
    val l2param = p(L2ParamKey)

    val ways        = l2param.ways
    val sets        = l2param.sets
    val wayBits     = log2Ceil(l2param.sets)
    val addressBits = l2param.addressBits
    val dataBits    = l2param.dataBits
    val beatBytes   = l2param.beatBytes
    val setBits     = log2Ceil(l2param.sets)
    val offsetBits  = log2Ceil(l2param.blockBytes)
    val tagBits     = l2param.addressBits - setBits - offsetBits
    val nrMSHR      = l2param.nrMSHR
    val nrClients   = l2param.nrClients

    val enableClockGate         = l2param.enableClockGate
    val nrTempDataEntry         = l2param.nrTempDataEntry
    val dataIdBits              = log2Ceil(nrTempDataEntry)
    val nrRequestBufferEntry    = l2param.nrRequestBufferEntry
    val nrSourceDTaskQueueEntry = l2param.nrSourceDTaskQueueEntry
    val rdQueueEntries          = l2param.rdQueueEntries

    val rxrspCreditMAX = l2param.rxrspCreditMAX
    val rxsnpCreditMAX = l2param.rxsnpCreditMAX
    val rxdatCreditMAX = l2param.rxdatCreditMAX

    val replacementPolicy = l2param.replacementPolicy

    val aliasBitsOpt = Some(2)

    // @formatter:off
    val tlBundleParams = TLBundleParameters(
        addressBits = addressBits,
        dataBits = beatBytes * 8,
        sourceBits = 5, // TODO: Parameterize it
        sinkBits = 7,
        sizeBits = 3,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
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

    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

    def getClientBitOH(sourceId: UInt): UInt = {
        if (nrClients == 1) {
            1.U(1.W)
        } else {

            /** 
              * Now we suppose that we have 2 clients and each of them owns a unique id range(0 ~ 15, 16~31).
              * So we can use the sourceId to determine which client the request belongs to.
              * The MSB<5> is used to identify the clientBitOH because 0xF == 0b1111 = 15.
              * TODO: Parameterize this
              */
            require(nrClients == 2)
            // widthCheck(sourceId, 5)
            assert(sourceId <= 31.U)

            /** 
              * "0b01" => L1 DCache Core0
              * "0b10" => L1 DCache Core1
              */
            Mux(sourceId(4), "b10".U, "b01".U)
        }
    }

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

    def needT(param: UInt): Bool = {
        param === NtoT || param === BtoT
    }

    def needData(opcode: UInt): Bool = {
        opcode === ReleaseData
    }
}
