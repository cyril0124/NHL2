package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink.MasterMuxNode
import freechips.rocketchip.tilelink.TLMessages._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import SimpleL2.Configs._
import SimpleL2.chi._
import Utils.GenerateVerilog
import scala.math.BigInt

class CHIBundleDownstream_1(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Bundle {
    val tx_req: CHIChannelIO[CHIBundleREQ] = CHIChannelIO(new CHIBundleREQ(params), aggregateIO)
    val tx_dat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val tx_rsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)

    val rx_rsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))
    val rx_dat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val rx_snp: CHIChannelIO[CHIBundleSNP] = Flipped(CHIChannelIO(new CHIBundleSNP(params), aggregateIO))
}

class SimpleEndpointCHI()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val chi = Flipped(new CHIBundleDownstream_1(chiBundleParams))

        val chi_tx_txsactive     = Input(Bool())
        val chi_tx_rxsactive     = Output(Bool())
        val chi_tx_linkactivereq = Input(Bool())
        val chi_tx_linkactiveack = Output(Bool())
        val chi_rx_linkactivereq = Output(Bool())
        val chi_rx_linkactiveack = Input(Bool())
    })

    val fakeCHIBundle = WireInit(0.U.asTypeOf(new CHIBundleDownstream_1(chiBundleParams)))
    io.chi <> fakeCHIBundle

    io.elements.foreach(x => x._2 := DontCare)

    // Keep clock and reset
    val (_, cnt) = Counter(true.B, 10)
    dontTouch(cnt)

    dontTouch(io)
    io.elements.foreach(x => dontTouch(x._2))
}

class SimpleL2Cache(parentName: String = "L2_")(implicit p: Parameters) extends LazyModule with HasL2Param {
    val xfer   = TransferSizes(blockBytes, blockBytes)
    val atom   = TransferSizes(1, beatBytes)
    val access = TransferSizes(1, blockBytes)

    val addressRange = AddressSet(0x00000000L, 0xfffffffffL).subtract(AddressSet(0x0L, 0x7fffffffL)) // TODO: parameterize this
    val managerParameters = TLSlavePortParameters.v1(
        managers = Seq(
            TLSlaveParameters.v1(
                address = addressRange,
                regionType = RegionType.CACHED,
                supportsAcquireT = xfer,
                supportsAcquireB = xfer,
                supportsArithmetic = atom,
                supportsLogical = atom,
                supportsGet = access,
                supportsPutFull = access,
                supportsPutPartial = access,
                supportsHint = access,
                fifoId = None
            )
        ),
        beatBytes = 32,
        minLatency = 2,
        responseFields = Nil,
        requestKeys = Nil,
        endSinkId = idsAll * (1 << bankBits)
    )

    val sinkNodes = Seq.fill(nrSlice) { TLManagerNode(Seq(managerParameters)) }
    // val node = TLManagerNode(Seq(managerParameters))

    println(s"addressBits:$addressBits")
    println(s"tagBits:$tagBits")
    println(s"setBits:$setBits")
    println(s"bankBits:$bankBits")
    println(s"offsetBits:$offsetBits")

    // finalTxnID => | bankID | txnID |
    def setTxnID(txnID: UInt, sliceID: UInt): UInt = {
        if (nrSlice <= 1) txnID else Cat(sliceID(bankBits - 1, 0), txnID.tail(bankBits + 1))
    }

    def getSliceID(txnID: UInt): UInt = {
        if (nrSlice <= 1) 0.U else txnID.head(bankBits)
    }

    def restoreTxnID(txnID: UInt): UInt = {
        if (nrSlice <= 1) txnID else Cat(0.U(bankBits.W), txnID.tail(bankBits + 1))
    }

    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
        val io = IO(new Bundle {
            // val chi         = CHIBundleDownstream(chiBundleParams)
            // val chiLinkCtrl = new CHILinkCtrlIO()

            // compatible with kmh
            val chi                  = new CHIBundleDownstream_1(chiBundleParams)
            val chi_tx_txsactive     = Output(Bool())
            val chi_tx_rxsactive     = Input(Bool())
            val chi_tx_linkactivereq = Output(Bool())
            val chi_tx_linkactiveack = Input(Bool())
            val chi_rx_linkactivereq = Input(Bool())
            val chi_rx_linkactiveack = Output(Bool())

            val nodeID = Input(UInt(12.W))
        })

