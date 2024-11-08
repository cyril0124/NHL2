package SimpleL2

import chisel3._
import chisel3.util._
import chisel3.experimental.{SourceInfo, SourceLine}
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.interrupts.{IntSinkNode, IntSinkPortSimple}
import freechips.rocketchip.interrupts.{IntSourceNode, IntSourcePortSimple}
import freechips.rocketchip.util.SeqToAugmentedSeq
import freechips.rocketchip.tile.MaxHartIdBits
import xs.utils.perf._
import xs.utils.tl.{TLNanhuBusField, TLNanhuBusKey, TLUserKey, TLUserParams}
import xs.utils.FastArbiter
import SimpleL2.Configs._
import SimpleL2.chi._
import Utils.GenerateVerilog

abstract class L2Module(implicit val p: Parameters) extends Module with HasL2Param with HadMixedStateOps
abstract class L2Bundle(implicit val p: Parameters) extends Bundle with HasL2Param

object _assert {
    def apply(cond: Bool, message: String, data: Bits*)(implicit s: SourceInfo) = {
        val regData = data.map(RegNext(_))
        val debugInfo = s match {
            case SourceLine(filename, line, col) => s"($filename:$line:$col)"
            case _                               => ""
        }
        assert(RegNext(cond), message + " at " + debugInfo, regData: _*)
    }

    def apply(cond: Bool)(implicit s: SourceInfo) = {
        val debugInfo = s match {
            case SourceLine(filename, line, col) => s"($filename:$line:$col)"
            case _                               => ""
        }
        assert(RegNext(cond), "at " + debugInfo)
    }
}

object DefaultConfig {
    def apply() = new Config((_, _, _) => {
        case TLUserKey             => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey            => L2Param()
        case DebugOptionsKey       => DebugOptions()
        case DebugOptionsKey       => DebugOptions(EnablePerfDebug = false)
        case PerfCounterOptionsKey => PerfCounterOptions(enablePerfPrint = false, enablePerfDB = false, perfDBHartID = 0)
        case LogUtilsOptionsKey    => LogUtilsOptions(enableDebug = false, enablePerf = false, fpgaPlatform = false)
    })
}

class CHIBundleDownstream_1(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Bundle {
    val tx_req: CHIChannelIO[CHIBundleREQ] = CHIChannelIO(new CHIBundleREQ(params), aggregateIO)
    val tx_dat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val tx_rsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)

    val rx_rsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))
    val rx_dat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val rx_snp: CHIChannelIO[CHIBundleSNP] = Flipped(CHIChannelIO(new CHIBundleSNP(params), aggregateIO))
}

