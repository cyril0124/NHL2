package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqToAugmentedSeq

class TempDataBeatWrite()(implicit p: Parameters) extends L2Bundle {
    val beatData = UInt((beatBytes * 8).W)
    val wrMaskOH = UInt(nrBeat.W)
}

class TempDataWrite()(implicit p: Parameters) extends L2Bundle {
    val idx  = UInt(log2Ceil(nrMSHR).W)
    val data = UInt(dataBits.W)
    val mask = UInt(nrBeat.W)
}

class TempDataReadReq()(implicit p: Parameters) extends L2Bundle {
    val idx  = UInt(log2Ceil(nrMSHR).W)
    val dest = UInt(DataDestination.width.W)
}

class TempDataReadBeatResp()(implicit p: Parameters) extends L2Bundle {
    val beatData = UInt((beatBytes * 8).W)
}

class TempDataReadResp()(implicit p: Parameters) extends L2Bundle {
    val data = UInt(dataBits.W)
}

class TempDataEntry(bytes: Int)(implicit p: Parameters) extends L2Bundle {
    // TODO: ECC
    val data = UInt((bytes * 8).W)
}

object TempDataEntry {
    def apply(bytes: Int, data: UInt, ecc: UInt = 0.U)(implicit p: Parameters) = {
        val tempDataEntry = Wire(new TempDataEntry(bytes))
        tempDataEntry.data := data
        // tempDataEntry.ecc := ecc // TODO:
        tempDataEntry
    }
}