        println(s"node size: ${sinkNodes.length}")
        sinkNodes.zipWithIndex.foreach { case (node, i) =>
            val edgeIn   = node.in.head._2
            val bundleIn = node.in.head._1
            // edgeIn.client.clients.foreach { c =>
            //     println(s"[ALL] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
            // }
            edgeIn.client.clients.filter(_.supports.probe).foreach { c =>
                println(s"[node_$i][TL-C ] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
            }
            edgeIn.client.clients.filter(!_.supports.probe).foreach { c =>
                println(s"[node_$i][TL-UL] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
            }
        }

        val linkMonitor = Module(new LinkMonitor)
        val slices = (0 until nrSlice).map { i =>
            val edgeIn = sinkNodes(i).in.head._2

            Module(new Slice()(p.alterPartial { case EdgeInKey =>
                edgeIn
            }))
        }

        slices.zip(sinkNodes).zipWithIndex.foreach { case ((slice, node), i) =>
            val bundleIn = node.in.head._1

            slice.io         <> DontCare
            slice.io.tl      <> bundleIn
            slice.io.sliceId := i.U(bankBits.W)
        }

        /** TXREQ */
        val txreq    = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleREQ(chiBundleParams))))
        val txreqArb = Module(new Arbiter(new CHIBundleREQ(chiBundleParams), nrSlice))
        txreqArb.io.in   <> slices.map(_.io.chi.txreq)
        txreq            <> txreqArb.io.out
        txreq.bits.txnID := setTxnID(txreqArb.io.out.bits.txnID, txreqArb.io.chosen)

        /** TXDAT */
        val txdat = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleDAT(chiBundleParams))))
        arbTask(slices.map(_.io.chi.txdat), txdat, Some("slice_txdat"))

        /** TXRSP */
        val txrsp = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleRSP(chiBundleParams))))
        arbTask(slices.map(_.io.chi.txrsp), txrsp, Some("slice_txrsp"))

        /** RXSNP */
        val rxsnp        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleSNP(chiBundleParams)))))
        val rxsnpSliceID = if (nrSlice <= 1) 0.U else (rxsnp.bits.addr >> (offsetBits - 3))(bankBits - 1, 0)
        slices.zipWithIndex.foreach { case (slice, i) =>
            slice.io.chi.rxsnp.valid := rxsnp.valid && rxsnpSliceID === i.U
            slice.io.chi.rxsnp.bits  := rxsnp.bits
        }
        rxsnp.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxsnp.ready && rxsnpSliceID === i.U }).orR

        /** RXRSP */
        val rxrsp        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))))
        val rxrspSliceID = getSliceID(rxrsp.bits.txnID)
        slices.zipWithIndex.foreach { case (slice, i) =>
            slice.io.chi.rxrsp.valid      := rxrsp.valid && rxrspSliceID === i.U
            slice.io.chi.rxrsp.bits       := rxrsp.bits
            slice.io.chi.rxrsp.bits.txnID := restoreTxnID(rxrsp.bits.txnID)
        }
        rxrsp.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxrsp.ready && rxrspSliceID === i.U }).orR

        /** RXDAT */
        val rxdat        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))))
        val rxdatSliceID = getSliceID(rxdat.bits.txnID)
        slices.zipWithIndex.foreach { case (slice, i) =>
            slice.io.chi.rxdat.valid      := rxdat.valid
            slice.io.chi.rxdat.bits       := rxdat.bits
            slice.io.chi.rxdat.bits.txnID := restoreTxnID(rxdat.bits.txnID)
        }
        rxdat.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxdat.ready && rxdatSliceID === i.U }).orR

        linkMonitor.io              <> DontCare
        linkMonitor.io.in.chi.txreq <> txreq
        linkMonitor.io.in.chi.txdat <> txdat
        linkMonitor.io.in.chi.txrsp <> txrsp
        linkMonitor.io.in.chi.rxrsp <> rxrsp
        linkMonitor.io.in.chi.rxsnp <> rxsnp
        linkMonitor.io.in.chi.rxdat <> rxdat
        linkMonitor.io.nodeID       := io.nodeID

        // io.chi         <> linkMonitor.io.out.chi
        // io.chiLinkCtrl <> linkMonitor.io.out.chiLinkCtrl
        // dontTouch(io.chi)
        // dontTouch(io.chiLinkCtrl)

        // compatible with kmh
        io.chi.tx_req           <> linkMonitor.io.out.chi.txreq
        io.chi.tx_dat           <> linkMonitor.io.out.chi.txdat
        io.chi.tx_rsp           <> linkMonitor.io.out.chi.txrsp
        io.chi.rx_rsp           <> linkMonitor.io.out.chi.rxrsp
        io.chi.rx_snp           <> linkMonitor.io.out.chi.rxsnp
        io.chi.rx_dat           <> linkMonitor.io.out.chi.rxdat
        io.chi_tx_txsactive     <> linkMonitor.io.out.chiLinkCtrl.txsactive
        io.chi_tx_rxsactive     <> linkMonitor.io.out.chiLinkCtrl.rxsactive
        io.chi_tx_linkactivereq <> linkMonitor.io.out.chiLinkCtrl.txactivereq
        io.chi_tx_linkactiveack <> linkMonitor.io.out.chiLinkCtrl.txactiveack
        io.chi_rx_linkactivereq <> linkMonitor.io.out.chiLinkCtrl.rxactivereq
        io.chi_rx_linkactiveack <> linkMonitor.io.out.chiLinkCtrl.rxactiveack
        dontTouch(io)
    }

    // class Impl extends LazyModuleImp(this) {
    //     val io = IO(new Bundle {
    //         // val chi         = CHIBundleDownstream(chiBundleParams)
    //         // val chiLinkCtrl = new CHILinkCtrlIO()

    //         // compatible with kmh
    //         val chi                  = new CHIBundleDownstream_1(chiBundleParams)
    //         val chi_tx_txsactive     = Output(Bool())
    //         val chi_tx_rxsactive     = Input(Bool())
    //         val chi_tx_linkactivereq = Output(Bool())
    //         val chi_tx_linkactiveack = Input(Bool())
    //         val chi_rx_linkactivereq = Input(Bool())
    //         val chi_rx_linkactiveack = Output(Bool())

    //         val nodeID = Input(UInt(12.W))
    //     })

    //     println(s"node size: ${node.in.length}")
    //     node.in.zipWithIndex.foreach { case ((bundleIn, edgeIn), i) =>
    //         // edgeIn.client.clients.foreach { c =>
    //         //     println(s"[ALL] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
    //         // }
    //         edgeIn.client.clients.filter(_.supports.probe).foreach { c =>
    //             println(s"[node_$i][TL-C ] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
    //         }
    //         edgeIn.client.clients.filter(!_.supports.probe).foreach { c =>
    //             println(s"[node_$i][TL-UL] client_name:${c.name} sourceId_start:${c.sourceId.start} sourceId_end:${c.sourceId.end}")
    //         }
    //     }

    //     val linkMonitor = Module(new LinkMonitor)
    //     val slices = (0 until nrSlice).map { i =>
    //         val edgeIn = node.in(i)._2

    //         Module(new Slice()(p.alterPartial { case EdgeInKey =>
    //             edgeIn
    //         }))
    //     }

    //     slices.zipWithIndex.foreach { case (slice, i) =>
    //         val bundleIn = node.in(i)._1

    //         slice.io         <> DontCare
    //         slice.io.tl      <> bundleIn
    //         slice.io.sliceId := i.U(bankBits.W)
    //     }

    //     /** TXREQ */
    //     val txreq    = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleREQ(chiBundleParams))))
    //     val txreqArb = Module(new Arbiter(new CHIBundleREQ(chiBundleParams), nrSlice))
    //     txreqArb.io.in   <> slices.map(_.io.chi.txreq)
    //     txreq            <> txreqArb.io.out
    //     txreq.bits.txnID := setTxnID(txreqArb.io.out.bits.txnID, txreqArb.io.chosen)

    //     /** TXDAT */
    //     val txdat = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleDAT(chiBundleParams))))
    //     arbTask(slices.map(_.io.chi.txdat), txdat, Some("slice_txdat"))

    //     /** TXRSP */
    //     val txrsp = WireInit(0.U.asTypeOf(Decoupled(new CHIBundleRSP(chiBundleParams))))
    //     arbTask(slices.map(_.io.chi.txrsp), txrsp, Some("slice_txrsp"))

    //     /** RXSNP */
    //     val rxsnp        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleSNP(chiBundleParams)))))
    //     val rxsnpSliceID = if (nrSlice <= 1) 0.U else (rxsnp.bits.addr >> (offsetBits - 3))(bankBits - 1, 0)
    //     slices.zipWithIndex.foreach { case (slice, i) =>
    //         slice.io.chi.rxsnp.valid := rxsnp.valid && rxsnpSliceID === i.U
    //         slice.io.chi.rxsnp.bits  := rxsnp.bits
    //     }
    //     rxsnp.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxsnp.ready && rxsnpSliceID === i.U }).orR

    //     /** RXRSP */
    //     val rxrsp        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))))
    //     val rxrspSliceID = getSliceID(rxrsp.bits.txnID)
    //     slices.zipWithIndex.foreach { case (slice, i) =>
    //         slice.io.chi.rxrsp.valid      := rxrsp.valid && rxrspSliceID === i.U
    //         slice.io.chi.rxrsp.bits       := rxrsp.bits
    //         slice.io.chi.rxrsp.bits.txnID := restoreTxnID(rxrsp.bits.txnID)
    //     }
    //     rxrsp.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxrsp.ready && rxrspSliceID === i.U }).orR

    //     /** RXDAT */
    //     val rxdat        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))))
    //     val rxdatSliceID = getSliceID(rxdat.bits.txnID)
    //     slices.zipWithIndex.foreach { case (slice, i) =>
    //         slice.io.chi.rxdat.valid      := rxdat.valid
    //         slice.io.chi.rxdat.bits       := rxdat.bits
    //         slice.io.chi.rxdat.bits.txnID := restoreTxnID(rxdat.bits.txnID)
    //     }
    //     rxdat.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxdat.ready && rxdatSliceID === i.U }).orR

    //     linkMonitor.io              <> DontCare
    //     linkMonitor.io.in.chi.txreq <> txreq
    //     linkMonitor.io.in.chi.txdat <> txdat
    //     linkMonitor.io.in.chi.txrsp <> txrsp
    //     linkMonitor.io.in.chi.rxrsp <> rxrsp
    //     linkMonitor.io.in.chi.rxsnp <> rxsnp
    //     linkMonitor.io.in.chi.rxdat <> rxdat
    //     linkMonitor.io.nodeID       := io.nodeID

    //     // io.chi         <> linkMonitor.io.out.chi
    //     // io.chiLinkCtrl <> linkMonitor.io.out.chiLinkCtrl
    //     // dontTouch(io.chi)
    //     // dontTouch(io.chiLinkCtrl)

    //     // compatible with kmh
    //     io.chi.tx_req           <> linkMonitor.io.out.chi.txreq
    //     io.chi.tx_dat           <> linkMonitor.io.out.chi.txdat
    //     io.chi.tx_rsp           <> linkMonitor.io.out.chi.txrsp
    //     io.chi.rx_rsp           <> linkMonitor.io.out.chi.rxrsp
    //     io.chi.rx_snp           <> linkMonitor.io.out.chi.rxsnp
    //     io.chi.rx_dat           <> linkMonitor.io.out.chi.rxdat
    //     io.chi_tx_txsactive     <> linkMonitor.io.out.chiLinkCtrl.txsactive
    //     io.chi_tx_rxsactive     <> linkMonitor.io.out.chiLinkCtrl.rxsactive
    //     io.chi_tx_linkactivereq <> linkMonitor.io.out.chiLinkCtrl.txactivereq
    //     io.chi_tx_linkactiveack <> linkMonitor.io.out.chiLinkCtrl.txactiveack
    //     io.chi_rx_linkactivereq <> linkMonitor.io.out.chiLinkCtrl.rxactivereq
    //     io.chi_rx_linkactiveack <> linkMonitor.io.out.chiLinkCtrl.rxactiveack
    //     dontTouch(io)
    // }
}

