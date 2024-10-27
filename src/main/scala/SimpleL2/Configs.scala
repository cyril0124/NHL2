package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLPermissions._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.util.{BundleField, BundleFieldBase, BundleKeyBase, ControlKey}
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import xs.utils.Code
import xs.utils.tl.TLUserKey
import SimpleL2._
import SimpleL2.chi._
import freechips.rocketchip.diplomacy.AddressSet
import xs.utils.tl.TLNanhuBusField

case object L2ParamKey extends Field[L2Param](L2Param())

case object EdgeInKey extends Field[TLEdgeIn]

case class L1Param(
    aliasBitsOpt: Option[Int] = None,
    vaddrBitsOpt: Option[Int] = None
)

case class L2OptimizationParam(
    reqBufOutLatch: Boolean = true,
    rxsnpHasLatch: Boolean = true,   // Whether to latch the request for one cycle delay in the RXSNP module
    sinkcHasLatch: Boolean = true,   // Whether to latch the request for one cycle delay in the SinkC module
    sourcebHasLatch: Boolean = true, // Whether to latch the request for one cycle delay on the path from MSHR sourceb task to SourceB
    rxrspHasLatch: Boolean = true,
    sinkaStallOnReqArb: Boolean = false,
    mshrStallOnReqArb: Boolean = false,
    latchTempDsToDs: Boolean = true, // Whether to latch the refill data from TempDataStorage to DataStorage for one cycle. If it is true, it will eliminate the timing path of refilling data from TempDataStorage to DataStorage when data ECC is enabled.
    useFlatDataSRAM: Boolean = true
)

case class L2Param(
    name: String = "L2",
    ways: Int = 4,
    sets: Int = 256,
    nrSlice: Int = 1,
    blockBytes: Int = 64,
    beatBytes: Int = 32,
    dataBits: Int = 64 * 8,                              // 64 Byte
    addressBits: Int = 48,                               // used when diplomacy is not enabled
    chiBundleParams: Option[CHIBundleParameters] = None, // This will overwrite the default chi bundle parameters
    pageBytes: Int = 4096,                               // for prefetcher
    nrClients: Int = 1,
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
    prefetchParams: Seq[SimpleL2.prefetch.PrefetchParameters] = Nil,
    replacementPolicy: String = "plru",             // Option: "random", "plru", "lru"
    dataEccCode: String = "secded",                 // Option: "none", "identity", "parity", "sec", "secded"
    useDiplomacy: Boolean = false,                  // If use diplomacy, EdgeInKey should be passed in
    clientSourceIdOpt: Option[Seq[Seq[Int]]] = None // User defined sourceIds for the L1 clients that support `Probe` operation. Each is an unique sourceId range with no overlap
) {
    require(isPow2(ways))
    require(isPow2(sets))
    require(dataBits == 64 * 8)
    require(nrSlice >= 1)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
    require(dataEccCode == "none" || dataEccCode == "identity" || dataEccCode == "parity" || dataEccCode == "sec" || dataEccCode == "secded")

    private val addressMask = (1L << addressBits) - 1
    val addressSet          = AddressSet(0, addressMask)
}

trait HasL2Param {
    val p: Parameters
    val l2param = p(L2ParamKey)

    val ways          = l2param.ways
    val sets          = l2param.sets
    val wayBits       = log2Ceil(l2param.ways)
    val dataBits      = l2param.dataBits
    val beatBytes     = l2param.beatBytes
    val setBits       = log2Ceil(l2param.sets)
    val offsetBits    = log2Ceil(l2param.blockBytes)
    val blockBytes    = l2param.blockBytes
    val nrSlice       = l2param.nrSlice
    val bankBits      = log2Ceil(nrSlice)
    val sliceBits     = bankBits
    val nrMSHR        = l2param.nrMSHR
    val nrExtraSinkId = l2param.nrExtraSinkId
    val nrGrantMap    = nrMSHR
    val mshrBits      = log2Ceil(l2param.nrMSHR)
    val nrBeat        = l2param.blockBytes / l2param.beatBytes
    val idsAll        = 256

