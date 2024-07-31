package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLPermissions._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.util.{BundleField, BundleFieldBase, BundleKeyBase, ControlKey}
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import SimpleL2._
import SimpleL2.chi._

case object AliasKey extends ControlKey[UInt]("alias")
case class AliasField(width: Int) extends BundleField[UInt](AliasKey, Output(UInt(width.W)), _ := 0.U(width.W))

case object L2ParamKey extends Field[L2Param](L2Param())

case object EdgeInKey extends Field[TLEdgeIn]

case class L2Param(
    name: String = "L2",
    ways: Int = 4,
    sets: Int = 256,
    nrSlice: Int = 1,
    blockBytes: Int = 64,
    beatBytes: Int = 32,
    dataBits: Int = 64 * 8, // 64 Byte
    addressBits: Int = 44,
    nrClients: Int = 2, // number of L1 DCache
    enableClockGate: Boolean = true,
    nrMSHR: Int = 16,
    nrExtraSinkId: Int = 16, // extra sink ids for hit Acquire requests which need to wait GrantAck
    nrReplayEntry: Int = 8,
    nrNonDataSourceDEntry: Int = 4,
    nrTXRSPEntry: Int = 4,
    metaSramBank: Int = 4,
    nrTempDataEntry: Int = 16,
    nrReqBufEntry: Int = 4,
    rxrspCreditMAX: Int = 2,
    rxsnpCreditMAX: Int = 2,
    rxdatCreditMAX: Int = 2,
    replacementPolicy: String = "random", // TODO: plru
    useDiplomacy: Boolean = false         // If use diplomacy, EdgeInKey should be passed in
) {
    require(dataBits == 64 * 8)
    require(nrSlice >= 1)
    require(nrMSHR == nrTempDataEntry)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
    require(nrClients >= 1)
}

trait HasL2Param {
    val p: Parameters
    val l2param = p(L2ParamKey)

    val ways          = l2param.ways
    val sets          = l2param.sets
    val wayBits       = log2Ceil(l2param.ways)
    val addressBits   = l2param.addressBits
    val dataBits      = l2param.dataBits
    val beatBytes     = l2param.beatBytes
    val setBits       = log2Ceil(l2param.sets)
    val offsetBits    = log2Ceil(l2param.blockBytes)
    val blockBytes    = l2param.blockBytes
    val nrSlice       = l2param.nrSlice
    val bankBits      = log2Ceil(nrSlice)
    val sliceBits     = bankBits
    val tagBits       = l2param.addressBits - setBits - bankBits - offsetBits
    val nrMSHR        = l2param.nrMSHR
    val nrExtraSinkId = l2param.nrExtraSinkId
    val nrGrantMap    = nrMSHR
    val mshrBits      = log2Ceil(l2param.nrMSHR)
    val nrReplayEntry = l2param.nrReplayEntry
    val metaSramBank  = l2param.metaSramBank // TODO: remove this
    val nrBeat        = l2param.blockBytes / l2param.beatBytes
    val idsAll        = 256

    val enableClockGate       = l2param.enableClockGate
    val nrTempDataEntry       = l2param.nrTempDataEntry
    val dataIdBits            = log2Ceil(nrTempDataEntry)
    val nrReqBufEntry         = l2param.nrReqBufEntry
    val nrNonDataSourceDEntry = l2param.nrNonDataSourceDEntry
    val nrTXRSPEntry          = l2param.nrTXRSPEntry

    val rxrspCreditMAX = l2param.rxrspCreditMAX
    val rxsnpCreditMAX = l2param.rxsnpCreditMAX
    val rxdatCreditMAX = l2param.rxdatCreditMAX

    val replacementPolicy = l2param.replacementPolicy

    val deadlockThreshold = 10000 * 1

    val aliasBitsOpt = Some(2)

    lazy val edgeIn = p(EdgeInKey)