// Only for simulation
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

    val addressRange = Seq(l2param.addressSet) // TODO: parameterize this
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
        requestKeys = Seq(TLNanhuBusKey),
        endSinkId = idsAll
    )

    val sinkNodes = Seq.fill(nrSlice) { TLManagerNode(Seq(managerParameters)) }
    // val node = TLManagerNode(Seq(managerParameters))

    // Interrupt node for ECC error reporting
    val device     = new SimpleDevice("l2", Seq("xiangshan,simpleL2"))
    val eccIntNode = IntSourceNode(IntSourcePortSimple(resources = device.int))

    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with HasL2Param {
        def finalEdgeIn = sinkNodes.head.in.head._2

        val l2ToTlbParams: Parameters = p.alterPartial { case EdgeInKey =>
            finalEdgeIn
        }

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

            val prefetchOpt =
                if (enablePrefetch) Some(new Bundle {
                    val tlbReqs    = Vec(nrClients, new SimpleL2.prefetch.L2ToL1TlbIO(nRespDups = 1)(l2ToTlbParams))
                    val recv_addrs = Vec(nrClients, Flipped((new SimpleL2.prefetch.PrefetchIO)(l2ToTlbParams).recv_addr.cloneType))
                })
                else None
        })

        // Each client has its own prefetcher since L2 allows connection to multiple clients
        val prefetchersOpt = Seq.fill(nrClients) {
            // TODO: prefetcher receiver
            if (enablePrefetch)
                Some(
                    Module(new SimpleL2.prefetch.Prefetcher()(p.alterPartial {
                        case EdgeInKey     => finalEdgeIn
                        case MaxHartIdBits => 12 // TODO:
                    }))
                )
            else None
        }

        prefetchersOpt.zipWithIndex.foreach { case (prefetcherOpt, i) =>
            prefetcherOpt.foreach { prefetcher =>
                prefetcher.io          <> DontCare
                prefetcher.hartId      := DontCare
                prefetcher.io_l2_pf_en := true.B // Enable prefetcher

                prefetcher.io.tlb_req   <> io.prefetchOpt.get.tlbReqs(i)
                prefetcher.io.recv_addr <> io.prefetchOpt.get.recv_addrs(i)

                dontTouch(prefetcher.io)
            }
        }

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
            Module(new Slice()(p.alterPartial { case EdgeInKey =>
                sinkNodes(i).in.head._2
            }))
        }

        /** If there has any ECC error in slices, the error signal will be sent to the [[eccIntNode]] */
        val slicesECC   = VecInit(slices.map(s => RegNext(s.io.eccError)))
        val hasECCError = Cat(slicesECC.asUInt).orR
        eccIntNode.out.foreach(int => int._1.foreach(_ := hasECCError))
        eccIntNode.out.foreach(i   => dontTouch(i._1))

        slices.zip(sinkNodes).zipWithIndex.foreach { case ((slice, node), i) =>
            val bundleIn = node.in.head._1

            slice.io         <> DontCare
            slice.io.tl      <> bundleIn
            slice.io.sliceId := i.U(bankBits.W)
        }

        if (enablePrefetch) {
            val edgeIn = sinkNodes.head.in.head._2

            /** Prefetcher Req */
            slices.zipWithIndex.foreach { case (slice, i) =>
                val prefetchReqArb = Module(new Arbiter(slice.io.prefetchReqOpt.get.bits.cloneType, nrClients))

                prefetchersOpt.zipWithIndex.foreach { case (prefetcher, j) =>
                    val req   = prefetcher.get.io.req
                    val arbIn = prefetchReqArb.io.in(j)

                    arbIn.valid := req.valid && bank_eq(req.bits.set, i, bankBits)
                    arbIn.bits  := req.bits
                    req.ready   := arbIn.ready
                }

                slice.io.prefetchReqOpt.get <> prefetchReqArb.io.out
            }

            /** Prefetch Train */
            val prefetchTrain    = WireInit(0.U.asTypeOf(slices.head.io.prefetchTrainOpt.get.cloneType))
            val prefetchTrainArb = Module(new FastArbiter(slices.head.io.prefetchTrainOpt.get.bits.cloneType, nrSlice))
            val trainClientOH    = getClientBitOH(prefetchTrain.bits.source, finalEdgeIn)
            prefetchTrainArb.io.in <> slices.map(_.io.prefetchTrainOpt.get)
            prefetchTrain          <> prefetchTrainArb.io.out
            prefetchTrain.ready    := prefetchersOpt.map(_.get.io.train.ready).reduce(_ || _)

            prefetchersOpt.zipWithIndex.foreach { case (prefetcherOpt, i) =>
                prefetcherOpt.get.io.train.valid := prefetchTrain.valid && trainClientOH(i)
                prefetcherOpt.get.io.train.bits  := prefetchTrain.bits
            }

            require(trainClientOH.getWidth == prefetchersOpt.length)
            assert(PopCount(trainClientOH) <= 1.U)

            /** Prefetch Resp */
            val prefetchResp    = WireInit(0.U.asTypeOf(slices.head.io.prefetchRespOpt.get.cloneType))
            val prefetchRespArb = Module(new FastArbiter(slices.head.io.prefetchRespOpt.get.bits.cloneType, nrSlice))
            val respClientOH    = getClientBitOH(prefetchResp.bits.source, edgeIn)
            prefetchRespArb.io.in <> slices.map(_.io.prefetchRespOpt.get)
            prefetchResp          <> prefetchRespArb.io.out
            prefetchResp.ready    := prefetchersOpt.map(_.get.io.resp.ready).reduce(_ || _)

            prefetchersOpt.zipWithIndex.foreach { case (prefetcherOpt, i) =>
                prefetcherOpt.get.io.resp.valid := prefetchResp.valid && respClientOH(i)
                prefetcherOpt.get.io.resp.bits  := prefetchResp.bits
            }

            require(respClientOH.getWidth == prefetchersOpt.length)
            assert(PopCount(respClientOH) <= 1.U)
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
        val rxrsp = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))))

        val retryHelper  = Module(new RetryHelper()) // A RetryHelper module for handling RetryAck and PCrdGrant
        val rxrspOut     = retryHelper.io.rxrspOut
        val rxrspSliceID = retryHelper.io.sliceID
        retryHelper.io.rxrspIn <> rxrsp
        retryHelper.io.pCrdRetryInfoVecs.zipWithIndex.foreach { case (pCrdRetryInfoVec, i) =>
            pCrdRetryInfoVec <> slices(i).io.pCrdRetryInfoVec
        }

        slices.zipWithIndex.foreach { case (slice, i) =>
            slice.io.chi.rxrsp.valid      := rxrspOut.valid && rxrspSliceID === i.U
            slice.io.chi.rxrsp.bits       := rxrspOut.bits
            slice.io.chi.rxrsp.bits.txnID := restoreTxnID(rxrspOut.bits.txnID)
        }
        rxrspOut.ready := Cat(slices.zipWithIndex.map { case (s, i) => s.io.chi.rxrsp.ready && rxrspSliceID === i.U }).orR

        /** RXDAT */
        val rxdat        = WireInit(0.U.asTypeOf(Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))))
        val rxdatSliceID = getSliceID(rxdat.bits.txnID)
        slices.zipWithIndex.foreach { case (slice, i) =>
            slice.io.chi.rxdat.valid      := rxdat.valid && rxdatSliceID === i.U
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
}

