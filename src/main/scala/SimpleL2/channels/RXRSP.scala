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

    val rxrsp = WireInit(0.U.asTypeOf(io.rxrsp))

    if (optParam.rxrspHasLatch) {
        rxrsp <> Queue(io.rxrsp, 1)
    } else {
        rxrsp <> io.rxrsp
    }

    rxrsp.ready := true.B

    io.resp.valid          := rxrsp.valid
    io.resp.bits.chiOpcode := rxrsp.bits.opcode
    io.resp.bits.pCrdType  := rxrsp.bits.pCrdType
    io.resp.bits.resp      := rxrsp.bits.resp
    io.resp.bits.respErr   := rxrsp.bits.respErr
    io.resp.bits.srcID     := rxrsp.bits.srcID
    io.resp.bits.dbID      := rxrsp.bits.dbID
    io.resp.bits.txnID     := rxrsp.bits.txnID
    io.resp.bits.last      := true.B
}

object RXRSP extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXRSP()(config), name = "RXRSP", split = false)
}