    // @formatter:off
    val _tlBundleParams = TLBundleParameters(
        addressBits = addressBits,
        dataBits = beatBytes * 8,
        sourceBits = 6, // TODO: Parameterize it
        sinkBits = 7,
        sizeBits = 3,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseFields = Nil,
        hasBCE = true
    )
    // @formatter:on

    def tlBundleParams: TLBundleParameters = if (l2param.useDiplomacy) {
        edgeIn.bundle
    } else {
        _tlBundleParams
    }

    lazy val nrClients = if (l2param.useDiplomacy) {
        edgeIn.client.clients.count(_.supports.probe)
    } else {
        l2param.nrClients
    }


    // @formatter:off
    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = addressBits,
        dataBits = beatBytes * 8,
        dataCheck = false
    )
    // @formatter:on

    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

    def getClientBitOH(sourceId: UInt): UInt = {
        if (l2param.useDiplomacy) {
            Cat(
                edgeIn.client.clients
                    .filter(_.supports.probe)
                    .map(c => {
                        c.sourceId.contains(sourceId).asInstanceOf[Bool]
                    })
                    .reverse
            )
        } else {
            if (nrClients == 1) {
                1.U(1.W)
            } else {

                /** 
                 * Now we suppose that we have 2 clients and each of them owns an unique id range(0~15(Core 0 DCache), 16~31(Core 1 DCache)).
                 * We also need 2 additional clients to send Get requests also called ICache with id range(32~47(Core 0 ICache), 48~63(Core 1 ICache))
                 * So we can use the sourceId to determine which client the request belongs to.
                 * The MSB<5:4> of sourceId is used to identify the clientBitOH because 0xF == 0b1111 = 15.
                 */
                require(nrClients == 2)
                assert(sourceId <= 63.U)

                /** 
                 * "b01" => L1 DCache Core0
                 * "b10" => L1 DCache Core1
                 */
                MuxCase(
                    "b00".U,
                    Seq(
                        (sourceId(5, 4) === "b00".U) -> "b01".U,
                        (sourceId(5, 4) === "b01".U) -> "b10".U
                    )
                )
            }
        }
    }

    def clientOHToSource(clientBitOH: UInt): UInt = {
        if (l2param.useDiplomacy) {
            if (nrClients <= 1) {
                0.U
            } else {
                Mux1H(
                    clientBitOH,
                    edgeIn.client.clients
                        .filter(_.supports.probe)
                        .map(c => c.sourceId.start.U)
                )
            }
        } else {
            Mux(clientBitOH === "b10".U, 16.U, 0.U)
        }
    }

    def parseAddress(x: UInt): (UInt, UInt, UInt) = {
        // used in Slice
        val offset = x
        val set    = offset >> (offsetBits + bankBits)
        val tag    = set >> setBits
        // (tag(tagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0)) // TODO:
        (tag, set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }

    def fastArb[T <: Data](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }

    def lfsrArb[T <: Data](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new Utils.LFSRArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }

    def arbTask[T <: Data](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new Arbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) { arb.suggestName(s"${name.get}_arb") }
        for ((a, req) <- arb.io.in.zip(in)) { a <> req }
        out <> arb.io.out
    }

    def needT(opcode: UInt, param: UInt): Bool = {
        !opcode(2) ||
        (opcode === TLMessages.Hint && param === TLHints.PREFETCH_WRITE) ||
        ((opcode === TLMessages.AcquireBlock || opcode === TLMessages.AcquirePerm) && param =/= TLPermissions.NtoB)
    }

    def needB(opcode: UInt, param: UInt): Bool = {
        opcode === TLMessages.Get ||
        opcode === TLMessages.AcquireBlock && param === TLPermissions.NtoB ||
        opcode === TLMessages.Hint && param === TLHints.PREFETCH_READ
    }

    def needData(opcode: UInt): Bool = {
        opcode === ReleaseData || opcode === GrantData || opcode === AccessAckData
    }
}
