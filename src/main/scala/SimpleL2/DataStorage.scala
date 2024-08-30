package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.Code
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import dataclass.data
import freechips.rocketchip.util.SeqToAugmentedSeq

class DSRead()(implicit p: Parameters) extends L2Bundle {
    val set   = UInt(setBits.W)
    val wayOH = UInt(ways.W)
    val dest  = UInt(DataDestination.width.W)
}

class DSWrite()(implicit p: Parameters) extends L2Bundle {
    val set   = UInt(setBits.W)
    val wayOH = UInt(ways.W)
    val data  = UInt(dataBits.W)
}

class DSResp()(implicit p: Parameters) extends L2Bundle {
    val data = UInt(dataBits.W)
}

class DataStorage()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {

        /** Write interface for [[SinkC]] */
        val dsWrite_s2 = Flipped(DecoupledIO(new DSWrite))

        /** Refilled data from [[TempDataStorage]] */
        val refillWrite_s2 = Flipped(ValidIO(new DSWrite))

        /** Read interface for [[MainPipe]] */
        val fromMainPipe = new Bundle {
            val dsRead_s3    = Flipped(ValidIO(new DSRead))
            val mshrId_s3    = Input(UInt(mshrBits.W))
            val dsWrWayOH_s3 = Flipped(ValidIO(UInt(ways.W))) // Write wayOH can only be determined when directory result is read back
        }

        val toTempDS = new Bundle {
            val eccVec_s5 = Output(Vec(blockBytes / eccProtectBytes, UInt(dataEccBits.W)))
            val write_s5  = ValidIO(new TempDataWrite)
        }

        /** 
         * The data being read will be passed into [[TXDAT]], where data will be further transfered into 
         * next level cache.
         */
        val toTXDAT = new Bundle {
            val dsResp_s6s7 = DecoupledIO(new DSResp)
        }

        val toSourceD = new Bundle {
            val dsResp_s6s7 = DecoupledIO(new DSResp)
        }

