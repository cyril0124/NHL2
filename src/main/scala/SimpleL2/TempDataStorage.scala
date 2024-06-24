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
    val beatData = UInt((beatBytes * 8).W)
    val dataId   = UInt(dataIdBits.W)
    val wrMaskOH = UInt(2.W)
}

class TempDataRead()(implicit p: Parameters) extends L2Bundle {
    val dataId = Input(UInt(dataIdBits.W))
}

class TempDataEntry()(implicit p: Parameters) extends L2Bundle {
    // TODO: ECC
    val beatData = UInt((beatBytes * 8).W)
}

object TempDataEntry {
    def apply(beatData: UInt, ecc: UInt = 0.U)(implicit p: Parameters) = {
        val tempDataEntry = Wire(new TempDataEntry)
        tempDataEntry.beatData := beatData
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
            val read = Flipped(DecoupledIO(new TempDataRead()))
            val resp = ValidIO(UInt(dataBits.W))
        }

        val fromRXDAT = new Bundle {
            val write = Flipped(DecoupledIO(new TempDataWrite))
        }

        val toSourceD = new Bundle {
            val dataOut = DecoupledIO(new DataSelectorOut)
        }

        val freeDataId = Output(UInt(dataIdBits.W))
        val flushEntry = Flipped(ValidIO(UInt(dataIdBits.W)))
    })

    io <> DontCare

    val valids      = RegInit(VecInit(Seq.fill(nrTempDataEntry)(VecInit(Seq.fill(2)(false.B)))))
    val freeDataIdx = PriorityEncoder(~VecInit(valids.map(_.orR)).asUInt)
    val full        = valids.map(_.orR).andR
    dontTouch(valids)

    /** split 512-bits of data into two 256-bits data */
    val dataSel = Module(new DataSelector)
    dataSel.io.dsResp_ds4      := io.fromDS.dsResp_ds4
    io.toSourceD.dataOut.valid := dataSel.io.dataOut.valid
    io.toSourceD.dataOut.bits  := dataSel.io.dataOut.bits
    io.freeDataId              := freeDataIdx

    val isSourceD      = io.fromDS.dsDest_ds4 === DataDestination.SourceD
    val stallOnSourceD = isSourceD && !io.toSourceD.dataOut.ready && io.toSourceD.dataOut.valid && io.toSourceD.dataOut.bits.last
    val wen_sourceD    = stallOnSourceD
    val wen_ds         = io.fromDS.dsDest_ds4 === DataDestination.TempDataStorage && io.fromDS.dsResp_ds4.valid
    val wen_rxdat      = io.fromRXDAT.write.fire

    val wen      = wen_sourceD || wen_ds || wen_rxdat
    val wrIdx    = Mux(io.fromRXDAT.write.valid, io.fromRXDAT.write.bits.dataId, freeDataIdx)                               // Mux(io.fromDS.dsResp_ds4.valid, freeDataIdx, DontCare) // TODO:
    val wrMaskOH = MuxCase("b00".U, Seq((wen_sourceD || wen_ds) -> "b11".U, wen_rxdat -> io.fromRXDAT.write.bits.wrMaskOH)) // TODO:
    val ren      = io.fromSourceD.read.fire
    val rdIdx    = io.fromSourceD.read.bits.dataId
    assert(!(wen && !wrMaskOH.orR), "wen but wrMaskOH is 0b00")

    val wrData = MuxCase(
        0.U,
        Seq(
            (wen_sourceD || wen_ds) -> io.fromDS.dsResp_ds4.bits.data
        )
    )

    /** write data into internal SRAM */
    val nrBeat = 2
    val tempDataSRAMs = Seq.fill(nrBeat) {
        Module(
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
    }

    val rdDatas = WireInit(0.U.asTypeOf(Vec(2, new TempDataEntry)))
    tempDataSRAMs.zipWithIndex.foreach { case (sram, i) =>
        if (i != 0) require(i == 1)

        sram.io.w(
            valid = wen && wrMaskOH(i),
            data = MuxCase(
                0.U.asTypeOf(new TempDataEntry),
                Seq(
                    (wen_sourceD || wen_ds) -> TempDataEntry(wrData(255 * i + 255, i * 256)),
                    wen_rxdat               -> TempDataEntry(io.fromRXDAT.write.bits.beatData)
                )
            ),
            setIdx = Mux(wen_rxdat, if (i == 0) wrIdx else RegEnable(wrIdx, false.B, wen), wrIdx),
            waymask = 1.U
        )
        assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "tempDataSRAM should always be ready for write")
        assert(!(wen && wrMaskOH(i) && valids(sram.io.w.req.bits.setIdx)(i)), s"try to write to an valid entry setIdx => %d i => ${i}", sram.io.w.req.bits.setIdx)

        when(sram.io.w.req.fire && wrMaskOH(i)) {
            if (i == 0) {
                valids(freeDataIdx)(i) := true.B
            } else {
                valids(RegEnable(freeDataIdx, 0.U, wen))(i) := true.B
            }
        }

        sram.io.r.req.valid       := ren
        sram.io.r.req.bits.setIdx := rdIdx
        rdDatas(i)                := sram.io.r.resp.data(0)
        assert(!(sram.io.r.req.valid && !sram.io.r.req.ready), "tempDataSRAM should always be ready for read")
        assert(!(ren && !valids(rdIdx).orR), "try to read from an empty tempDataSRAM valids:0x%x rdIdx:0x%x", valids.asUInt, rdIdx)
    }
    assert(!(wen && full), "try to write to a full tempDataSRAM valids:0x%x", valids.asUInt)

    io.fromSourceD.read.ready := true.B // TODO: arbiter
    io.fromSourceD.resp.valid := RegNext(ren, false.B)
    io.fromSourceD.resp.bits  := rdDatas.asUInt

    io.fromRXDAT.write.ready := true.B // TODO: arbiter

    /** flush data SRAM entry of [[TempDataStorage]] */
    when(io.flushEntry.valid) {
        valids(io.flushEntry.bits).map(_ := false.B)
    }

    assert(
        !(io.flushEntry.valid && tempDataSRAMs.map(_.io.w.req.fire).orR && freeDataIdx === io.flushEntry.bits),
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
