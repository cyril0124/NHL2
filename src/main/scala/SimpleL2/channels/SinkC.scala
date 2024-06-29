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
        val respDest = Flipped(ValidIO(new RespDataDestSinkC))

        /** Interact with [[DataStorage]] (for ReleaseData) */
        val dsWrite_s2 = DecoupledIO(new DSWrite)

        /** Interact with [[TempDataStorage]] (for ProbeAckData) */
        val toTempDS = new Bundle {
            val dataWr = DecoupledIO(new TempDataWrite)
            val dataId = Input(UInt(dataIdBits.W))
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
        val isTempDS = Bool()
        val isDS     = Bool()
    }))))
    val respMatchSetOH   = VecInit(respDestMap.map(_.set === set)).asUInt
    val respMatchTagOH   = VecInit(respDestMap.map(_.tag === tag)).asUInt
    val respMatchOH      = respMatchSetOH & respMatchTagOH
    val respHasMatch     = respMatchOH.orR
    val respDataToTempDS = Mux1H(respMatchOH, respDestMap).isTempDS // true.B
    val respDataToDS     = Mux1H(respMatchOH, respDestMap).isDS     // false.B
    when(io.respDest.fire) {
        val entry = respDestMap(io.respDest.bits.mshrId)
        entry.valid    := true.B
        entry.set      := io.respDest.bits.set
        entry.tag      := io.respDest.bits.tag
        entry.isTempDS := io.respDest.bits.isTempDS
        entry.isDS     := io.respDest.bits.isDS
        assert(!entry.valid, "respDestMap[%d] is already valid!", io.respDest.bits.mshrId)
    }
    respDestMap.zip(respMatchOH.asBools).zipWithIndex.foreach { case ((destMap, en), i) =>
        when(io.c.fire && !isRelease && hasData && last && en) {
            destMap.valid := false.B
            assert(respHasMatch, "ProbeAckData does not match any entry of respDestMap! addr => 0x%x set => 0x%x tag => 0x%x", io.c.bits.address, set, tag)
            assert(PopCount(respMatchOH) <= 1.U, "ProbeAckData match multiple entries of respDestMap! addr => 0x%x set => 0x%x tag => 0x%x", io.c.bits.address, set, tag)
            assert(destMap.valid, s"ProbeAckData match an empty entry! entry_idx => ${i} addr => 0x%x set => 0x%x tag => 0x%x", io.c.bits.address, set, tag)
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
    io.task.bits.dataId     := DontCare
    io.task.bits.set        := set
    io.task.bits.tag        := tag

    /**
      * ReleaseData is allowed to write data into [[DataStorage]] directly since L2Cache is inclusive.
      * ReleaseData is always hit.
      */
    io.dsWrite_s2.valid     := io.c.fire && last && (isReleaseData || !isRelease && hasData && respDataToDS)
    io.dsWrite_s2.bits.data := Cat(io.c.bits.data, RegEnable(io.c.bits.data, io.c.fire))
    io.dsWrite_s2.bits.set  := set
    io.dsWrite_s2.bits.way  := DontCare

    /**
      * Send response to [[MSHR]], only for ProbeAck/ProbeAckData.
      * Release/ReleaseData is request, not response.
      */
    io.resp.valid       := io.c.fire && (first || last) && !isRelease
    io.resp.bits.opcode := io.c.bits.opcode
    io.resp.bits.param  := io.c.bits.param
    io.resp.bits.sink   := DontCare
    io.resp.bits.set    := set
    io.resp.bits.tag    := tag
    io.resp.bits.dataId := io.toTempDS.dataId
    io.resp.bits.last   := hasData && last || !hasData

    /** 
      * Write response data into [[TempDataStorage]].
      * These data can be further written into [[DataStorage]] or bypass downstream to next level cache, which is dependt on how this Probe request is triggered.
      * For example, if this Probe request is triggered by a [[SinkA]], then the data will be written into [[DataStorage]].
      * If this Probe request is triggered by a [[RXSNP]], then the data will be bypassed to next level cache.
      */
    io.toTempDS.dataWr.valid         := io.c.fire && (first || last) && hasData && !isRelease && respDataToTempDS
    io.toTempDS.dataWr.bits.beatData := io.c.bits.data
    io.toTempDS.dataWr.bits.dataId   := Mux(first, io.toTempDS.dataId, RegEnable(io.toTempDS.dataId, first && io.c.fire))
    io.toTempDS.dataWr.bits.wrMaskOH := Cat(last, first).asUInt

    io.c.ready := Mux(isRelease, Mux(hasData, io.dsWrite_s2.ready && io.task.ready, io.task.ready) || !first, hasData && io.toTempDS.dataWr.ready || !hasData)

    assert(!(io.c.fire && hasData && io.c.bits.size =/= log2Ceil(blockBytes).U))
    assert(!(io.c.fire && hasData && last && !io.toTempDS.dataWr.fire && !io.dsWrite_s2.valid), "SinkC data is not written into TempDataStorage or DataStorage")

    dontTouch(io)
}

object SinkC extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkC()(config), name = "SinkC", split = false)
}
