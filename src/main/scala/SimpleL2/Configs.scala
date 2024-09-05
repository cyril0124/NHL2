package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLPermissions._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.util.{BundleField, BundleFieldBase, BundleKeyBase, ControlKey}
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import xs.utils.Code
import SimpleL2._
import SimpleL2.chi._

case object AliasKey extends ControlKey[UInt]("alias")
case class AliasField(width: Int) extends BundleField[UInt](AliasKey, Output(UInt(width.W)), _ := 0.U(width.W))

case object L2ParamKey extends Field[L2Param](L2Param())

case object EdgeInKey extends Field[TLEdgeIn]

case class L2OptimizationParam(
    reqBufOutLatch: Boolean = true,
    rxsnpHasLatch: Boolean = true,   // Whether to latch the request for one cycle delay in the RXSNP module
    sinkcHasLatch: Boolean = true,   // Whether to latch the request for one cycle delay in the SinkC module
    sourcebHasLatch: Boolean = true, // Whether to latch the request for one cycle delay on the path from MSHR sourceb task to SourceB
    rxrspHasLatch: Boolean = true,
    sinkaStallOnReqArb: Boolean = false,
    mshrStallOnReqArb: Boolean = false,
    latchTempDsToDs: Boolean = true // Whether to latch the refill data from TempDataStorage to DataStorage for one cycle. If it is true, it will eliminate the timing path of refilling data from TempDataStorage to DataStorage when data ECC is enabled.
)

case class L2Param(
    name: String = "L2",
    ways: Int = 4,
    sets: Int = 256,
    nrSlice: Int = 1,
    blockBytes: Int = 64,
    beatBytes: Int = 32,
    dataBits: Int = 64 * 8, // 64 Byte
    addressBits: Int = 44,
    chiBundleParams: Option[CHIBundleParameters] = None, // This will overwrite the default chi bundle parameters
    nrClients: Int = 2,                                  // Number of L1 DCache
    nrMSHR: Int = 16,
    nrExtraSinkId: Int = 16, // Extra sink ids for hit Acquire requests which need to wait GrantAck
    nrReplayEntrySinkA: Int = 4,
    nrReplayEntrySnoop: Int = 4,
    nrNonDataSourceDEntry: Int = 4,
    nrTXRSPEntry: Int = 4,
    nrReqBufEntry: Int = 4,
    optParam: L2OptimizationParam = L2OptimizationParam(),
    supportDCT: Boolean = true,
    rxrspCreditMAX: Int = 2,
    rxsnpCreditMAX: Int = 2,
    rxdatCreditMAX: Int = 2,
    replacementPolicy: String = "plru", // Option: "random", "plru", "lru"
    dataEccCode: String = "secded",     // Option: "none", "identity", "parity", "sec", "secded"
    useDiplomacy: Boolean = false       // If use diplomacy, EdgeInKey should be passed in
) {
    require(isPow2(ways))
    require(isPow2(sets))
    require(dataBits == 64 * 8)
    require(nrSlice >= 1)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
    require(nrClients >= 1)
    require(dataEccCode == "none" || dataEccCode == "identity" || dataEccCode == "parity" || dataEccCode == "sec" || dataEccCode == "secded")
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
    val nrBeat        = l2param.blockBytes / l2param.beatBytes
    val idsAll        = 256

    val optParam              = l2param.optParam
    val supportDCT            = l2param.supportDCT
    val nrReqBufEntry         = l2param.nrReqBufEntry
    val nrNonDataSourceDEntry = l2param.nrNonDataSourceDEntry
    val nrTXRSPEntry          = l2param.nrTXRSPEntry
    val nrReplayEntrySinkA    = l2param.nrReplayEntrySinkA
    val nrReplayEntrySnoop    = l2param.nrReplayEntrySnoop

    val rxrspCreditMAX = l2param.rxrspCreditMAX
    val rxsnpCreditMAX = l2param.rxsnpCreditMAX
    val rxdatCreditMAX = l2param.rxdatCreditMAX

    val replacementPolicy = l2param.replacementPolicy

    /** 
     * ECC parameters 
     */
    val dataEccCode = l2param.dataEccCode
    def dataCode: Code = Code.fromString(dataEccCode)

    val eccProtectBytes = 8
    val eccProtectBits  = eccProtectBytes * 8
    val dataWithECCBits = dataCode.width(eccProtectBits)
    val dataEccBits     = dataWithECCBits - eccProtectBits
    val enableDataECC   = dataEccBits > 0

    val deadlockThreshold = 10000 * 2

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

    val chiBundleParams = if (l2param.chiBundleParams.isDefined) {
        l2param.chiBundleParams.get
    } else {
        CHIBundleParameters(
            nodeIdBits = 7,
            addressBits = addressBits,
            dataBits = beatBytes * 8,
            dataCheck = false
        )
    }

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
                edgeIn.client.clients.filter(_.supports.probe).map(c => c.sourceId.start).head.U
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

    // finalTxnID => | bankID | txnID |
    def setTxnID(txnID: UInt, sliceID: UInt): UInt = {
        if (nrSlice <= 1) txnID else Cat(sliceID(bankBits - 1, 0), txnID.tail(bankBits + 1))
    }

    def getSliceID(txnID: UInt): UInt = {
        if (nrSlice <= 1) 0.U else txnID.head(bankBits) // The `bankBits` most significant bits(MSB)
    }

    def restoreTxnID(txnID: UInt): UInt = {
        if (nrSlice <= 1) txnID else Cat(0.U(bankBits.W), txnID.tail(bankBits + 1))
    }
}