    def tlAddressBits = if (l2param.useDiplomacy) edgeIn.bundle.addressBits else l2param.addressBits
    def chiAddressBits = if (l2param.chiBundleParams.isDefined) l2param.chiBundleParams.get.addressBits else l2param.addressBits
    def addressBits = tlAddressBits
    def tagBits = l2param.addressBits - setBits - bankBits - offsetBits

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

    val nrClients = l2param.nrClients

    val aliasBitsOpt = {
        val aliasBits = p(TLUserKey).aliasBits
        if (aliasBits == 0) None else Some(aliasBits)
    }

    lazy val edgeIn = p(EdgeInKey)

    /**
     * vaddr without offset bits
     */
    def fullAddressBits = edgeIn.bundle.addressBits
    val vaddrBitsOpt = {
        val vaddrBits = p(TLUserKey).vaddrBits
        if (vaddrBits == 0) None else Some(vaddrBits)
    }
    val fullVAddrBits = vaddrBitsOpt.getOrElse(0) + offsetBits
    def fullTagBits = fullAddressBits - setBits - offsetBits

    /**
     * Prefetch related parameters
     */
    val prefetchParams = l2param.prefetchParams
    val enablePrefetch = if (prefetchParams.nonEmpty) true else false
    val pageOffsetBits = log2Ceil(l2param.pageBytes)
    def hasBOP = prefetchParams.exists(_.isInstanceOf[SimpleL2.prefetch.BOPParameters])
    def hasReceiver = prefetchParams.exists(_.isInstanceOf[SimpleL2.prefetch.PrefetchReceiverParams])
    // def hasTPPrefetcher = prefetchParams.exists(_.isInstanceOf[SimpleL2.prefetch.TPParameters])
    def hasPrefetchBit = prefetchParams.exists(_.hasPrefetchBit)
    def hasPrefetchSrc = prefetchParams.exists(_.hasPrefetchSrc)
    def hartIdLen: Int = p(MaxHartIdBits)

    val clientSourceIdOpt = if (l2param.clientSourceIdOpt.isDefined) {
        val clientSourceId = l2param.clientSourceIdOpt.get
        // Check for id overlap
        val sets = clientSourceId.map(_.toSet).reduce(_ & _)
        require(!sets.nonEmpty, s"clientSourceIdOpt has id overlap! => ${sets} origin ids: ${clientSourceId}")

        // clientSourceId should match the number of clients
        require(
            nrClients == clientSourceId.length,
            s"clientSourceId should match the number of clients! ${clientSourceId.length} =/= nrClients:${nrClients}"
        )

        Some(clientSourceId.map(_.sorted))
    } else None

    def tlBundleParams: TLBundleParameters = if (l2param.useDiplomacy) {
        edgeIn.bundle
    } else {
        // @formatter:off
        val _tlBundleParams = TLBundleParameters(
            addressBits = addressBits,
            dataBits = beatBytes * 8,
            sourceBits = 6, // TODO: Parameterize it
            sinkBits = 7,
            sizeBits = 3,
            echoFields = Nil,
            requestFields = Seq(TLNanhuBusField()(p)),
            responseFields = Nil,
            hasBCE = true
        )
        // @formatter:on

        _tlBundleParams
    }

    def chiBundleParams: CHIBundleParameters = if (l2param.chiBundleParams.isDefined) {
        l2param.chiBundleParams.get
    } else {
        CHIBundleParameters(
            nodeIdBits = 7,
            addressBits = chiAddressBits,
            dataBits = beatBytes * 8,
            dataCheck = false,
            issue = "G"
        )
    }

    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

    def isConsecutive(seq: Seq[Int]): Boolean = {
        seq.sliding(2).forall { case Seq(a, b) => b - a == 1 }
    }

