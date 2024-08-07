package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._

class RespDataDestSinkC()(implicit p: Parameters) extends L2Bundle {
    val mshrId   = UInt(log2Ceil(nrMSHR).W)
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
    val wayOH    = UInt(ways.W)
    val isTempDS = Bool()
    val isDS     = Bool()
}

class SinkC()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val c    = Flipped(DecoupledIO(new TLBundleC(tlBundleParams)))
        val task = DecoupledIO(new TaskBundle)
        val resp = ValidIO(new TLRespBundle(tlBundleParams))

        /**
         * [[MSHR]] is permitted to cancel the unfired probe, hence the corresponding respDestMap entry should be freed as well. 
         */
        val respMapCancel = Flipped(DecoupledIO(UInt(mshrBits.W))) // from MissHandler

        /** 
         * Interact with [[MainPipe]], [[MainPipe]] will specify the ProbeAckData destination. 
         * ProbeAckData cannot be received without the resp data destination info sent from [[MainPipe]].
         */
        val respDest_s4 = Flipped(ValidIO(new RespDataDestSinkC)) // from MainPipe // TODO: Unify signal naming...

        /** Interact with [[DataStorage]] (for ReleaseData / ProbeAckData) */
        val dsWrite_s2 = DecoupledIO(new DSWrite)

        /** Interact with [[TempDataStorage]] (for ReleaseData / ProbeAckData) */
        val toTempDS = new Bundle {
            val write = DecoupledIO(new TempDataWrite)
        }

        val fromReqArb = new Bundle {
            val mayReadDS_s1    = Input(Bool())
            val willRefillDS_s1 = Input(Bool())
            val mayReadDS_s2    = Input(Bool())
            val willRefillDS_s2 = Input(Bool())
        }
        val toReqArb = new Bundle {
            val willWriteDS_s1 = Output(Bool())
            val willWriteDS_s2 = Output(Bool())
        }
    })

    println(s"[${this.getClass().toString()}] sinkcHasLatch:${sinkcHasLatch}")

    val c = if (sinkcHasLatch) {
        Queue(io.c, 1)
    } else {
        io.c
    }

    val blockDsWrite_s1    = WireInit(false.B)
    val (tag, set, offset) = parseAddress(c.bits.address)
    assert(offset === 0.U, "offset is not zero")

    val isRelease      = c.bits.opcode(1)
    val hasData        = c.bits.opcode(0)
    val isProbeAckData = !isRelease && hasData
    val isReleaseData  = isRelease && hasData

    /** beatCounter for multi-beat transactions like ReleaseData/PrbeAckData  */
    val beatCnt = RegInit(0.U(log2Ceil(nrBeat).W)) // TODO: parameterize
    val last    = beatCnt === (nrBeat - 1).U
    val first   = beatCnt === 0.U
    when(c.fire && hasData) {
        beatCnt := beatCnt + 1.U
    }
    assert(!(c.fire && !hasData && beatCnt === 1.U))

    /**
     * [[respDestMap]] is used to determine the destination of ProbeAckData, which can be chosen between [[TempDataStorage]] and [[DataStorage]].
     * This infomation is sent from [[MissHandler]]. 
     */
    val nrRespDestMapEntry = nrMSHR
    val respDestMap = RegInit(VecInit(Seq.fill(nrRespDestMapEntry)(0.U.asTypeOf(new Bundle {
        val valid    = Bool()
        val set      = UInt(setBits.W)
        val tag      = UInt(tagBits.W)
        val wayOH    = UInt(ways.W)
        val isTempDS = Bool()
        val isDS     = Bool()
    }))))
    val respMatchSetOH   = VecInit(respDestMap.map(_.set === set)).asUInt
    val respMatchTagOH   = VecInit(respDestMap.map(_.tag === tag)).asUInt
    val respValidOH      = VecInit(respDestMap.map(_.valid)).asUInt
    val respMatchOH      = respMatchSetOH & respMatchTagOH & respValidOH
    val respHasMatch     = respMatchOH.orR
    val respMatchEntry   = Mux1H(respMatchOH, respDestMap)
    val respDataToTempDS = respMatchEntry.isTempDS // true.B
    val respDataToDS     = respMatchEntry.isDS     // false.B
    dontTouch(respDataToTempDS)
    dontTouch(respDataToDS)
    when(io.respDest_s4.fire) {
        val entry = respDestMap(io.respDest_s4.bits.mshrId)
        entry.valid    := true.B
        entry.set      := io.respDest_s4.bits.set
        entry.tag      := io.respDest_s4.bits.tag
        entry.wayOH    := io.respDest_s4.bits.wayOH
        entry.isTempDS := io.respDest_s4.bits.isTempDS
        entry.isDS     := io.respDest_s4.bits.isDS
        // ! If then probe has been canceled by Release, this entry will still be valid.
        // assert(!entry.valid, "respDestMap[%d] is already valid! addr:0x%x", io.respDest_s4.bits.mshrId, Cat(io.respDest_s4.bits.tag, io.respDest_s4.bits.set, 0.U(6.W)))
    }

    io.respMapCancel.ready := true.B
    when(io.respMapCancel.fire) {
        val entry = respDestMap(io.respMapCancel.bits)
        entry.valid := false.B

        // assert(entry.valid)
        assert(!(io.respDest_s4.fire && io.respMapCancel.bits === io.respDest_s4.bits.mshrId), "conflict between alloc and cancel!")
    }

    /**
     * Send response to [[MSHR]], only for ProbeAck/ProbeAckData.
     * Release/ReleaseData is request, not response.
     */
    val resp      = WireInit(0.U.asTypeOf(Valid(chiselTypeOf(io.resp.bits))))
    val respValid = RegNext(resp.valid, false.B)     // opt for timing
    val respBits  = RegEnable(resp.bits, resp.valid) // opt for timing
    resp.valid       := c.fire && (first || last) && !isRelease
    resp.bits.opcode := c.bits.opcode
    resp.bits.param  := c.bits.param
    resp.bits.source := c.bits.source
    resp.bits.sink   := DontCare
    resp.bits.set    := set
    resp.bits.tag    := tag
    resp.bits.last   := hasData && last || !hasData
    io.resp.valid    := respValid
    io.resp.bits     := respBits

    /** 
     * Write response data into [[TempDataStorage]].
     * These data can be further written into [[DataStorage]] or bypass downstream to next level cache, which is dependt on how this Probe request is triggered.
     * For example, if this Probe request is triggered by a [[SinkA]](AcquirePerm), then the data will be written into [[DataStorage]].
     * If this Probe request is triggered by a [[RXSNP]], then the data will be bypassed to next level cache.
     * Further more, if this Probe request is triggered by a [[SinkA]](AcquireBlock), then the data will be written into both [[TempDataStorage]] or [[DataStorage]], 
     * where data in [[TempDataStoarge]] can be further used by [[SoruceD]].
     */
    io.toTempDS.write.valid     := c.fire && last && hasData && respDataToTempDS
    io.toTempDS.write.bits.data := Cat(c.bits.data, RegEnable(c.bits.data, c.fire))
    io.toTempDS.write.bits.idx  := OHToUInt(respMatchOH)

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val fire_s1 = c.fire && ((isReleaseData || isProbeAckData) && last || isRelease && !hasData)

    /**
     * If the incoming transaction is a Release/ReleaseData, we need to pack the transaction and send it to [[RequestArbiter]].
     * Otherwise, we can bypass the [[RequestArbiter]] and send the transaction(response) directly to [[MSHR]].
     */
    io.task.valid           := c.valid && (isReleaseData && last || isRelease && !hasData)
    io.task.bits            := DontCare
    io.task.bits.channel    := L2Channel.ChannelC
    io.task.bits.opcode     := c.bits.opcode
    io.task.bits.param      := c.bits.param
    io.task.bits.source     := c.bits.source
    io.task.bits.isPrefetch := false.B
    io.task.bits.set        := set
    io.task.bits.tag        := tag

    /**
     * ReleaseData is allowed to write data into [[DataStorage]] directly since L2Cache is inclusive.
     * ReleaseData is always hit.
     * ProbeAckData can also write data into [[DataStorage]] depending on respDataToDS(control by MainPipe).
     */
    val dsWrite_s1 = WireInit(0.U.asTypeOf(Valid(new DSWrite)))
    dsWrite_s1.valid      := c.fire && last && (isReleaseData || isProbeAckData && respDataToDS)
    dsWrite_s1.bits.data  := Cat(c.bits.data, RegEnable(c.bits.data, c.fire))
    dsWrite_s1.bits.set   := set
    dsWrite_s1.bits.wayOH := respMatchEntry.wayOH // For ReleaseData, wayOH is provided in MainPipe stage 3

    io.toReqArb.willWriteDS_s1 := c.valid && last && hasData

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    io.dsWrite_s2.valid      := RegNext(dsWrite_s1.valid, false.B)
    io.dsWrite_s2.bits.data  := RegEnable(dsWrite_s1.bits.data, 0.U, fire_s1)
    io.dsWrite_s2.bits.set   := RegEnable(dsWrite_s1.bits.set, 0.U, fire_s1)
    io.dsWrite_s2.bits.wayOH := RegEnable(dsWrite_s1.bits.wayOH, 0.U, fire_s1) // For ReleaseData, wayOH is provided in MainPipe stage 3
    assert(!(io.dsWrite_s2.valid && !io.dsWrite_s2.ready), "sinkC dsWrite_s2 should always ready!")

    io.toReqArb.willWriteDS_s2 := RegNext(fire_s1, false.B)

    // TODO: ReleaseData does not need to Replay, so it is necessary to make sure that when Release is fired and the SourceD is prepared to receive the ReleaseAck.
    c.ready := Mux(
        isRelease,
        Mux(hasData, !blockDsWrite_s1 && io.task.ready || first, io.task.ready),                                                                                    // ReleaseData / Release
        hasData && ((respDataToTempDS && io.toTempDS.write.ready || !respDataToTempDS) && (respDataToDS && !blockDsWrite_s1 || !respDataToDS) || first) || !hasData // ProbeAckData / ProbeAck
    )

    blockDsWrite_s1 := io.fromReqArb.mayReadDS_s1 || io.fromReqArb.willRefillDS_s1 || io.fromReqArb.mayReadDS_s2 || io.fromReqArb.willRefillDS_s2

    // @formatter:off
    assert(!(c.fire && hasData && c.bits.size =/= log2Ceil(blockBytes).U))
    assert(!(c.fire && hasData && last && !io.toTempDS.write.fire && !io.dsWrite_s2.valid && !dsWrite_s1.valid),"SinkC data is not written into TempDataStorage or DataStorage")
    LeakChecker(c.valid, c.fire, Some("SinkC_io_c_valid"), maxCount = deadlockThreshold)

    when(io.dsWrite_s2.fire || io.toTempDS.write.fire) {
        val _isRelease = Mux(io.toTempDS.write.fire, isRelease, RegNext(isRelease))
        val _isProbeAckData = Mux(io.toTempDS.write.fire, isProbeAckData, RegNext(isProbeAckData))
        val _respMatchOH = Mux(io.toTempDS.write.fire, respMatchOH, RegNext(respMatchOH)).asTypeOf(UInt(nrRespDestMapEntry.W))
        val _respMatchEntry = Mux(io.toTempDS.write.fire, respMatchEntry, RegNext(respMatchEntry))
        val _address = Mux(io.toTempDS.write.fire, c.bits.address, RegNext(c.bits.address))
        val _opcode = Mux(io.toTempDS.write.fire, c.bits.opcode, RegNext(c.bits.opcode))
        val _param = Mux(io.toTempDS.write.fire, c.bits.param, RegNext(c.bits.param))
        
        when(_isProbeAckData) {
            assert(
                !(!_isRelease && !_respMatchEntry.valid),
                "ProbeAckData write data does not match any valid entry! address:0x%x opcode:%d param:%d dsWrite_s2.fire:%d toTempDS.write.fire:%d",
                _address,
                _opcode,
                _param,
                io.dsWrite_s2.fire,
                io.toTempDS.write.fire
            )
        }

        assert(PopCount(_respMatchOH) <= 1.U, "ProbeAckData match multiple entries of respDestMap! addr => 0x%x respMatchOH: 0b%b", _address, _respMatchOH)
    }
    // @formatter:on
}

object SinkC extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkC()(config), name = "SinkC", split = false)
}
