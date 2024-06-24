package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class RXDAT()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxdat = Flipped(DecoupledIO(new CHIBundleDAT(chiBundleParams)))
        val resp  = ValidIO(new CHIRespBundle(chiBundleParams))
        val toTempDS = new Bundle {
            val dataWr = DecoupledIO(new TempDataWrite)
            val dataId = Input(UInt(dataIdBits.W))
        }
    })

    val first = io.rxdat.bits.dataID === "b00".U
    val last  = io.rxdat.bits.dataID === "b10".U

    io.resp.valid          := io.rxdat.fire && (first || last)
    io.resp.bits.chiOpcode := io.rxdat.bits.opcode
    io.resp.bits.resp      := io.rxdat.bits.resp
    io.resp.bits.respErr   := io.rxdat.bits.respErr
    io.resp.bits.dbID      := io.rxdat.bits.dbID
    io.resp.bits.txnID     := io.rxdat.bits.txnID
    io.resp.bits.last      := last
    io.resp.bits.pCrdType  := DontCare
    io.resp.bits.dataId    := io.toTempDS.dataWr.bits.dataId

    io.rxdat.ready                   := io.toTempDS.dataWr.ready
    io.toTempDS.dataWr.valid         := io.rxdat.valid && (first || last)
    io.toTempDS.dataWr.bits.beatData := io.rxdat.bits.data
    io.toTempDS.dataWr.bits.dataId   := Mux(first, io.toTempDS.dataId, RegEnable(io.toTempDS.dataId, first && io.rxdat.fire))
    io.toTempDS.dataWr.bits.wrMaskOH := Cat(last, first).asUInt
}

object RXDAT extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXDAT()(config), name = "RXDAT", split = false)
}
