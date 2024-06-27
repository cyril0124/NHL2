package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import chisel3.experimental.Param
import java.lang.reflect.Parameter
import utest.ufansi.Bold

class DataSelectorOut()(implicit p: Parameters) extends L2Bundle {
    val data = UInt((beatBytes * 8).W)
    val last = Bool()
}

class DataSelector()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val dataIn  = Flipped(ValidIO(UInt(dataBits.W)))
        val dataOut = ValidIO(new DataSelectorOut)
    })

    val beatCnt = RegInit(0.U(1.W))
    when(io.dataOut.valid) {
        beatCnt := beatCnt + 1.U
    }

    val dsRespValid  = io.dataIn.valid || RegNext(io.dataIn.valid, false.B)
    val selectedData = Mux(beatCnt === 1.U, io.dataIn.bits(511, 256), io.dataIn.bits(255, 0))
    dontTouch(selectedData)

    io.dataOut.bits.last := beatCnt === 1.U
    io.dataOut.bits.data := Mux(dsRespValid, selectedData, 0.U /* TODO: */ )
    io.dataOut.valid     := dsRespValid
}