class SimpleL2CacheWrapper(nrCore: Int = 1, nrSlice: Int = 1, nodeID: Int = 0)(implicit p: Parameters) extends LazyModule {
    val cacheParams = p(L2ParamKey)

    def createDCacheNode(name: String, sources: Int) = {
        val masterNode = TLClientNode(
            Seq(
                TLMasterPortParameters.v2(
                    masters = Seq(
                        TLMasterParameters.v1(
                            name = name,
                            sourceId = IdRange(0, sources),
                            supportsProbe = TransferSizes(cacheParams.blockBytes)
                        )
                    ),
                    channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
                    minLatency = 1,
                    echoFields = Nil,
                    requestFields = Nil,
                    responseKeys = Nil
                )
            )
        )
        masterNode
    }

    def createICacheNode(name: String, source: Int) = {
        val masterNode = TLClientNode(
            Seq(
                TLMasterPortParameters.v1(
                    clients = Seq(
                        TLMasterParameters.v1(
                            name = name,
                            sourceId = IdRange(0, source)
                        )
                    )
                )
            )
        )
        masterNode
    }

    val BlockSize = 64 // in byte

    val bankBinder = BankBinder(nrSlice, BlockSize)
    val l1d_nodes  = (0 until nrCore).map { i => createDCacheNode(s"L1D_$i", 16) }
    val l1i_nodes  = (0 until nrCore).map { i => createICacheNode(s"L1I_$i", 16) }