class SimpleL2CacheWrapper(idRangeMax: Int = 16, nodeID: Int = 0, hasEndpoint: Boolean = true)(implicit p: Parameters) extends LazyModule {
    val cacheParams = p(L2ParamKey)

    val blockBytes     = cacheParams.blockBytes
    val nrCore         = cacheParams.nrClients
    val nrSlice        = cacheParams.nrSlice
    val ways           = cacheParams.ways
    val sets           = cacheParams.sets
    val enablePrefetch = cacheParams.prefetchParams.nonEmpty

    val capacityInBytes  = nrSlice * ways * sets * blockBytes
    val capacityInKBytes = capacityInBytes / 1024
    println(s"[${this.getClass}] capacity: ${capacityInBytes} B / ${capacityInKBytes} KB")

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
                    requestFields = Seq(TLNanhuBusField()),
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

    val l2EccIntSinkNode = IntSinkNode(IntSinkPortSimple(1, 1))

    val BlockSize = 64 // in byte

    val bankBinder = BankBinder(nrSlice, BlockSize)
    val l1d_nodes  = (0 until nrCore).map { i => createDCacheNode(s"L1D_$i", idRangeMax) }
    val l1i_nodes  = (0 until nrCore).map { i => createICacheNode(s"L1I_$i", idRangeMax) }

    val l2     = LazyModule(new SimpleL2Cache)
    val l1xbar = TLXbar()

    (0 until nrCore).foreach { i =>
        l1xbar := TLBuffer.chainNode(2) := l1d_nodes(i)
        l1xbar := TLBuffer.chainNode(2) := l1i_nodes(i)
    }

    l2.sinkNodes.foreach { node =>
        node := bankBinder
    }
    bankBinder :*= l1xbar

    l2EccIntSinkNode := l2.eccIntNode

    lazy val module = new LazyModuleImp(this) {
        val io = IO(new Bundle {
            val prefetchOpt = if (enablePrefetch) Some(l2.module.io.prefetchOpt.get.cloneType) else None
        })

        (0 until nrCore).foreach { i =>
            l1d_nodes(i).makeIOs()(ValName(s"dcache_in_$i"))
            l1i_nodes(i).makeIOs()(ValName(s"icache_in_$i"))
        }

        l2EccIntSinkNode.makeIOs()(ValName("l2EccInt"))

        // l2.module.io     <> DontCare
        // l2.module.io.chi <> DontCare
        // l2.module.io.chiLinkCtrl <> DontCare
        l2.module.io.nodeID := nodeID.U

        io.prefetchOpt.foreach { prefetch =>
            prefetch <> l2.module.io.prefetchOpt.get
        }

        if (hasEndpoint) {
            val endpoint = Module(new SimpleEndpointCHI)
            endpoint.io.chi                  <> l2.module.io.chi
            endpoint.io.chi_rx_linkactiveack <> l2.module.io.chi_rx_linkactiveack
            endpoint.io.chi_rx_linkactivereq <> l2.module.io.chi_rx_linkactivereq
            endpoint.io.chi_tx_linkactiveack <> l2.module.io.chi_tx_linkactiveack
            endpoint.io.chi_tx_linkactivereq <> l2.module.io.chi_tx_linkactivereq
            endpoint.io.chi_tx_rxsactive     <> l2.module.io.chi_tx_rxsactive
            endpoint.io.chi_tx_txsactive     <> l2.module.io.chi_tx_txsactive
            dontTouch(endpoint.io)
        } else {
            val l2_chi                  = IO(l2.module.io.chi.cloneType)
            val l2_chi_rx_linkactiveack = IO(l2.module.io.chi_rx_linkactiveack.cloneType)
            val l2_chi_rx_linkactivereq = IO(Flipped(l2.module.io.chi_rx_linkactivereq.cloneType))
            val l2_chi_tx_linkactiveack = IO(Flipped(l2.module.io.chi_tx_linkactiveack.cloneType))
            val l2_chi_tx_linkactivereq = IO(l2.module.io.chi_tx_linkactivereq.cloneType)
            val l2_chi_tx_rxsactive     = IO(Flipped(l2.module.io.chi_tx_rxsactive.cloneType))
            val l2_chi_tx_txsactive     = IO(l2.module.io.chi_tx_txsactive.cloneType)
            l2_chi                  <> l2.module.io.chi
            l2_chi_rx_linkactiveack <> l2.module.io.chi_rx_linkactiveack
            l2_chi_rx_linkactivereq <> l2.module.io.chi_rx_linkactivereq
            l2_chi_tx_linkactiveack <> l2.module.io.chi_tx_linkactiveack
            l2_chi_tx_linkactivereq <> l2.module.io.chi_tx_linkactivereq
            l2_chi_tx_rxsactive     <> l2.module.io.chi_tx_rxsactive
            l2_chi_tx_txsactive     <> l2.module.io.chi_tx_txsactive
        }

        dontTouch(l2.module.io)
    }
}