class TempDataStorage()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val fromDS = new Bundle {
            val eccVec_s5 = Input(Vec(blockBytes / eccProtectBytes, UInt(dataEccBits.W)))
            val write_s5  = Flipped(ValidIO(new TempDataWrite))
        }

        val fromRXDAT = new Bundle {
            val write = Flipped(DecoupledIO(new TempDataWrite))
        }

        val fromSinkC = new Bundle {
            val write = Flipped(DecoupledIO(new TempDataWrite))
        }

        val toDS = new Bundle {
            val refillWrite_s2 = ValidIO(new DSWrite)
        }

        val toSourceD = new Bundle {
            val data_s2 = DecoupledIO(UInt(dataBits.W))
        }

        val toTXDAT = new Bundle {
            val data_s2 = DecoupledIO(UInt(dataBits.W))
        }

        val fromReqArb = new Bundle {
            val read_s1      = Flipped(DecoupledIO(new TempDataReadReq))
            val dsWrSet_s1   = Input(UInt(setBits.W))
            val dsWrWayOH_s1 = Input(UInt(ways.W))
        }

        /** 
         * This signal indicates that there is an uncorrectable ECC error. 
         * It is also passed into the top-level of [[Slice]] and connect to the L2 top-level interrupt signal after one cycle delay.
         */
        val eccError = Output(Bool())
    })

    io <> DontCare

    val groupBytes = 16 // TODO: parameterize
    val group      = blockBytes / groupBytes
    val tempDataSRAMs = Seq.fill(group) {
        Module(
            new SRAMTemplate(
                gen = new TempDataEntry(groupBytes),
                set = nrMSHR,
                way = 1,
                singlePort = true,
                multicycle = 1
            )
        )
    }

    val tempDataEccVecs = RegInit(0.U.asTypeOf(Vec(nrMSHR, Vec(blockBytes / eccProtectBytes, UInt(dataEccBits.W)))))

    val full_ts1   = RegInit(true.B)
    val rdData_ts2 = WireInit(0.U.asTypeOf(Vec(group, new TempDataEntry(groupBytes))))

    // -----------------------------------------------------------------------------------------
    // Stage 0
    // -----------------------------------------------------------------------------------------
    val wIdx_sinkc_ts0  = io.fromSinkC.write.bits.idx
    val wData_sinkc_ts0 = io.fromSinkC.write.bits.data
    val fire_ts0        = io.fromSinkC.write.fire

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    def genEcc(data: UInt): UInt = {
        val ecc = dataCode.encode(data).head(dataEccBits)
        ecc
    }

    def genEccVec(data: UInt): Vec[UInt] = {
        VecInit(data.asTypeOf(Vec(blockBytes / eccProtectBytes, UInt(eccProtectBits.W))).map(genEcc))
    }

    val wen_ds_ts1      = io.fromDS.write_s5.valid
    val wen_rxdat_ts1   = io.fromRXDAT.write.valid
    val wMsk_rxdat_ts1  = io.fromRXDAT.write.bits.mask // Only RXDAT has valid mask beacuse RXDAT needs to deal with out-of-order refill data, other sources are all full mask
    val wen_sinkc_ts1   = full_ts1
    val wIdx_sinkc_ts1  = RegEnable(wIdx_sinkc_ts0, fire_ts0)
    val wData_sinkc_ts1 = RegEnable(wData_sinkc_ts0, fire_ts0)
    val wen_ts1         = wen_ds_ts1 || wen_rxdat_ts1 || wen_sinkc_ts1
    val wIdx_ts1        = PriorityMux(Seq(wen_ds_ts1 -> io.fromDS.write_s5.bits.idx, wen_rxdat_ts1 -> io.fromRXDAT.write.bits.idx, wen_sinkc_ts1 -> wIdx_sinkc_ts1))
    val wData_ts1       = PriorityMux(Seq(wen_ds_ts1 -> io.fromDS.write_s5.bits.data, wen_rxdat_ts1 -> io.fromRXDAT.write.bits.data, wen_sinkc_ts1 -> wData_sinkc_ts1))
    val wEcc_ts1        = PriorityMux(Seq(wen_ds_ts1 -> io.fromDS.eccVec_s5.asUInt, wen_rxdat_ts1 -> genEccVec(io.fromRXDAT.write.bits.data).asUInt, wen_sinkc_ts1 -> genEccVec(wData_sinkc_ts1).asUInt))

    when(fire_ts0) {
        full_ts1 := true.B
    }.elsewhen(wen_ts1 && !wen_ds_ts1 && !wen_rxdat_ts1) {
        full_ts1 := false.B
    }

    val ren_ts1       = io.fromReqArb.read_s1.fire
    val rDest_ts1     = io.fromReqArb.read_s1.bits.dest
    val rIdx_ts1      = io.fromReqArb.read_s1.bits.idx
    val dsWrWayOH_ts1 = io.fromReqArb.dsWrWayOH_s1
    val dsWrSet_ts1   = io.fromReqArb.dsWrSet_s1

    val fire_ts1 = wen_ts1 || ren_ts1

    /**
     * Priority:
     *          fromDS.write_s5 > fromRXDAT.write > fromSinkC.write > fromReqArb.read_s1
     */
    io.fromReqArb.read_s1.ready := !wen_ds_ts1 && !wen_sinkc_ts1 && !wen_rxdat_ts1
    io.fromRXDAT.write.ready    := !wen_ds_ts1
    io.fromSinkC.write.ready    := !full_ts1

    val groupValidMask = WireInit(0.U(group.W))
    when(wen_rxdat_ts1) {
        val _groupValidMask = FillInterleaved(group / nrBeat, wMsk_rxdat_ts1)
        require(_groupValidMask.getWidth == groupValidMask.getWidth)

        groupValidMask := _groupValidMask
    }.otherwise {
        groupValidMask := Fill(group, 1.U(1.W))
    }

    tempDataSRAMs.zipWithIndex.foreach { case (sram, i) =>
        sram.io.w.req.valid             := wen_ts1 && groupValidMask(i)
        sram.io.w.req.bits.setIdx       := wIdx_ts1
        sram.io.w.req.bits.data(0).data := wData_ts1(groupBytes * 8 * (i + 1) - 1, groupBytes * 8 * i)
        sram.io.w.req.bits.waymask.foreach(_ := 1.U)
        assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "tempDataSRAM should always be ready for write")

        sram.io.r.req.valid       := ren_ts1
        sram.io.r.req.bits.setIdx := rIdx_ts1
        assert(!(sram.io.r.req.valid && !sram.io.r.req.ready), "tempDataSRAM should always be ready for read")

        rdData_ts2(i).data := sram.io.r.resp.data(0).data
    }

    if (enableDataECC) {
        when(wen_ts1) {
            when(wen_rxdat_ts1) {
                val groupValidMaskExtend = FillInterleaved(groupBytes / eccProtectBytes, groupValidMask)
                val dataVec              = wData_ts1.asTypeOf(Vec(blockBytes / eccProtectBytes, UInt(eccProtectBits.W)))
                val eccVec               = tempDataEccVecs(wIdx_ts1) // WireInit(tempDataEccVecs(wIdx_ts1))
                require(dataVec.length == groupValidMaskExtend.getWidth, s"${dataVec.length} != ${groupValidMaskExtend.getWidth}")

                dataVec.zip(groupValidMaskExtend.asBools).zipWithIndex.foreach { case ((data, valid), i) =>
                    when(valid) {
                        eccVec(i) := genEcc(data)
                    }
                }
            }.otherwise {
                tempDataEccVecs(wIdx_ts1) := wEcc_ts1.asTypeOf(Vec(blockBytes / eccProtectBytes, UInt(dataEccBits.W)))
            }
        }
    }
    assert(!(wen_ts1 && ren_ts1 && wIdx_ts1 === rIdx_ts1), "wen_ts1 && ren_ts1 && wIdx_ts1 === rIdx_ts1")

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val valid_ts2        = RegNext(fire_ts1, false.B)
    val ren_ts2          = valid_ts2 && RegEnable(ren_ts1, false.B, fire_ts1)
    val rDest_ts2        = RegEnable(rDest_ts1, ren_ts1)
    val dsWrWayOH_ts2    = RegEnable(dsWrWayOH_ts1, ren_ts1)
    val dsWrSet_ts2      = RegEnable(dsWrSet_ts1, ren_ts1)
    val rIdx_ts2         = RegEnable(rIdx_ts1, ren_ts1)
    val rEccVec_ts2      = RegEnable(tempDataEccVecs(rIdx_ts1), ren_ts1)
    val finalDataRaw_ts2 = VecInit(rdData_ts2.map(_.data)).asUInt.asTypeOf(Vec(8, UInt(64.W)))

    if (!enableDataECC) {
        val finalData_ts2 = VecInit(rdData_ts2.map(_.data)).asUInt

        io.toDS.refillWrite_s2.valid      := ren_ts2 && (rDest_ts2 & DataDestination.DataStorage).orR
        io.toDS.refillWrite_s2.bits.data  := finalData_ts2
        io.toDS.refillWrite_s2.bits.wayOH := dsWrWayOH_ts2
        io.toDS.refillWrite_s2.bits.set   := dsWrSet_ts2

        io.toSourceD.data_s2.valid := ren_ts2 && (rDest_ts2 & DataDestination.SourceD).orR
        io.toSourceD.data_s2.bits  := finalData_ts2

        io.toTXDAT.data_s2.valid := ren_ts2 && (rDest_ts2 & DataDestination.TXDAT).orR
        io.toTXDAT.data_s2.bits  := finalData_ts2
    }

    // -----------------------------------------------------------------------------------------
    // Stage 2 for ecc
    // -----------------------------------------------------------------------------------------
    val ren_ts2e          = RegNext(ren_ts2, false.B)
    val rDest_ts2e        = RegEnable(rDest_ts2, ren_ts2)
    val dsWrWayOH_ts2e    = RegEnable(dsWrWayOH_ts2, ren_ts2)
    val dsWrSet_ts2e      = RegEnable(dsWrSet_ts2, ren_ts2)
    val rIdx_ts2e         = RegEnable(rIdx_ts2, ren_ts2)
    val rEccVec_ts2e      = tempDataEccVecs(rIdx_ts2e)
    val finalDataRaw_ts2e = RegEnable(finalDataRaw_ts2, ren_ts2)
    val finalData_ts2e = VecInit(finalDataRaw_ts2e.zip(rEccVec_ts2e).map { case (d, e) =>
        dataCode.decode(Cat(e, d)).corrected
    }).asUInt

    val finalDataHasErr_ts2e = VecInit(finalDataRaw_ts2e.zip(rEccVec_ts2e).map { case (d, e) =>
        dataCode.decode(Cat(e, d)).error
    }).asUInt.orR
    val finalDataHasUncorrectable_ts2e = VecInit(finalDataRaw_ts2e.zip(rEccVec_ts2e).map { case (d, e) =>
        dataCode.decode(Cat(e, d)).uncorrectable
    }).asUInt.orR

    // TODO: ECC info should be saved in some kind of CSR registers. For now, just ignore it.
    if (enableDataECC) {
        assert(
            !(ren_ts2e && finalDataHasErr_ts2e && finalDataHasUncorrectable_ts2e),
            "TODO: Data has error finalData_ts2e:%x, finalDataHasErr_ts2e:%d, finalDataHasUncorrectable_ts2e:%d",
            finalData_ts2e,
            finalDataHasErr_ts2e,
            finalDataHasUncorrectable_ts2e
        )

        io.eccError := ren_ts2e && finalDataHasUncorrectable_ts2e
    } else {
        io.eccError := false.B
    }

    if (enableDataECC) {
        io.toDS.refillWrite_s2.valid      := ren_ts2e && (rDest_ts2e & DataDestination.DataStorage).orR
        io.toDS.refillWrite_s2.bits.data  := finalData_ts2e
        io.toDS.refillWrite_s2.bits.wayOH := dsWrWayOH_ts2e
        io.toDS.refillWrite_s2.bits.set   := dsWrSet_ts2e

        io.toSourceD.data_s2.valid := ren_ts2e && (rDest_ts2e & DataDestination.SourceD).orR
        io.toSourceD.data_s2.bits  := finalData_ts2e

        io.toTXDAT.data_s2.valid := ren_ts2e && (rDest_ts2e & DataDestination.TXDAT).orR
        io.toTXDAT.data_s2.bits  := finalData_ts2e
    }

    dontTouch(io)
}

object TempDataStorage extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new TempDataStorage()(config), name = "TempDataStorage", split = true)
}