    val l2 = LazyModule(new SimpleL2Cache()(p.alterPartial { case L2ParamKey =>
        L2Param(
            useDiplomacy = true,
            nrSlice = nrSlice,
            blockBytes = BlockSize
        )
    }))
    val l1xbar = TLXbar()

    (0 until nrCore).foreach { i =>
        l1xbar := TLBuffer.chainNode(2) := TLDelayer(0.3) := l1d_nodes(i)
        l1xbar := TLBuffer.chainNode(2) := TLDelayer(0.3) := l1i_nodes(i)
    }

    l2.sinkNodes.foreach { node =>
        node := bankBinder
    }
    bankBinder :*= l1xbar

    lazy val module = new LazyModuleImp(this) {
        (0 until nrCore).foreach { i =>
            l1d_nodes(i).makeIOs()(ValName(s"dcache_in_$i"))
            l1i_nodes(i).makeIOs()(ValName(s"icache_in_$i"))
        }

        // l2.module.io     <> DontCare
        // l2.module.io.chi <> DontCare
        // l2.module.io.chiLinkCtrl <> DontCare
        l2.module.io.nodeID := nodeID.U

        val endpoint = Module(new SimpleEndpointCHI)
        endpoint.io.chi                  <> l2.module.io.chi
        endpoint.io.chi_rx_linkactiveack <> l2.module.io.chi_rx_linkactiveack
        endpoint.io.chi_rx_linkactivereq <> l2.module.io.chi_rx_linkactivereq
        endpoint.io.chi_tx_linkactiveack <> l2.module.io.chi_tx_linkactiveack
        endpoint.io.chi_tx_linkactivereq <> l2.module.io.chi_tx_linkactivereq
        endpoint.io.chi_tx_rxsactive     <> l2.module.io.chi_tx_rxsactive
        endpoint.io.chi_tx_txsactive     <> l2.module.io.chi_tx_txsactive
        dontTouch(endpoint.io)
        dontTouch(l2.module.io)
    }
}

object SimpleL2Cache extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(nrCore = 2, nrSlice = 1, nodeID = 12)(p)))(config)

    GenerateVerilog(args, () => top.module, name = "SimpleL2CacheWrapper", split = false)
}