// For integration test
object SimpleL2Cache extends App {
    val nrCore  = 4
    val nrSlice = 1
    val nodeID  = 12

    xs.utils.Constantin.init(false)

    val config = new Config((_, _, _) => {
        case DebugOptionsKey => DebugOptions()
        case TLUserKey       => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey =>
            L2Param(
                ways = 4,
                sets = 256,
                nrSlice = nrSlice,
                useDiplomacy = true,
                nrClients = nrCore,
                prefetchParams = Seq(SimpleL2.prefetch.BOPParameters(virtualTrain = true), SimpleL2.prefetch.PrefetchReceiverParams())
            )

        case PerfCounterOptionsKey => PerfCounterOptions(enablePerfPrint = false, enablePerfDB = false, perfDBHartID = 0)
    })

    val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(idRangeMax = 64, nodeID = nodeID, hasEndpoint = true)(p)))(config)

    GenerateVerilog(args, () => top.module, name = "SimpleL2CacheWrapper", release = false, split = false)
}

// For logic synthesis
object SimpleL2CacheFinal extends App {
    val nodeID = 12

    val config_256kb_8way_2slice_1core = new Config((_, _, _) => {
        case TLUserKey => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey =>
            L2Param(
                ways = 8,
                sets = 256,
                nrSlice = 2,
                useDiplomacy = true,
                nrClients = 1
            )
        case DebugOptionsKey => DebugOptions()
    })

    val config_256kb_8way_4slice_1core = new Config((_, _, _) => {
        case TLUserKey => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey =>
            L2Param(
                ways = 8,
                sets = 128,
                nrSlice = 4,
                useDiplomacy = true,
                nrClients = 1
            )
        case DebugOptionsKey => DebugOptions()
    })

    val config_128kb_8way_2slice_1core = new Config((_, _, _) => {
        case TLUserKey => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey =>
            L2Param(
                ways = 8,
                sets = 128,
                nrSlice = 2,
                useDiplomacy = true,
                nrClients = 1
            )
        case DebugOptionsKey => DebugOptions()
    })

    val config_1024kb_8way_2slice_2core = new Config((_, _, _) => {
        case TLUserKey => TLUserParams(aliasBits = 2, vaddrBits = 48)
        case L2ParamKey =>
            L2Param(
                ways = 8,
                sets = 1024,
                nrSlice = 2,
                useDiplomacy = true,
                nrClients = 2
            )
        case DebugOptionsKey => DebugOptions()
    })

    // 256-KB, 8-way, 2 slice, 1 core
    // val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(nodeID = 12, hasEndpoint = false)(p)))(config_256kb_8way_2slice_1core)

    // 256-KB, 8-way, 4 slice, 1 core
    // val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(nodeID = 12, hasEndpoint = false)(p)))(config_256kb_8way_4slice_1core)

    // 128-KB, 8-way, 2 slice, 1 core
    // val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(nodeID = 12, hasEndpoint = false)(p)))(config_128kb_8way_2slice_1core)

    // 1024-KB, 8-way, 2 slice, 2 core
    val top = DisableMonitors(p => LazyModule(new SimpleL2CacheWrapper(nodeID = 12, hasEndpoint = false)(p)))(config_1024kb_8way_2slice_2core)

    GenerateVerilog(args, () => top.module, release = true, name = "SimpleL2CacheFinal", split = true)
}
