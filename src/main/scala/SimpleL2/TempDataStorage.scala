package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class TempDataWrite()(implicit p: Parameters) extends L2Bundle {
    val beatData = UInt((beatBytes * 8).W)
    val dataId   = UInt(dataIdBits.W)
    val wrMaskOH = UInt(2.W)
}

class TempDataReadReq()(implicit p: Parameters) extends L2Bundle {
    val dataId = UInt(dataIdBits.W)
    val dest   = UInt(DataDestination.width.W) // Data output destination
}

class TempDataReadResp()(implicit p: Parameters) extends L2Bundle {
    val data = UInt(dataBits.W)
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
            val dsDest_ds4 = Input(UInt(DataDestination.width.W))
        }

        val fromRXDAT = new Bundle {
            val write = Flipped(DecoupledIO(new TempDataWrite))
        }

        val fromSinkC = new Bundle {
            val write = Flipped(DecoupledIO(new TempDataWrite))
        }

        val toDS = new Bundle {
            val dsWrite = ValidIO(new DSWrite) // TODO:
        }

        val fromSourceD = new Bundle {
            val read = Flipped(DecoupledIO(new TempDataReadReq))
            val resp = ValidIO(new TempDataReadResp)
        }

        val toSourceD = new Bundle {
            val beatData = DecoupledIO(new DataSelectorOut)
            val dataId   = Output(UInt(dataIdBits.W))
        }

        val toTXDAT = new Bundle {
            val resp = ValidIO(new TempDataReadResp) // TODO:
        }

        val fromReqArb = new Bundle {
            val read    = Flipped(DecoupledIO(new TempDataReadReq))
            val dsWrSet = Input(UInt(setBits.W))
            val dsWrWay = Input(UInt(wayBits.W))
        }

        val flushEntry = Flipped(ValidIO(UInt(dataIdBits.W))) // TODO: from MainPipe

        val freeDataId = Output(UInt(dataIdBits.W))
    })

    io <> DontCare

    val valids      = RegInit(VecInit(Seq.fill(nrTempDataEntry)(VecInit(Seq.fill(nrBeat)(false.B)))))
    val freeDataIdx = PriorityEncoder(~VecInit(valids.map(_.asUInt.orR)).asUInt)
    val full        = Cat(valids.map(_.asUInt.orR)).andR
    dontTouch(valids)

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val isSourceD_ts1      = io.fromDS.dsDest_ds4 === DataDestination.SourceD
    val stallOnSourceD_ts1 = isSourceD_ts1 && !io.toSourceD.beatData.ready && io.toSourceD.beatData.valid && io.toSourceD.beatData.bits.last
    val wen_sourceD_ts1    = stallOnSourceD_ts1
    val wen_ds_ts1         = io.fromDS.dsDest_ds4 === DataDestination.TempDataStorage && io.fromDS.dsResp_ds4.valid
    val wen_rxdat_ts1      = io.fromRXDAT.write.fire
    val wen_sinkc_ts1      = io.fromSinkC.write.fire
    val ren_sourceD_ts1    = io.fromSourceD.read.fire
    val ren_reqArb_ts1     = io.fromReqArb.read.fire
    assert(
        PopCount(Seq(wen_sourceD_ts1, wen_ds_ts1, wen_rxdat_ts1)) <= 1.U,
        "wen_sourceD_ts1: %d, wen_ds_ts1: %d, wen_rxdat_ts1: %d",
        wen_sourceD_ts1,
        wen_ds_ts1,
        wen_rxdat_ts1
    )
    assert(PopCount(Seq(ren_sourceD_ts1, ren_reqArb_ts1)) <= 1.U, "multiple read! ren_sourceD_ts1: %d, ren_reqArb_ts1: %d", ren_sourceD_ts1, ren_reqArb_ts1)
    assert(
        PopCount(Seq(wen_sourceD_ts1, wen_ds_ts1, wen_rxdat_ts1, wen_sinkc_ts1, ren_sourceD_ts1, ren_reqArb_ts1)) <= 1.U,
        "multiple write! wen_sourceD_ts1: %d, wen_ds_ts1: %d, wen_rxdat_ts1: %d, wen_sinkc_ts1: %d, ren_sourceD_ts1: %d, ren_reqArb_ts1: %d",
        wen_sourceD_ts1,
        wen_ds_ts1,
        wen_rxdat_ts1,
        wen_sinkc_ts1,
        ren_sourceD_ts1,
        ren_reqArb_ts1
    )
    assert(!(wen_sourceD_ts1 && wen_ds_ts1), "wen_sourceD_ts1 and wen_ds_ts1 are both true! only one can be true")

    val wen_ts1 = wen_sourceD_ts1 || wen_ds_ts1 || wen_rxdat_ts1 || wen_sinkc_ts1
    val wrIdx_ts1 = MuxCase(
        freeDataIdx,
        Seq(
            io.fromRXDAT.write.valid   -> io.fromRXDAT.write.bits.dataId,
            io.fromDS.dsResp_ds4.valid -> freeDataIdx,
            io.fromSinkC.write.valid   -> io.fromSinkC.write.bits.dataId
        )
    )
    val wrMaskOH_ts1 = MuxCase(
        "b00".U,
        Seq(
            (wen_sourceD_ts1 || wen_ds_ts1) -> "b11".U,
            wen_rxdat_ts1                   -> io.fromRXDAT.write.bits.wrMaskOH,
            wen_sinkc_ts1                   -> io.fromSinkC.write.bits.wrMaskOH
        )
    )

    val ren_ts1      = ren_sourceD_ts1 || ren_reqArb_ts1
    val rdIdx_ts1    = Mux(ren_sourceD_ts1, io.fromSourceD.read.bits.dataId, io.fromReqArb.read.bits.dataId)
    val rdDest_ts1   = Mux(ren_reqArb_ts1, io.fromReqArb.read.bits.dest, DataDestination.SourceD)
    val rdDataId_ts1 = Mux(ren_reqArb_ts1, io.fromReqArb.read.bits.dataId, io.fromSourceD.read.bits.dataId)
    assert(
        !(wen_ts1 && !wrMaskOH_ts1.orR),
        "wen_ts1 but wrMaskOH_ts1 is 0b00, wen_sourceD:%d, wen_ds:%d, wen_rxdat:%d, wen_sinkc:%d",
        wen_sourceD_ts1,
        wen_ds_ts1,
        wen_rxdat_ts1,
        wen_sinkc_ts1
    )
    assert(!(wen_ts1 && ren_ts1), "try to write and read at the same time")

    val wrData_ts1 = MuxCase(
        0.U,
        Seq(
            (wen_sourceD_ts1 || wen_ds_ts1) -> io.fromDS.dsResp_ds4.bits.data
        )
    )

    val dsWrSet_ts1 = io.fromReqArb.dsWrSet
    val dsWrWay_ts1 = io.fromReqArb.dsWrWay

    /** 
      * Write beatData(32-bytes) into internal SRAM.
      * A complete cacheline data(64-bytes) should be splited into multiple banks due to physical constrain or chip technolocy limitation(for more information, please refer to the SRAM data book provided by specific foundary).
      */
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

    val rdDatas_ts2 = WireInit(0.U.asTypeOf(Vec(nrBeat, new TempDataEntry)))
    tempDataSRAMs.zipWithIndex.foreach { case (sram, i) =>
        if (i != 0) require(i == 1) // TODO: for now, we did not support multiple beats except 2, should be fixed in the future

        sram.io.w(
            valid = wen_ts1 && wrMaskOH_ts1(i),
            data = MuxCase(
                0.U.asTypeOf(new TempDataEntry),
                Seq(
                    (wen_sourceD_ts1 || wen_ds_ts1) -> TempDataEntry(wrData_ts1(255 * i + 255, i * 256)),
                    wen_rxdat_ts1                   -> TempDataEntry(io.fromRXDAT.write.bits.beatData),
                    wen_sinkc_ts1                   -> TempDataEntry(io.fromSinkC.write.bits.beatData)
                )
            ),
            setIdx = Mux(wen_rxdat_ts1, if (i == 0) wrIdx_ts1 else RegEnable(wrIdx_ts1, false.B, wen_ts1), wrIdx_ts1),
            waymask = 1.U
        )
        assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "tempDataSRAM should always be ready for write")
        assert(!(wen_ts1 && wrMaskOH_ts1(i) && valids(sram.io.w.req.bits.setIdx)(i)), s"try to write to an valid entry setIdx => %d i => ${i}", sram.io.w.req.bits.setIdx)

        when(sram.io.w.req.fire && wrMaskOH_ts1(i)) {
            if (i == 0) {
                valids(freeDataIdx)(i) := true.B
            } else {
                valids(RegEnable(freeDataIdx, 0.U, wen_ts1))(i) := true.B
            }
        }

        sram.io.r.req.valid       := ren_ts1
        sram.io.r.req.bits.setIdx := rdIdx_ts1
        rdDatas_ts2(i)            := sram.io.r.resp.data(0)
        assert(!(sram.io.r.req.valid && !sram.io.r.req.ready), "tempDataSRAM should always be ready for read")
        assert(!(ren_ts1 && !valids(rdIdx_ts1).asUInt.orR), "try to read from an empty tempDataSRAM valids:0x%x rdIdx_ts1:0x%x", valids.asUInt, rdIdx_ts1)
    }
    assert(!(wen_ts1 && full), "try to write to a full tempDataSRAM valids:0x%x", valids.asUInt)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val bypassDsData      = io.fromDS.dsResp_ds4.valid || RegNext(io.fromDS.dsResp_ds4.valid, false.B) // bypass data from DS to SourceD
    val ren_ts2           = RegNext(ren_ts1, false.B)
    val ren_sourceD_ts2   = RegNext(ren_sourceD_ts1, false.B)
    val rdDest_ts2        = RegEnable(rdDest_ts1, 0.U, ren_ts1)                                        // Read destination
    val rdDataId_ts2      = RegEnable(rdDataId_ts1, 0.U, ren_ts1)                                      // Read dataId
    val dataToSourceD_ts2 = (rdDest_ts2 & DataDestination.SourceD).orR
    val dataToDs_ts2      = (rdDest_ts2 & DataDestination.DataStorage).orR
    val readToSourceD_ts2 = ren_ts2 && dataToSourceD_ts2 || RegNext(ren_ts2 && dataToSourceD_ts2, false.B)
    val readToDS_ts2      = ren_ts2 && dataToDs_ts2

    /** 
      * Split 512-bits of data into two 256-bits data. The splited data is originated from [[DataStorage]].
      * If there is a hit on SinkA request, then we will need to bypass the data readed from [[DataStorage]] to [[SourceD]], 
      * [[TempDataStorage]] is acted as a bypass data path in this case.
      */
    val dataSel = Module(new DataSelector)
    dataSel.io.dataIn.valid := io.fromDS.dsResp_ds4.valid || ren_ts2 && dataToSourceD_ts2
    dataSel.io.dataIn.bits  := Mux(readToSourceD_ts2, rdDatas_ts2.asUInt, io.fromDS.dsResp_ds4.bits.data)

    io.toSourceD.beatData.valid := dataSel.io.dataOut.valid && (bypassDsData || readToSourceD_ts2)
    io.toSourceD.beatData.bits  := dataSel.io.dataOut.bits
    io.toSourceD.dataId         := Mux(ren_ts2 && dataToSourceD_ts2, rdDataId_ts2, freeDataIdx)
    io.freeDataId               := freeDataIdx

    val dsWrSet_ts2 = RegEnable(dsWrSet_ts1, ren_ts1)
    val dsWrWay_ts2 = RegEnable(dsWrWay_ts1, ren_ts1)
    io.toDS.dsWrite.valid     := ren_ts2 && readToDS_ts2
    io.toDS.dsWrite.bits.data := rdDatas_ts2.asUInt
    io.toDS.dsWrite.bits.set  := dsWrSet_ts2
    io.toDS.dsWrite.bits.way  := dsWrWay_ts2

    /** Arbitration between [[RequestArbiter]]'s read and [[SourceD]]'s read */
    io.fromReqArb.read.ready      := !io.fromSourceD.read.valid && !RegNext(io.fromReqArb.read.fire, false.B) // TODO: improve data bandwidth between SourceD and TempDataStorage?
    io.fromSourceD.read.ready     := !wen_ts1                                                                 // TODO: arbiter
    io.fromSourceD.resp.valid     := ren_sourceD_ts2
    io.fromSourceD.resp.bits.data := rdDatas_ts2.asUInt                                                       // 512-bits

    /** 
      * [[DataStorage]] is non-blocking, hence we should give a highest priority.
      * Priority =>  [[DataStorage]] > [[SourceD]](stall on SourceD) > [[RXDAT]] > [[SinkC]]
      */
    io.fromRXDAT.write.ready := !wen_ds_ts1 && !wen_sourceD_ts1
    io.fromSinkC.write.ready := !wen_ds_ts1 && !wen_sourceD_ts1 && !wen_rxdat_ts1

    /** Flush data SRAM entry of [[TempDataStorage]] */
    when(io.flushEntry.valid) {
        valids(io.flushEntry.bits).map(_ := false.B)
    }

    assert(
        !(io.flushEntry.valid && Cat(tempDataSRAMs.map(_.io.w.req.fire)).orR && freeDataIdx === io.flushEntry.bits),
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