        /** 
         * This signal indicates that there is an uncorrectable ECC error. 
         * It is also passed into the top-level of [[Slice]] and connect to the L2 top-level interrupt signal after one cycle delay.
         */
        val eccError = Output(Bool())
    })

    val ready_s7    = WireInit(false.B)
    val sramReady   = WireInit(false.B)
    val wayConflict = WireInit(false.B)

    println(s"[${this.getClass().toString()}] eccProtectBits: ${eccProtectBits} dataEccBits: ${dataEccBits} eccEnable: ${enableDataECC}")

    /**
     * Each cacheline(64-byte) should be seperated into groups of 16-bytes(128-bits) beacuse SRAM provided by foundary is typically less than 144-bits wide.
     * And we also need extra 8-bits for every 8-bytes to store ECC bits. The final SRAM bits = [8-bytes + 8-bits(ECC)] * 2 = 144-bits.
     *                                   way_0                                                          way_1                        ...
     *        SRAM_0                                SRAM_1    SRAM_2    SRAM_3         SRAM_4      SRAM_5    SRAM_6     SRAM_7       ...
     * |-------------------------------------------------------------------------|  |-------------------------------------------|        
     * | [8-bytes + 8-bits(ECC)] * 2 = 144-bits | 144-bits | 144-bits | 144-bits |  | 144-bits | 144-bits | 144-bits | 144-bits |    ...
     * |-------------------------------------------------------------------------|  |-------------------------------------------| 
     *                                       .                                                   .  
     *                                       .                                                   .
     *                                       .                                                   .
     * 
     */
    val groupBytes = 16                      // TODO: parameterize
    val group      = blockBytes / groupBytes // 64 / 16 = 4, each CacheLine is splited into 4 groups of data bytes
    val dataSRAMs = Seq.fill(ways) {
        Seq.fill(group) {
            Module(
                new SRAMTemplate(
                    gen = Vec(groupBytes / 8, UInt(dataWithECCBits.W)),
                    set = sets,
                    way = 1,
                    singlePort = true,
                    multicycle = 2
                )
            )
        }
    }

    println(s"[${this.getClass().toString()}] dataSRAMs entry bits: ${Vec(groupBytes / 8, UInt(dataWithECCBits.W)).getWidth}")

    val latchTempDsToDs = enableDataECC && optParam.latchTempDsToDs || !enableDataECC

    // -----------------------------------------------------------------------------------------
    // Stage 2 (SinkC release write)
    // -----------------------------------------------------------------------------------------
    val wen_sinkC_s2     = io.dsWrite_s2.fire
    val wrSet_sinkC_s2   = io.dsWrite_s2.bits.set
    val wrWayOH_sinkC_s2 = io.dsWrite_s2.bits.wayOH
    val wrData_sinkC_s2  = io.dsWrite_s2.bits.data

    val wen_refill_s2     = io.refillWrite_s2.valid
    val wrData_refill_s2  = io.refillWrite_s2.bits.data
    val wrSet_refill_s2   = io.refillWrite_s2.bits.set
    val wrWayOH_refill_s2 = io.refillWrite_s2.bits.wayOH

    val fire_s2 = if (latchTempDsToDs) wen_sinkC_s2 || wen_refill_s2 else wen_sinkC_s2

    _assert(!(RegNext(io.dsWrite_s2.fire, false.B) && io.dsWrite_s2.fire), "continuous write!")

    // -----------------------------------------------------------------------------------------
    // Stage 3 (mainpipe read)
    // -----------------------------------------------------------------------------------------
    val ren_s3       = io.fromMainPipe.dsRead_s3.valid
    val rdDest_s3    = io.fromMainPipe.dsRead_s3.bits.dest
    val rdWayOH_s3   = io.fromMainPipe.dsRead_s3.bits.wayOH
    val rdSet_s3     = io.fromMainPipe.dsRead_s3.bits.set
    val rdMshrIdx_s3 = io.fromMainPipe.mshrId_s3

    // @formatter:off
    val wen_s3           = if (latchTempDsToDs) { RegNext(fire_s2, false.B) } else { RegNext(fire_s2, false.B) || wen_refill_s2 } 
    val wen_sinkC_s3     = if (latchTempDsToDs) { wen_s3 && RegEnable(wen_sinkC_s2, false.B, fire_s2) } else { RegNext(fire_s2, false.B) && RegEnable(wen_sinkC_s2, false.B, fire_s2) } 
    val wrData_sinkC_s3  = RegEnable(wrData_sinkC_s2, wen_sinkC_s2)
    val wrSet_sinkC_s3   = RegEnable(wrSet_sinkC_s2, wen_sinkC_s2)
    val wrWayOH_sinkC_s3 = Mux(io.fromMainPipe.dsWrWayOH_s3.valid, io.fromMainPipe.dsWrWayOH_s3.bits, RegEnable(wrWayOH_sinkC_s2, wen_sinkC_s2))
    
    val wen_refill_s3     = if (latchTempDsToDs) { wen_s3 && RegEnable(wen_refill_s2, false.B, fire_s2) } else wen_refill_s2
    val wrData_refill_s3  = if (latchTempDsToDs) { RegEnable(wrData_refill_s2, wen_refill_s2) } else wrData_refill_s2
    val wrSet_refill_s3   = if (latchTempDsToDs) { RegEnable(wrSet_refill_s2, wen_refill_s2) } else wrSet_refill_s2
    val wrWayOH_refill_s3 = if (latchTempDsToDs) { RegEnable(wrWayOH_refill_s2, wen_refill_s2) } else wrWayOH_refill_s2
    // @formatter:on

    val wrSet_s3   = Mux(wen_refill_s3, wrSet_refill_s3, wrSet_sinkC_s3)
    val wrWayOH_s3 = Mux(wen_refill_s3, wrWayOH_refill_s3, wrWayOH_sinkC_s3)
    val wrData_s3  = Mux(wen_refill_s3, wrData_refill_s3, wrData_sinkC_s3)

    val fire_s3 = ren_s3 || wen_s3

    _assert(PopCount(Cat(wen_sinkC_s3, wen_refill_s3)) <= 1.U, "multiple write! wen_sinkC_s3:%d, wen_refill_s3:%d", wen_sinkC_s3, wen_refill_s3)
    assert(!(wen_s3 && ren_s3 && wayConflict), "read and write at the same time with wayConflict! wen_sinkC_s3:%d, wen_refill_s3:%d", wen_sinkC_s3, wen_refill_s3)

    dataSRAMs.zipWithIndex.foreach { case (srams, wayIdx) =>
        val wrWayEn = wrWayOH_s3(wayIdx)
        val rdWayEn = rdWayOH_s3(wayIdx)

        srams.zipWithIndex.foreach { case (sram, groupIdx) =>
            sram.io.w.req.valid       := wen_s3 && wrWayEn
            sram.io.w.req.bits.setIdx := wrSet_s3
            sram.io.w.req.bits.waymask.foreach(_ := 1.U)
            (0 until (groupBytes / eccProtectBytes)).foreach { i =>
                val wrGroupDatas = wrData_s3(groupBytes * 8 * (groupIdx + 1) - 1, groupBytes * 8 * groupIdx)
                sram.io.w.req.bits.data(0)(i) := dataCode.encode(wrGroupDatas(eccProtectBytes * 8 * (i + 1) - 1, eccProtectBytes * 8 * i))
            }

            sram.io.r.req.valid       := ren_s3 && rdWayEn
            sram.io.r.req.bits.setIdx := rdSet_s3

            _assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "dataSRAM write request not ready!")
            _assert(!(sram.io.r.req.valid && !sram.io.r.req.ready), "dataSRAM read request not ready!")
        }
    }

    io.dsWrite_s2.ready := !wen_s3 && !ren_s3

    // -----------------------------------------------------------------------------------------
    // Stage 4 (read accept)
    // -----------------------------------------------------------------------------------------
    val valid_s4     = RegNext(fire_s3, false.B)
    val wen_s4       = valid_s4 && RegEnable(wen_s3, false.B, fire_s3)
    val ren_s4       = valid_s4 && RegEnable(ren_s3, false.B, fire_s3)
    val wrWayOH_s4   = RegEnable(wrWayOH_s3, wen_s3)
    val rdWayOH_s4   = RegEnable(rdWayOH_s3, ren_s3)
    val rdDest_s4    = RegEnable(rdDest_s3, ren_s3)
    val rdMshrIdx_s4 = RegEnable(rdMshrIdx_s3, ren_s3)

    val hasAccess_s3   = wen_s3 || ren_s3
    val accessWayOH_s3 = Mux(wen_s3, wrWayOH_s3, rdWayOH_s3)
    val accessWayOH_s4 = Mux(wen_s4, wrWayOH_s4, rdWayOH_s4)
    wayConflict := RegNext(hasAccess_s3) && (accessWayOH_s3 & accessWayOH_s4).orR
    sramReady   := !wayConflict

    /**
     * It is permitted that [[DataStorage]] can be access by different wayOH during the consective cycles.
     * However, it is not permitted that [[DataStorage]] is accessed by the same wayOH during the consective cycle.
     */
    val wen_sinkC_s4  = RegNext(wen_sinkC_s3, false.B)
    val wen_refill_s4 = RegNext(wen_refill_s3, false.B)
    _assert(
        !((wen_s3 || ren_s3) && !sramReady),
        "sram is not ready! wen_s3:%d(wen_sinkC_s3:%d wen_refill_s3:%d), ren_s3:%d, wen_s4:%d(wen_sinkC_s4:%d wen_refill_s4:%d), ren_s4:%d",
        wen_s3,
        wen_sinkC_s3,
        wen_refill_s3,
        ren_s3,
        wen_s4,
        wen_sinkC_s4,
        wen_refill_s4,
        ren_s4
    )

    // -----------------------------------------------------------------------------------------
    // Stage 5 (read finish)
    // -----------------------------------------------------------------------------------------
    val ren_s5          = RegNext(ren_s4, false.B)
    val rdWayOH_s5      = RegEnable(rdWayOH_s4, ren_s4)
    val rdData_s5       = WireInit(0.U(dataBits.W))
    val rdDest_s5       = RegEnable(rdDest_s4, ren_s4)
    val rdMshrIdx_s5    = RegEnable(rdMshrIdx_s4, ren_s4)
    val fire_s5         = ren_s5 && rdDest_s5 =/= DataDestination.TempDataStorage
    val rdDataRawVec_s5 = VecInit(dataSRAMs.zipWithIndex.map { case (srams, wayIdx) => VecInit(srams.map(_.io.r.resp.data(0))) })
    val rdDataRaw_s5    = Mux1H(rdWayOH_s5, rdDataRawVec_s5)
    val rdDataVec_s5 = VecInit(rdDataRaw_s5.map { dataVec =>
        VecInit(dataVec.map { d =>
            dataCode.decode(d).uncorrected
        }).asUInt
    })

    rdData_s5                      := rdDataVec_s5.asUInt
    io.toTempDS.write_s5.valid     := rdDest_s5 === DataDestination.TempDataStorage && ren_s5
    io.toTempDS.write_s5.bits.data := rdData_s5 // rdData without ECC bits, ECC check is located in TempDataStorage, so we don't need to add ECC bits here
    io.toTempDS.write_s5.bits.idx  := rdMshrIdx_s5
    if (enableDataECC) {
        io.toTempDS.eccVec_s5 := VecInit(
            rdDataRaw_s5.map { dataVec =>
                dataVec.asTypeOf(Vec(groupBytes / 8, UInt(dataWithECCBits.W))).map(d => d.head(dataEccBits)).asUInt
            }
        ).asUInt.asTypeOf(Vec(blockBytes / eccProtectBytes, UInt(dataEccBits.W)))
    } else {
        io.toTempDS.eccVec_s5 := DontCare
    }

    // -----------------------------------------------------------------------------------------
    // Stage 6 (data output) / (ECC check)
    // -----------------------------------------------------------------------------------------
    val ren_s7_dup           = WireInit(false.B)
    val readToSourceD_s7_dup = WireInit(false.B)
    val readToTXDAT_s7_dup   = WireInit(false.B)

    val ren_s6           = RegInit(false.B)
    val rdData_s6        = WireInit(0.U(dataBits.W))
    val rdDataRaw_s6     = RegEnable(rdDataRaw_s5, fire_s5)
    val rdDest_s6        = RegEnable(rdDest_s5, fire_s5)
    val readToSourceD_s6 = rdDest_s6 === DataDestination.SourceD
    val readToTXDAT_s6   = rdDest_s6 === DataDestination.TXDAT
    val fire_s6          = ren_s6 && ready_s7 && (io.toSourceD.dsResp_s6s7.valid && !io.toSourceD.dsResp_s6s7.ready || io.toTXDAT.dsResp_s6s7.valid && !io.toTXDAT.dsResp_s6s7.ready)

    val rdDataVecDecoded_s6       = rdDataRaw_s6.map(dataVec => dataVec.map(d => dataCode.decode(d)))
    val rdDataVec_s6              = VecInit(rdDataVecDecoded_s6.map(decodedDataVec => VecInit(decodedDataVec.map(decodedData => Mux(decodedData.correctable, decodedData.corrected, decodedData.uncorrected))).asUInt))
    val rdDataHasErr_s6           = VecInit(rdDataVecDecoded_s6.map(decodedDataVec => VecInit(decodedDataVec.map(decodedData => decodedData.error)).asUInt)).asUInt.orR
    val rdDataHasUncorrectable_s6 = VecInit(rdDataVecDecoded_s6.map(decodedDataVec => VecInit(decodedDataVec.map(decodedData => decodedData.uncorrectable)).asUInt)).asUInt.orR

    rdData_s6 := rdDataVec_s6.asUInt

    // TODO: ECC info should be saved in some kind of CSR registers. For now, just ignore it.
    if (enableDataECC) {
        assert(
            !(ren_s6 && rdDataHasErr_s6 && rdDataHasUncorrectable_s6),
            "TODO: Data has error rdDataVec_s6:%x, rdDataHasErr_s6:%d, rdDataHasUncorrectable_s6:%d",
            rdDataVec_s6.asUInt,
            rdDataHasErr_s6,
            rdDataHasUncorrectable_s6
        )

        io.eccError := ren_s6 && rdDataHasUncorrectable_s6
    } else {
        io.eccError := false.B
    }

    when(fire_s5) {
        ren_s6 := true.B
    }.elsewhen((io.toSourceD.dsResp_s6s7.fire && readToSourceD_s6 && !(ren_s7_dup && readToSourceD_s7_dup) || io.toTXDAT.dsResp_s6s7.fire && readToTXDAT_s6 && !(ren_s7_dup && readToTXDAT_s7_dup)) && !fire_s5 && ren_s6) {
        ren_s6 := false.B
    }.elsewhen(fire_s6 && !fire_s5) {
        ren_s6 := false.B
    }

    assert(!(fire_s5 && ren_s6), "stage 6 is full!")
    assert(!(ren_s6 && readToSourceD_s6 && readToTXDAT_s6))
    LeakChecker(ren_s6, !ren_s6, Some("ren_s6"), maxCount = deadlockThreshold)

    // -----------------------------------------------------------------------------------------
    // Stage 7 (data output)
    // -----------------------------------------------------------------------------------------
    val ren_s7           = RegInit(false.B)
    val rdData_s7        = RegEnable(rdData_s6, fire_s6)
    val rdDest_s7        = RegEnable(rdDest_s6, fire_s6)
    val readToTXDAT_s7   = rdDest_s7 === DataDestination.TXDAT
    val readToSourceD_s7 = rdDest_s7 === DataDestination.SourceD
    val fire_s7          = io.toTXDAT.dsResp_s6s7.fire && readToTXDAT_s7 || io.toSourceD.dsResp_s6s7.fire && readToSourceD_s7
    ren_s7_dup           := ren_s7
    readToSourceD_s7_dup := readToSourceD_s7
    readToTXDAT_s7_dup   := readToTXDAT_s7
    ready_s7             := !ren_s7

    when(fire_s6) {
        ren_s7 := true.B
    }.elsewhen(fire_s7 && ren_s7 && !fire_s6) {
        ren_s7 := false.B
    }

    assert(!(ren_s7 && readToSourceD_s7 && readToTXDAT_s7))
    LeakChecker(ren_s7, !ren_s7, Some("ren_s7"), maxCount = deadlockThreshold)

    io.toTXDAT.dsResp_s6s7.valid     := ren_s6 && readToTXDAT_s6 || ren_s7 && readToTXDAT_s7
    io.toTXDAT.dsResp_s6s7.bits.data := Mux(readToTXDAT_s7 && ren_s7, rdData_s7, rdData_s6)

    io.toSourceD.dsResp_s6s7.valid     := ren_s6 && readToSourceD_s6 || ren_s7 && readToSourceD_s7
    io.toSourceD.dsResp_s6s7.bits.data := Mux(readToSourceD_s7 && ren_s7, rdData_s7, rdData_s6)

    dontTouch(io)
}

object DataStorage extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new DataStorage()(config), name = "DataStorage", split = true)
}
