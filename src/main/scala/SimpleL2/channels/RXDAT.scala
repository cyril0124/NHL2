package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
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

    io.resp.valid          := io.rxdat.fire
    io.resp.bits.chiOpcode := io.rxdat.bits.opcode
    io.resp.bits.resp      := io.rxdat.bits.resp
    io.resp.bits.respErr   := io.rxdat.bits.respErr
    io.resp.bits.srcID     := io.rxdat.bits.srcID
    io.resp.bits.homeNID   := io.rxdat.bits.homeNID
    io.resp.bits.dbID      := io.rxdat.bits.dbID
    io.resp.bits.dataID    := io.rxdat.bits.dataID
    io.resp.bits.txnID     := io.rxdat.bits.txnID
    io.resp.bits.pCrdType  := DontCare

    /**
      * Data from CHI bus is possibly not obeying the order of the beat, so we need to reorder it. 
      * CHI provide dataID field to indicate the order of the data.
      */
    val firstData = io.rxdat.bits.dataID === "b00".U
    val lastData  = io.rxdat.bits.dataID === "b10".U
    val writeData = Mux(lastData, Cat(io.rxdat.bits.data, 0.U((beatBytes * 8).W)), Cat(0.U((beatBytes * 8).W), io.rxdat.bits.data))
    val writeMask = Mux(lastData, "b10".U, "b01".U)

    io.rxdat.ready              := io.toTempDS.write.ready
    io.toTempDS.write.valid     := io.rxdat.valid
    io.toTempDS.write.bits.data := writeData
    io.toTempDS.write.bits.mask := writeMask
    io.toTempDS.write.bits.idx  := io.rxdat.bits.txnID
    assert(!(io.rxdat.fire && !firstData && !lastData), "RXDAT dataID should be 00 or 10")

    LeakChecker(io.rxdat.valid, io.rxdat.fire, Some("RXDAT_valid"), maxCount = deadlockThreshold)
}

object RXDAT extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new RXDAT()(config), name = "RXDAT", split = false)
}
