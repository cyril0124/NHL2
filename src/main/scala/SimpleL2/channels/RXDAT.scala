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
            val write = DecoupledIO(new TempDataWrite)
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

    io.rxdat.ready              := io.toTempDS.write.ready
    io.toTempDS.write.valid     := io.rxdat.valid && last
    io.toTempDS.write.bits.data := Cat(io.rxdat.bits.data, RegEnable(io.rxdat.bits.data, io.rxdat.fire))
    io.toTempDS.write.bits.idx  := io.rxdat.bits.txnID
}

object RXDAT extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXDAT()(config), name = "RXDAT", split = false)
}
