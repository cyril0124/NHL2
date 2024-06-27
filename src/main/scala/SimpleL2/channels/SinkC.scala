package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class SinkC()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val c    = Flipped(DecoupledIO(new TLBundleC(tlBundleParams)))
        val task = DecoupledIO(new TaskBundle)
        val resp = ValidIO(new TLRespBundle(tlBundleParams))

        /** Interact with [[DataStorage]] (for ReleaseData) */
        val toDS = new Bundle {
            val dsWrite_s2 = CreditIO(new DSWrite)
        }

        /** Interact with [[TempDataStorage]] (for ProbeAckData) */
        val toTempDS = new Bundle {
            val dataWr = DecoupledIO(new TempDataWrite)
            val dataId = Input(UInt(dataIdBits.W))
        }
    })

    io      <> DontCare
    io.c    <> DontCare
    io.task <> DontCare

    val isRelease = io.c.bits.opcode(1)
    val hasData   = io.c.bits.opcode(0)

    // TODO: parameterize
    val beatCnt = RegInit(0.U(log2Ceil(nrBeat).W))
    val last    = beatCnt === (nrBeat - 1).U
    val first   = beatCnt === 0.U
    when(io.c.fire && hasData) {
        beatCnt := beatCnt + 1.U
    }
    assert(!(io.c.fire && !hasData && beatCnt === 1.U))

    val (tag, set, offset) = parseAddress(io.c.bits.address)
    assert(offset === 0.U, "offset is not zero")

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
    val dsWrCnt = RegInit(0.U(1.W))
    when(io.toDS.dsWrite_s2.crdv && !io.toDS.dsWrite_s2.valid) {
        dsWrCnt := dsWrCnt + 1.U
        assert(dsWrCnt === 0.U)
    }.elsewhen(!io.toDS.dsWrite_s2.crdv && io.toDS.dsWrite_s2.valid) {
        dsWrCnt := dsWrCnt - 1.U
        assert(dsWrCnt === 1.U)
    }
    io.toDS.dsWrite_s2.valid     := io.c.fire && last && isRelease && hasData
    io.toDS.dsWrite_s2.bits.data := Cat(io.c.bits.data, RegEnable(io.c.bits.data, io.c.fire))
    io.toDS.dsWrite_s2.bits.set  := set
    io.toDS.dsWrite_s2.bits.way  := DontCare

    /**
      * Send response to [[MSHR]], only for ProbeAck/ProbeAckData.
      * Release/ReleaseData is request, not response.
      */
    io.resp.valid       := !isRelease && io.c.fire && (first || last)
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
    io.toTempDS.dataWr.valid         := io.c.fire && (first || last) && hasData
    io.toTempDS.dataWr.bits.beatData := io.c.bits.data
    io.toTempDS.dataWr.bits.dataId   := Mux(first, io.toTempDS.dataId, RegEnable(io.toTempDS.dataId, first && io.c.fire))
    io.toTempDS.dataWr.bits.wrMaskOH := Cat(last, first).asUInt

    io.c.ready := Mux(isRelease, Mux(hasData, dsWrCnt === 1.U && io.task.ready, io.task.ready) || !first, hasData && io.toTempDS.dataWr.ready || !hasData)

    assert(!(io.c.fire && hasData && io.c.bits.size =/= log2Ceil(blockBytes).U))
    // assert(!(io.c.fire && isRelease && !hasData), "Release request must have data")

    dontTouch(io)
}

object SinkC extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new SinkC()(config), name = "SinkC", split = false)
}
