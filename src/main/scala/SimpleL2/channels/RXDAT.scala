package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class RXDAT()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxdat = Flipped(DecoupledIO(new CHIBundleDAT(chiBundleParams)))
        val resp  = ValidIO(new CHIRespBundle(chiBundleParams))
        val toTempDS = new Bundle {
            val write = DecoupledIO(new TempDataWrite)
        }
    })

    val beatCnt = RegInit(0.U(log2Ceil(nrBeat).W))
    val last    = beatCnt === (nrBeat - 1).U
    val first   = beatCnt === 0.U
    when(io.rxdat.fire) {
        when(beatCnt === (nrBeat - 1).U) {
            beatCnt := 0.U
        }.otherwise {
            beatCnt := beatCnt + 1.U
        }
    }

    io.resp.valid          := io.rxdat.fire && (first || last)
    io.resp.bits.chiOpcode := io.rxdat.bits.opcode
    io.resp.bits.resp      := io.rxdat.bits.resp
    io.resp.bits.respErr   := io.rxdat.bits.respErr
    io.resp.bits.srcID     := io.rxdat.bits.srcID
    io.resp.bits.homeNID   := io.rxdat.bits.homeNID
    io.resp.bits.dbID      := io.rxdat.bits.dbID
    io.resp.bits.txnID     := io.rxdat.bits.txnID
    io.resp.bits.last      := last
    io.resp.bits.pCrdType  := DontCare

    /**
      * Data from CHI bus is possibly not obeying the order of the beat, so we need to reorder it. 
      * CHI provide dataID field to indicate the order of the data.
      */
    val firstData = io.rxdat.bits.dataID === "b00".U
    val lastData  = io.rxdat.bits.dataID === "b10".U
    val tmpData   = RegEnable(io.rxdat.bits.data, io.rxdat.fire)
    val writeData = Mux(lastData, Cat(io.rxdat.bits.data, tmpData), Cat(tmpData, io.rxdat.bits.data))

    io.rxdat.ready              := io.toTempDS.write.ready
    io.toTempDS.write.valid     := io.rxdat.valid && last
    io.toTempDS.write.bits.data := writeData
    io.toTempDS.write.bits.idx  := io.rxdat.bits.txnID
    assert(!(io.rxdat.fire && !firstData && !lastData), "RXDAT dataID should be 00 or 10")

    LeakChecker(io.rxdat.valid, io.rxdat.fire, Some("RXDAT_valid"), maxCount = deadlockThreshold)
}

object RXDAT extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXDAT()(config), name = "RXDAT", split = false)
}
