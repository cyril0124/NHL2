package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

class RXRSP()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxrsp = Flipped(DecoupledIO(new CHIBundleRSP(chiBundleParams)))
        val resp  = ValidIO(new CHIRespBundle(chiBundleParams))
    })

    io.rxrsp.ready := true.B

    io.resp.valid          := io.rxrsp.valid
    io.resp.bits.chiOpcode := io.rxrsp.bits.opcode
    io.resp.bits.pCrdType  := io.rxrsp.bits.pCrdType
    io.resp.bits.resp      := io.rxrsp.bits.resp
    io.resp.bits.respErr   := io.rxrsp.bits.respErr
    io.resp.bits.dbID      := io.rxrsp.bits.dbID
    io.resp.bits.txnID     := io.rxrsp.bits.txnID
    io.resp.bits.last      := true.B
    io.resp.bits.dataId    := DontCare
}

object RXRSP extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXRSP()(config), name = "RXRSP", split = false)
}
