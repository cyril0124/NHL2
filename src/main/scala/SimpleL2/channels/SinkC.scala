package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class RespDataDestSinkC()(implicit p: Parameters) extends L2Bundle {
    val mshrId   = UInt(log2Ceil(nrMSHR).W)
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
    val dataId   = UInt(dataIdBits.W)
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
         * Interact with [[MissHandler]], [[MissHandler]] will specify the ProbeAckData destination. 
         * ProbeAckData cannot be received without the resp data destination info sent from [[MissHandler]].
         */
        val respDest_s4 = Flipped(ValidIO(new RespDataDestSinkC))

        /** Interact with [[DataStorage]] (for ReleaseData / ProbeAckData) */
        val dsWrite_s2 = DecoupledIO(new DSWrite)

        /** Interact with [[TempDataStorage]] (for ReleaseData / ProbeAckData) */
        val toTempDS = new Bundle {
            val write = DecoupledIO(new TempDataWrite)
        }
    })

    io      <> DontCare
    io.c    <> DontCare
    io.task <> DontCare

    val (tag, set, offset) = parseAddress(io.c.bits.address)
    assert(offset === 0.U, "offset is not zero")

    val isRelease     = io.c.bits.opcode(1)
    val hasData       = io.c.bits.opcode(0)
    val isReleaseData = isRelease && hasData

    /** beatCounter for multi-beat transactions like ReleaseData/PrbeAckData  */
    val beatCnt = RegInit(0.U(log2Ceil(nrBeat).W)) // TODO: parameterize
    val last    = beatCnt === (nrBeat - 1).U
    val first   = beatCnt === 0.U
    when(io.c.fire && hasData) {
        beatCnt := beatCnt + 1.U
    }
    assert(!(io.c.fire && !hasData && beatCnt === 1.U))

    /**
     * [[respDestMap]] is used to determine the destination of ProbeAckData, which can be chosen between [[TempDataStorage]] and [[DataStorage]].
     * This infomation is sent from [[MissHandler]]. 
     */
    val respDestMap = RegInit(VecInit(Seq.fill(nrMSHR)(0.U.asTypeOf(new Bundle {
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
        // assert(!entry.valid, "respDestMap[%d] is already valid!", io.respDest.bits.mshrId)
    }
    respDestMap.zip(respMatchOH.asBools).zipWithIndex.foreach { case ((destMap, en), i) =>
        when(io.c.fire && !isRelease && hasData && last && en) {
            destMap.valid := false.B
            assert(respHasMatch, "ProbeAckData does not match any entry of respDestMap! addr => 0x%x set => 0x%x tag => 0x%x", io.c.bits.address, set, tag)
            assert(
                PopCount(respMatchOH) <= 1.U,
                "ProbeAckData match multiple entries of respDestMap! addr => 0x%x set => 0x%x tag => 0x%x respMatchOH: 0b%b",
                io.c.bits.address,
                set,
                tag,
                respMatchOH
            )
            assert(destMap.valid, s"ProbeAckData match an empty entry! entry_idx => ${i} addr => 0x%x set => 0x%x tag => 0x%x", io.c.bits.address, set, tag)
        }.elsewhen(io.c.fire && !isRelease && !hasData && en) {
            destMap.valid := false.B
        }
    }

    /**
     * If the incoming transaction is a Release/ReleaseData, we need to pack the transaction and send it to [[RequestArbiter]].
     * Otherwise, we can bypass the [[RequestArbiter]] and send the transaction(response) directly to [[MSHR]].
     */
    io.task.valid           := io.c.valid && first && isRelease
    io.task.bits.channel    := L2Channel.ChannelC
    io.task.bits.opcode     := io.c.bits.opcode
    io.task.bits.param      := io.c.bits.param
    io.task.bits.source     := io.c.bits.source
    io.task.bits.isPrefetch := false.B
    io.task.bits.set        := set
    io.task.bits.tag        := tag

    /**
     * ReleaseData is allowed to write data into [[DataStorage]] directly since L2Cache is inclusive.
     * ReleaseData is always hit.
     * ProbeAckData can also write data into [[DataStorage]] depending on respDataToDS(control by MainPipe).
     */
    io.dsWrite_s2.valid      := io.c.fire && last && (isReleaseData || !isRelease && hasData && respDataToDS)
    io.dsWrite_s2.bits.data  := Cat(io.c.bits.data, RegEnable(io.c.bits.data, io.c.fire))
    io.dsWrite_s2.bits.set   := set
    io.dsWrite_s2.bits.wayOH := respMatchEntry.wayOH // For ReleaseData, wayOH is provided in MainPipe stage 3

    /**
     * Send response to [[MSHR]], only for ProbeAck/ProbeAckData.
     * Release/ReleaseData is request, not response.
     */
    io.resp.valid       := io.c.fire && (first || last) && !isRelease
    io.resp.bits.opcode := io.c.bits.opcode
    io.resp.bits.param  := io.c.bits.param
    io.resp.bits.source := io.c.bits.source
    io.resp.bits.sink   := DontCare
    io.resp.bits.set    := set
    io.resp.bits.tag    := tag
    io.resp.bits.last   := hasData && last || !hasData

    /** 
     * Write response data into [[TempDataStorage]].
     * These data can be further written into [[DataStorage]] or bypass downstream to next level cache, which is dependt on how this Probe request is triggered.
     * For example, if this Probe request is triggered by a [[SinkA]](AcquirePerm), then the data will be written into [[DataStorage]].
     * If this Probe request is triggered by a [[RXSNP]], then the data will be bypassed to next level cache.
     * Further more, if this Probe request is triggered by a [[SinkA]](AcquireBlock), then the data will be written into both [[TempDataStorage]] or [[DataStorage]], 
     * where data in [[TempDataStoarge]] can be further used by [[SoruceD]].
     */
    io.toTempDS.write.valid     := io.c.fire && last && hasData && !isRelease && respDataToTempDS
    io.toTempDS.write.bits.data := Cat(io.c.bits.data, RegEnable(io.c.bits.data, io.c.fire))
    io.toTempDS.write.bits.idx  := OHToUInt(respMatchOH)

    io.c.ready := Mux(isRelease, Mux(hasData, io.dsWrite_s2.ready && io.task.ready, io.task.ready) || !first, hasData && io.toTempDS.write.ready || !hasData)

    assert(!(io.c.fire && hasData && io.c.bits.size =/= log2Ceil(blockBytes).U))
    assert(!(io.c.fire && hasData && last && !io.toTempDS.write.fire && !io.dsWrite_s2.valid), "SinkC data is not written into TempDataStorage or DataStorage")

    dontTouch(io)
}

object SinkC extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkC()(config), name = "SinkC", split = false)
}
