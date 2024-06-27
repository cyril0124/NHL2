package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, BankedSRAM}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import dataclass.data
import freechips.rocketchip.diplomacy.BufferParams.flow

class DSRead()(implicit p: Parameters) extends L2Bundle {
    val set  = UInt(setBits.W)
    val way  = UInt(wayBits.W)
    val dest = UInt(DataDestination.width.W)
}

class DSWrite()(implicit p: Parameters) extends L2Bundle {
    val set  = UInt(setBits.W)
    val way  = UInt(wayBits.W)
    val data = UInt(dataBits.W)
}

class DSResp()(implicit p: Parameters) extends L2Bundle {
    val data = UInt(dataBits.W)
}

class DSEntry()(implicit p: Parameters) extends L2Bundle {
    // val ecc = UInt()
    val data = UInt(dataBits.W)
}

class DataStorage()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val dsWrite_s2 = Flipped(CreditIO(new DSWrite))
        val dsWrWay_s3 = Input(UInt(wayBits.W))
        val dsRead_s3  = Flipped(CreditIO(new DSRead))

        val toTempDS = new Bundle {
            val dsResp_ds4 = ValidIO(new DSResp)
            val dsDest_ds4 = Output(UInt(DataDestination.width.W))
        }

        val toTXDAT = new Bundle {
            val dsResp_ds4 = ValidIO(new DSResp)
        }
    })

    io <> DontCare

    val dsReady_s2 = WireInit(true.B)
    val dsReady_s3 = WireInit(true.B)

    val dataSRAM = Module(
        new BankedSRAM(
            gen = new DSEntry,
            sets = sets * ways,
            ways = 1,
            nrBank = dataSramBank,
            singlePort = true,
            // hasMbist = false /* TODO */,
            // hasShareBus = false /* TDOO */,
            hasClkGate = enableClockGate
            // parentName = parentName + "ds_" /* TODO */
        )
    )
    dataSRAM.io <> DontCare

    // -----------------------------------------------------------------------------------------
    // Stage 2 (release write)
    // -----------------------------------------------------------------------------------------
    val wrSet_s2  = io.dsWrite_s2.bits.set
    val wrData_s2 = io.dsWrite_s2.bits.data
    val wen_s2    = io.dsWrite_s2.valid
    // TODO: calc ECC

    assert(!(RegNext(io.dsWrite_s2.valid, false.B) && io.dsWrite_s2.valid), "continuous write!")

    // -----------------------------------------------------------------------------------------
    // Stage 3 (mainpipe read)
    //         &&
    // DataStorage Stage 1
    // -----------------------------------------------------------------------------------------
    // val wen_ds1    = RegNext(wen_s2, false.B)
    val wen_ds1 = WireInit(false.B)

    val rdQueue = Module(new Queue(new DSRead, rdQueueEntries, flow = true))
    rdQueue.io.deq.ready := dsReady_s3 && !wen_ds1
    rdQueue.io.enq.valid := io.dsRead_s3.valid
    rdQueue.io.enq.bits  := io.dsRead_s3.bits
    assert(!(rdQueue.io.enq.valid && !rdQueue.io.enq.ready), "rdQueue should not blocking the input request!")

    val ren_ds1    = rdQueue.io.deq.fire
    val rdReq_ds1  = rdQueue.io.deq.bits
    val rdDest_ds1 = rdReq_ds1.dest
    val rdIdx_ds1  = Cat(rdReq_ds1.way, rdReq_ds1.set)
    dataSRAM.io.r.req.valid       := ren_ds1 && !wen_ds1 && dsReady_s3
    dataSRAM.io.r.req.bits.setIdx := rdIdx_ds1

    val rdCrdCnt_s3 = RegInit(0.U(log2Ceil(rdQueueEntries + 1).W))
    io.dsRead_s3.crdv := (rdCrdCnt_s3 < rdQueueEntries.U || ren_ds1) && !reset.asBool
    when(io.dsRead_s3.crdv && !ren_ds1) {
        rdCrdCnt_s3 := rdCrdCnt_s3 + 1.U
        assert(rdCrdCnt_s3 < rdQueueEntries.U)
    }.elsewhen(!io.dsRead_s3.crdv && ren_ds1) {
        rdCrdCnt_s3 := rdCrdCnt_s3 - 1.U
        assert(rdCrdCnt_s3 > 0.U)
    }

    val wrQueue = Module(new Queue(new DSWrite, 1, flow = false))
    wrQueue.io.deq.ready := dsReady_s3
    wrQueue.io.enq.valid := wen_s2
    wrQueue.io.enq.bits  := io.dsWrite_s2.bits
    assert(!(wrQueue.io.enq.valid && !wrQueue.io.enq.ready), "wrQueue should not blocking the input request!")

    val wrReq_ds1    = wrQueue.io.deq.bits
    val wrData_ds1   = wrReq_ds1.data
    val wrWayReg_ds1 = RegEnable(io.dsWrWay_s3, 0.U, RegNext(wen_s2))
    val wrWay_ds1    = Mux(RegNext(wen_s2), io.dsWrWay_s3, wrWayReg_ds1)
    val wrIdx_ds1    = Cat(wrWay_ds1, wrReq_ds1.set)
    wen_ds1                             := wrQueue.io.deq.fire
    dataSRAM.io.w.req.valid             := wen_ds1 && dsReady_s3
    dataSRAM.io.w.req.bits.data(0).data := wrData_ds1
    dataSRAM.io.w.req.bits.setIdx       := wrIdx_ds1
    dontTouch(wrWay_ds1)

    val wrCrdCnt_s3 = RegInit(0.U(1.W))
    io.dsWrite_s2.crdv := (wrCrdCnt_s3 === 0.U || wen_ds1) && !reset.asBool
    when(io.dsWrite_s2.crdv && !wen_ds1) {
        wrCrdCnt_s3 := wrCrdCnt_s3 + 1.U
        assert(wrCrdCnt_s3 < 1.U)
    }.elsewhen(!io.dsWrite_s2.crdv && wen_ds1) {
        wrCrdCnt_s3 := wrCrdCnt_s3 - 1.U
        assert(wrCrdCnt_s3 > 0.U)
    }

    assert(PopCount(Seq(wen_ds1, ren_ds1)) <= 1.U)

    // -----------------------------------------------------------------------------------------
    // DataStorage Stage 2 (read accept)
    // -----------------------------------------------------------------------------------------
    val wen_ds2    = RegNext(wen_ds1, false.B)
    val ren_ds2    = RegNext(ren_ds1, false.B)
    val rdDest_ds2 = RegEnable(rdDest_ds1, ren_ds1)
    assert(PopCount(Seq(wen_ds2, ren_ds2)) <= 1.U)

    // -----------------------------------------------------------------------------------------
    // DataStorage Stage 3 (read finish && ECC)
    // -----------------------------------------------------------------------------------------
    val ren_ds3    = RegNext(ren_ds2, false.B)
    val rdData_ds3 = RegEnable(dataSRAM.io.r.resp.data(0), ren_ds2)
    val rdDest_ds3 = RegEnable(rdDest_ds2, ren_ds2)
    // TODO: ECC

    // -----------------------------------------------------------------------------------------
    // DataStorage Stage 4 (data output)
    // -----------------------------------------------------------------------------------------
    val ren_ds4    = RegNext(ren_ds3, false.B)
    val rdData_ds4 = RegEnable(rdData_ds3, ren_ds3)
    val rdDest_ds4 = RegEnable(rdDest_ds3, ren_ds3)

    io.toTXDAT.dsResp_ds4.valid     := rdDest_ds4 === DataDestination.TXDAT && ren_ds4
    io.toTXDAT.dsResp_ds4.bits.data := rdData_ds4.data

    io.toTempDS.dsResp_ds4.valid     := (rdDest_ds4 === DataDestination.TempDataStorage || rdDest_ds4 === DataDestination.SourceD) && ren_ds4
    io.toTempDS.dsResp_ds4.bits.data := rdData_ds4.data
    io.toTempDS.dsDest_ds4           := rdDest_ds4

    dsReady_s2 := !ren_ds1 && !wen_ds1
    dsReady_s3 := !ren_ds2 && !wen_ds2
    dontTouch(dsReady_s3)

    dontTouch(io)
}

object DataStorage extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new DataStorage()(config), name = "DataStorage", split = true)
}
