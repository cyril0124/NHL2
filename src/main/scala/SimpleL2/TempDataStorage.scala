package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqBoolBitwiseOps

class TempDataWrite()(implicit p: Parameters) extends L2Bundle {
    val valid = Input(Bool())
    val ready = Output(Bool())
    val data  = Input(UInt(dataBits.W))

    def fire = valid && ready
}

class TempDataRead()(implicit p: Parameters) extends L2Bundle {
    val valid  = Input(Bool())
    val ready  = Output(Bool())
    val dataId = Input(UInt(dataIdBits.W))

    def fire = valid && ready
}

class TempDataEntry()(implicit p: Parameters) extends L2Bundle {
    // TODO: ECC
    val data = UInt(dataBits.W)
}

object TempDataEntry {
    def apply(data: UInt, ecc: UInt = 0.U)(implicit p: Parameters) = {
        val tempDataEntry = Wire(new TempDataEntry)
        tempDataEntry.data := data
        // tempDataEntry.ecc := ecc // TODO:
        tempDataEntry
    }
}

class TempDataStorage()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val fromDS = new Bundle {
            val dsResp_ds4 = Flipped(ValidIO(new DSResp))
            val dsDest_ds4 = Input(DataDestination())
        }

        val fromSourceD = new Bundle {
            val read = new TempDataRead()
            val resp = ValidIO(UInt(dataBits.W))
        }

        val toSourceD = new Bundle {
            val dataOut = DecoupledIO(new DataSelectorOut)
            val dataId  = Output(UInt(dataIdBits.W))
        }

        val flushEntry = Flipped(ValidIO(UInt(dataIdBits.W)))
    })

    io <> DontCare

    val valids      = RegInit(VecInit(Seq.fill(nrTempDataEntry)(false.B)))
    val freeDataIdx = PriorityEncoder(~valids.asUInt)
    val full        = valids.andR
    dontTouch(valids)

    val dataSel = Module(new DataSelector)
    dataSel.io.dsResp_ds4      := io.fromDS.dsResp_ds4
    io.toSourceD.dataOut.valid := dataSel.io.dataOut.valid
    io.toSourceD.dataOut.bits  := dataSel.io.dataOut.bits
    io.toSourceD.dataId        := freeDataIdx

    val isSourceD      = io.fromDS.dsDest_ds4 === DataDestination.SourceD
    val stallOnSourceD = isSourceD && !io.toSourceD.dataOut.ready && io.toSourceD.dataOut.valid && io.toSourceD.dataOut.bits.last
    val wrTempDS       = io.fromDS.dsDest_ds4 === DataDestination.TempDataStorage && io.fromDS.dsResp_ds4.valid
    val wen            = stallOnSourceD || wrTempDS
    val widx           = freeDataIdx
    val ren            = io.fromSourceD.read.fire
    val ridx           = io.fromSourceD.read.dataId

    val wrData = MuxCase(
        0.U,
        Seq(
            stallOnSourceD -> io.fromDS.dsResp_ds4.bits.data,
            wrTempDS       -> io.fromDS.dsResp_ds4.bits.data
        )
    )

    io.fromSourceD.read.ready := true.B // TODO: arbiter

    val tempDataSRAM = Module(
        new SRAMTemplate(
            new TempDataEntry,
            nrTempDataEntry,
            1,
            singlePort = true,
            hasMbist = false /* TODO */,
            hasShareBus = false /* TDOO */,
            hasClkGate = enableClockGate
            // parentName = parentName + "tempData_" /* TODO */
        )
    )

    tempDataSRAM.io.w.req.valid := wen
    tempDataSRAM.io.w(
        valid = wen,
        data = TempDataEntry(wrData),
        setIdx = widx,
        waymask = 1.U
    )
    assert(!(tempDataSRAM.io.w.req.valid && !tempDataSRAM.io.w.req.ready), "tempDataSRAM should always be ready for write")
    assert(!(wen && full), "try to write to a full tempDataSRAM valids:0x%x", valids.asUInt)

    tempDataSRAM.io.r.req.valid       := ren
    tempDataSRAM.io.r.req.bits.setIdx := ridx
    assert(!(tempDataSRAM.io.r.req.valid && !tempDataSRAM.io.r.req.ready), "tempDataSRAM should always be ready for read")
    assert(!(ren && !valids(ridx)), "try to read from an empty tempDataSRAM valids:0x%x ridx:0x%x", valids.asUInt, ridx)

    val rdata = tempDataSRAM.io.r.resp.data(0)
    io.fromSourceD.resp.valid := RegNext(ren, false.B)
    io.fromSourceD.resp.bits  := rdata.data

    when(tempDataSRAM.io.w.req.fire) {
        valids(freeDataIdx) := true.B
    }

    when(io.flushEntry.valid) {
        valids(io.flushEntry.bits) := false.B
    }

    assert(
        !(io.flushEntry.valid && tempDataSRAM.io.w.req.fire && freeDataIdx === io.flushEntry.bits),
        "io.flushEntry should not be valid when there is a write operation on tempDataSRAM"
    )

    dontTouch(io)
}

object TempDataStorage extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new TempDataStorage()(config), name = "TempDataStorage", split = true)
}
