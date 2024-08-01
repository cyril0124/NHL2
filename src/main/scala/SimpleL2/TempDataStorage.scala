package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class TempDataBeatWrite()(implicit p: Parameters) extends L2Bundle {
    val beatData = UInt((beatBytes * 8).W)
    val wrMaskOH = UInt(nrBeat.W)
}

class TempDataWrite()(implicit p: Parameters) extends L2Bundle {
    val idx  = UInt(log2Ceil(nrMSHR).W)
    val data = UInt(dataBits.W)
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
            val write_s5 = Flipped(ValidIO(new TempDataWrite))
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
    val rdData_ts2 = WireInit(0.U.asTypeOf(Vec(group, new TempDataEntry(groupBytes))))

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val wen_ds_ts1    = io.fromDS.write_s5.valid
    val wen_rxdat_ts1 = io.fromRXDAT.write.fire
    val wen_sinkc_ts1 = io.fromSinkC.write.fire
    val wen_ts1       = wen_ds_ts1 || wen_rxdat_ts1 || wen_sinkc_ts1
    val wIdx_ts1      = PriorityMux(Seq(wen_ds_ts1 -> io.fromDS.write_s5.bits.idx, wen_rxdat_ts1 -> io.fromRXDAT.write.bits.idx, wen_sinkc_ts1 -> io.fromSinkC.write.bits.idx))
    val wData_ts1     = PriorityMux(Seq(wen_ds_ts1 -> io.fromDS.write_s5.bits.data, wen_rxdat_ts1 -> io.fromRXDAT.write.bits.data, wen_sinkc_ts1 -> io.fromSinkC.write.bits.data))

    val ren_ts1       = io.fromReqArb.read_s1.fire
    val rDest_ts1     = io.fromReqArb.read_s1.bits.dest
    val rIdx_ts1      = io.fromReqArb.read_s1.bits.idx
    val dsWrWayOH_ts1 = io.fromReqArb.dsWrWayOH_s1
    val dsWrSet_ts1   = io.fromReqArb.dsWrSet_s1

    /**
     * Priority:
     *          fromDS.write_s5 > fromRXDAT.write > fromReqArb.read_s1 > fromSinkC.write
     */
    io.fromReqArb.read_s1.ready := !wen_ds_ts1 && !io.fromRXDAT.write.valid
    io.fromRXDAT.write.ready    := !wen_ds_ts1
    io.fromSinkC.write.ready    := !io.fromReqArb.read_s1.valid && !wen_ds_ts1 && !io.fromRXDAT.write.valid

    tempDataSRAMs.zipWithIndex.foreach { case (sram, i) =>
        sram.io.w.req.valid             := wen_ts1
        sram.io.w.req.bits.setIdx       := wIdx_ts1
        sram.io.w.req.bits.data(0).data := wData_ts1(groupBytes * 8 * (i + 1) - 1, groupBytes * 8 * i) // TODO: ECC
        sram.io.w.req.bits.waymask.foreach(_ := 1.U)
        assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "tempDataSRAM should always be ready for write")

        sram.io.r.req.valid       := ren_ts1
        sram.io.r.req.bits.setIdx := rIdx_ts1
        assert(!(sram.io.r.req.valid && !sram.io.r.req.ready), "tempDataSRAM should always be ready for read")

        rdData_ts2(i).data := sram.io.r.resp.data(0).data
    }

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val wen_ts2       = RegNext(wen_ts1, false.B)
    val ren_ts2       = RegNext(ren_ts1, false.B)
    val rDest_ts2     = RegEnable(rDest_ts1, ren_ts1)
    val dsWrWayOH_ts2 = RegEnable(dsWrWayOH_ts1, ren_ts1)
    val dsWrSet_ts2   = RegEnable(dsWrSet_ts1, ren_ts1)
    val finalData_ts2 = VecInit(rdData_ts2.map(_.data)).asUInt

    io.toDS.refillWrite_s2.valid      := ren_ts2 && (rDest_ts2 & DataDestination.DataStorage).orR
    io.toDS.refillWrite_s2.bits.data  := finalData_ts2
    io.toDS.refillWrite_s2.bits.wayOH := dsWrWayOH_ts2
    io.toDS.refillWrite_s2.bits.set   := dsWrSet_ts2

    io.toSourceD.data_s2.valid := ren_ts2 && (rDest_ts2 & DataDestination.SourceD).orR
    io.toSourceD.data_s2.bits  := finalData_ts2

    io.toTXDAT.data_s2.valid := ren_ts2 && (rDest_ts2 & DataDestination.TXDAT).orR
    io.toTXDAT.data_s2.bits  := finalData_ts2

    dontTouch(io)
}

object TempDataStorage extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new TempDataStorage()(config), name = "TempDataStorage", split = true)
}