    def getClientBitOH(sourceId: UInt, clientSourceId: Seq[Seq[Int]]): UInt = {
        val isConsecutiveId = clientSourceId.map(isConsecutive).reduce(_ && _)
        Cat(
            clientSourceId.map { ids =>
                if (ids.length == 0) {
                    false.B
                } else if (ids.length == 1) {
                    sourceId === ids.head.U
                } else {
                    if (isConsecutiveId) {
                        val x     = sourceId
                        val start = ids.head
                        val end   = ids.last
                        require(end > start)

                        // find index of largest different bit
                        val largestDeltaBit   = log2Floor(start ^ (end - 1))
                        val smallestCommonBit = largestDeltaBit + 1 // may not exist in x
                        val uncommonMask      = (1 << smallestCommonBit) - 1
                        val uncommonBits      = (x | 0.U(smallestCommonBit.W))(largestDeltaBit, 0)
                        // the prefix must match exactly (note: may shift ALL bits away)
                        (x >> smallestCommonBit) === (start >> smallestCommonBit).U &&
                        // firrtl constant prop range analysis can eliminate these two:
                        (start & uncommonMask).U <= uncommonBits &&
                        uncommonBits <= ((end - 1) & uncommonMask).U
                    } else {
                        Cat(ids.map(id => sourceId === id.U)).orR
                    }
                }
            }.reverse
        )
    }

    def getClientBitOH(sourceId: UInt, edgeIn: TLEdgeIn): UInt = {
        if (clientSourceIdOpt.isDefined) {
            getClientBitOH(sourceId, clientSourceIdOpt.get)
        } else {
            Cat(
                edgeIn.client.clients
                    .filter(_.supports.probe)
                    .map(c => {
                        c.sourceId.contains(sourceId).asInstanceOf[Bool]
                    })
                    .reverse
            )
        }
    }

    def getClientBitOH(sourceId: UInt): UInt = {
        if (l2param.useDiplomacy) {
            if (clientSourceIdOpt.isDefined) {
                getClientBitOH(sourceId, clientSourceIdOpt.get)
            } else {
                Cat(
                    edgeIn.client.clients
                        .filter(_.supports.probe)
                        .map(c => {
                            c.sourceId.contains(sourceId).asInstanceOf[Bool]
                        })
                        .reverse
                )
            }
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
            if (clientSourceIdOpt.isDefined) {
                val clientSourceId = clientSourceIdOpt.get
                if (nrClients <= 1) {
                    require(clientSourceId.length == 1)
                    clientSourceId.head.head.U
                } else {
                    Mux1H(
                        clientBitOH,
                        clientSourceId.map(ids => ids.head.U)
                    )
                }
            } else {
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
            }
        } else {
            Mux(clientBitOH === "b10".U, 16.U, 0.U) // Only for test
        }
    }

    def parseAddress(x: UInt): (UInt, UInt, UInt) = {
        // used in Slice
        val offset = x
        val set    = offset >> (offsetBits + bankBits)
        val tag    = set >> setBits
        (tag, set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }

    def parseFullAddress(x: UInt): (UInt, UInt, UInt) = {
        val offset = x
        val set    = offset >> offsetBits
        val tag    = set >> setBits
        (tag(fullTagBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
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
        if (nrSlice <= 1) txnID else Cat(sliceID(bankBits - 1, 0), txnID.tail(bankBits))
    }

    def getSliceID(txnID: UInt): UInt = {
        if (nrSlice <= 1) 0.U else txnID.head(bankBits) // The `bankBits` most significant bits(MSB)
    }

    def restoreTxnID(txnID: UInt): UInt = {
        if (nrSlice <= 1) txnID else Cat(0.U(bankBits.W), txnID.tail(bankBits))
    }

    def bank_eq(set: UInt, bankId: Int, bankBits: Int): Bool = {
        if (bankBits == 0) true.B else set(bankBits - 1, 0) === bankId.U
    }
}
