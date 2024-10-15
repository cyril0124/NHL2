package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
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
    io.resp.bits.homeNID   := DontCare
    io.resp.bits.dbID      := rxrsp.bits.dbID
    io.resp.bits.dataID    := DontCare
    io.resp.bits.txnID     := rxrsp.bits.txnID
}

object RXRSP extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new RXRSP()(config), name = "RXRSP", split = false)
}
