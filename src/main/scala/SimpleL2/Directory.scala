package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, RandomPriorityEncoder}
import SimpleL2.Configs._
import SimpleL2.Bundles._

object TLState {
    val width   = 2
    val INVALID = "b00".U(width.W)
    val BRANCH  = "b01".U(width.W)
    val TRUNK   = "b10".U(width.W)
    val TIP     = "b11".U(width.W)
}

object MixedState {
    val width = 3

    /** [[MixedState]]: MSB <-- | Meta[1:0] | Dirty | --> LSB */
    val I   = "b000".U(width.W) // 0  Invalid
    val BC  = "b010".U(width.W) // 2  Branch Clean
    val BD  = "b011".U(width.W) // 3  Branch Dirty
    val TTC = "b100".U(width.W) // 4  Trunk Clean
    val TTD = "b101".U(width.W) // 5  Trunk Dirty
    val TC  = "b110".U(width.W) // 6  Tip Clean
    val TD  = "b111".U(width.W) // 7  Tip Dirty

    def apply(dirty: Bool, state: UInt) = {
        require(state.getWidth == 2, s"widht is ${state.getWidth}")
        val mixedState = WireInit(0.U(MixedState.width.W))
        mixedState := Cat(state, dirty)
        mixedState
    }

    def cleanDirty(mixedState: UInt) = {
        Cat(mixedState(2, 1), 0.U(1.W))
    }

    def setDirty(mixedState: UInt) = {
        Cat(mixedState(2, 1), 1.U(1.W))
    }
}

class MixedState {
    val state = UInt(MixedState.width.W)

    def isDirty = state(0)
    def isBranch = state(2, 1) === TLState.BRANCH
    def isTrunk = state(2, 1) === TLState.TRUNK
    def isTip = state(2, 1) === TLState.TIP
    def isInvalid = state(2, 1) === TLState.INVALID
    def rawState = state(2, 1)
}

trait HasMixedState {
    val state = UInt(MixedState.width.W)

    def isDirty = state(0)
    def isBranch = state(2, 1) === TLState.BRANCH
    def isTrunk = state(2, 1) === TLState.TRUNK
    def isTip = state(2, 1) === TLState.TIP
    def isInvalid = state(2, 1) === TLState.INVALID
    def rawState = state(2, 1)
}

trait HadMixedStateOps {
    implicit class MixedStateOps(val state: UInt) {
        def widthCheck(x: UInt) = require(x.getWidth == MixedState.width)

        def isDirty = {
            widthCheck(state)
            state(0)
        }

        def isBranch = {
            widthCheck(state)
            state(2, 1) === TLState.BRANCH
        }

        def isTrunk = {
            widthCheck(state)
            state(2, 1) === TLState.TRUNK
        }

        def isTip = {
            widthCheck(state)
            state(2, 1) === TLState.TIP
        }

        def isInvalid = {
            widthCheck(state)
            state(2, 1) === TLState.INVALID
        }

        def rawState = {
            widthCheck(state)
            state(2, 1)
        }
    }
}

class DirectoryMetaEntryNoTag(implicit p: Parameters) extends L2Bundle {
    val dirty        = Bool()
    val state        = UInt(TLState.width.W)
    val fromPrefetch = Bool()
    val aliasOpt     = aliasBitsOpt.map(width => UInt(width.W))
    val clientsOH    = UInt(nrClients.W)
}

object DirectoryMetaEntryNoTag {
    def apply(dirty: Bool, state: UInt, alias: UInt, clientsOH: UInt, fromPrefetch: Bool)(implicit p: Parameters) = {
        require(state.getWidth == TLState.width)

        val meta = Wire(new DirectoryMetaEntryNoTag)
        meta.aliasOpt.map(_ := alias)
        meta.fromPrefetch := fromPrefetch
        meta.dirty        := dirty
        meta.state        := state
        meta.clientsOH    := clientsOH
        meta
    }
}

class DirectoryMetaEntry(implicit p: Parameters) extends L2Bundle with HasMixedState {
    val fromPrefetch = Bool()
    val tag          = UInt(tagBits.W)
    val aliasOpt     = aliasBitsOpt.map(width => UInt(width.W))
    val clientsOH    = UInt(nrClients.W)
    // val noData       = Bool() // TODO: Indicate that whether DataStorage has data, if L2 receive Comp from lower level, this fild will be set to true
}

object DirectoryMetaEntry {
    def apply(fromPrefetch: Bool, state: UInt, tag: UInt, aliasOpt: Option[UInt], clientsOH: UInt)(implicit p: Parameters) = {
        val meta = Wire(new DirectoryMetaEntry)
        meta.aliasOpt.map(_ := aliasOpt.getOrElse(0.U))
        meta.fromPrefetch := fromPrefetch
        meta.state        := state
        meta.tag          := tag
        meta.clientsOH    := clientsOH
        meta
    }

    def apply(tag: UInt, dirMetaEntryNoTag: DirectoryMetaEntryNoTag)(implicit p: Parameters) = {
        val meta = WireInit(0.U.asTypeOf(new DirectoryMetaEntry))
        meta.aliasOpt.map(_ := dirMetaEntryNoTag.aliasOpt.getOrElse(0.U))
        meta.fromPrefetch := dirMetaEntryNoTag.fromPrefetch
        meta.state        := MixedState(dirMetaEntryNoTag.dirty, dirMetaEntryNoTag.state)
        meta.tag          := tag
        meta.clientsOH    := dirMetaEntryNoTag.clientsOH
        meta
    }

    def apply()(implicit p: Parameters) = {
        val meta = WireInit(0.U.asTypeOf(new DirectoryMetaEntry))
        meta
    }
}

class DirRead(implicit p: Parameters) extends L2Bundle {
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
    val replTask = Bool()           // replacement task, which is used by mainpipe to find a suitable entry for replacement
    val mshrId   = UInt(mshrBits.W) // mshr ID for replacement task response to find the destination mshr
}

class DirWrite(implicit p: Parameters) extends L2Bundle {
    val set   = UInt(setBits.W)
    val meta  = new DirectoryMetaEntry
    val wayOH = UInt(ways.W)
}

class DirResp(implicit p: Parameters) extends L2Bundle {
    val meta  = new DirectoryMetaEntry
    val wayOH = UInt(ways.W)
    val hit   = Bool()
}

class DirReplResp(implicit p: Parameters) extends L2Bundle {
    val wayOH  = UInt(ways.W)
    val meta   = new DirectoryMetaEntry()
    val mshrId = UInt(mshrBits.W)
    val retry  = Bool() // if there is no free way for replacement, we need retry
}

class Directory()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val dirRead_s1  = Flipped(DecoupledIO(new DirRead))
        val dirWrite_s3 = Flipped(ValidIO(new DirWrite))
        val dirResp_s3  = ValidIO(new DirResp)
        val replResp_s3 = ValidIO(new DirReplResp)
        val mshrStatus  = Vec(nrMSHR, Input(new MshrStatus))
        val resetFinish = Output(Bool()) // reset finish is ASSERTED when all meta entries are reset
    })

    println(s"[${this.getClass().toString()}] DirectoryMetaEntry bits: ${(new DirectoryMetaEntry).getWidth}")

    // TODO: ECC

    io <> DontCare

    val resetIdx = RegInit(sets.U)

    val group = 2 // Combine two directory entries into one SRAM bank
    val metaSRAMs = Seq.fill(ways / group) {
        Module(
            new SRAMTemplate(
                gen = new DirectoryMetaEntry,
                set = sets,
                way = group,
                singlePort = true,
                multicycle = 1
            )
        )
    }

    // TODO: update replacer SRAM
    val repl = ReplacementPolicy.fromString(replacementPolicy, ways)
    
    // @formatter:off
    // val replacerSRAM_opt = if (replacementPolicy == "random") None else {
    //         Some(
    //             Module(
    //                 new BankedSRAM( // TODO:
    //                     gen = UInt(repl.nBits.W),
    //                     sets = sets,
    //                     ways = 1,
    //                     nrBank = 4,
    //                     singlePort = true,
    //                     shouldReset = true,
    //                     // hasMbist = false /* TODO */,
    //                     // hasShareBus = false /* TODO */,
    //                     hasClkGate = enableClockGate
    //                     // parentName = parentName + "repl_"
    //                 )
    //             )
    //         )
    //     }
    // @formatter:on

    // TODO: when should we update replacer SRAM
    // replacerSRAM_opt.foreach { sram =>
    //     sram.io <> DontCare
    //     dontTouch(sram.io)
    // }
    // ReplacerWen: updateHit || updateRepl(replace task)

    // replacerSRAM_opt.foreach { sram =>
    //     sram.io.w(
    //         valid = !io.resetFinish,
    //         data = 0.U, // TODO: replacer SRAM init value
    //         setIdx = resetIdx - 1.U,
    //         waymask = 1.U
    //     )

    //     assert(!(!io.resetFinish && sram.io.w.req.valid && !sram.io.w.req.ready))
    // }

    // -----------------------------------------------------------------------------------------
    // Stage 1(dir read) / Stage 3(dir write)
    // -----------------------------------------------------------------------------------------
    val sramRdReady = metaSRAMs.map(_.io.r.req.ready).reduce(_ & _)
    metaSRAMs.zipWithIndex.foreach { case (sram, i) =>
        sram.io.r.req.valid       := io.dirRead_s1.fire
        sram.io.r.req.bits.setIdx := io.dirRead_s1.bits.set

        val sramWayMask = io.dirWrite_s3.bits.wayOH(i * 2 + (group - 1), i * 2)
        sram.io.w.req.valid       := !io.resetFinish || io.dirWrite_s3.fire
        sram.io.w.req.bits.setIdx := Mux(io.resetFinish, io.dirWrite_s3.bits.set, resetIdx - 1.U)
        sram.io.w.req.bits.data   := VecInit(Seq.fill(group)(io.dirWrite_s3.bits.meta))
        sram.io.w.req.bits.waymask.foreach(_ := Mux(io.resetFinish, sramWayMask, Fill(group, 1.U)))
        assert(PopCount(sramWayMask) <= 1.U, "0b%b", sramWayMask)
    }

    io.dirRead_s1.ready := io.resetFinish && sramRdReady && !io.dirWrite_s3.fire

    // -----------------------------------------------------------------------------------------
    // Stage 2(dir read)
    // -----------------------------------------------------------------------------------------
    val metaRead_s2     = Wire(Vec(ways, new DirectoryMetaEntry()))
    val reqValid_s2     = RegNext(io.dirRead_s1.fire, false.B)
    val replReqValid_s2 = RegNext(io.dirRead_s1.fire && io.dirRead_s1.bits.replTask, false.B)
    val mshrId_s2       = RegEnable(io.dirRead_s1.bits.mshrId, io.dirRead_s1.fire && io.dirRead_s1.bits.replTask)
    val reqTag_s2       = RegEnable(io.dirRead_s1.bits.tag, io.dirRead_s1.fire)
    val reqSet_s2       = RegEnable(io.dirRead_s1.bits.set, io.dirRead_s1.fire)
    val occWayMask_s2 = VecInit(io.mshrStatus.map { mshr =>
        /* Only those set match and dirHit mshr can occupy a way */
        Mux(mshr.valid && mshr.set === reqSet_s2 && (mshr.dirHit || mshr.lockWay), mshr.wayOH, 0.U(ways.W))
    }).reduceTree(_ | _).asUInt // opt for timing, move from Stage 3 to Stage 2, cut the timing path between MSHR, Directory, MainPipe(Stage 3)
    metaRead_s2 := VecInit(metaSRAMs.map(_.io.r.resp.data)).asTypeOf(Vec(ways, new DirectoryMetaEntry()))

    // -----------------------------------------------------------------------------------------
    // Stage 3(dir read)
    // -----------------------------------------------------------------------------------------
    val metaRead_s3     = RegEnable(metaRead_s2, reqValid_s2)
    val reqValid_s3     = RegNext(reqValid_s2, false.B)
    val replReqValid_s3 = RegNext(replReqValid_s2, false.B)
    val mshrId_s3       = RegEnable(mshrId_s2, replReqValid_s2)
    val reqTag_s3       = RegEnable(reqTag_s2, reqValid_s2)
    val reqSet_s3       = RegEnable(reqSet_s2, reqValid_s2)
    val occWayMask_s3   = RegEnable(occWayMask_s2, reqValid_s2)
    val stateVec_s3     = VecInit(metaRead_s3.map(_.state))
    val tagVec_s3       = VecInit(metaRead_s3.map(_.tag))
    val invVec_s3       = VecInit(stateVec_s3.map(_ === MixedState.I)).asUInt

    val hitOH_s3    = VecInit(stateVec_s3.zip(tagVec_s3).map { case (state, tag) => state =/= MixedState.I && tag === reqTag_s3 }).asUInt
    val hit_s3      = hitOH_s3.orR
    val hasInv_s3   = invVec_s3.orR
    val invWayOH_s3 = PriorityEncoderOH(invVec_s3)

    val freeWayMask_s3 = ~occWayMask_s3
    val replRetry_s3   = occWayMask_s3.andR
    val noFreeWay_s3   = replRetry_s3

    val chosenWayOH_s3     = Mux(hasInv_s3, invWayOH_s3, UIntToOH(random.LFSR(3))(ways - 1, 0) /* TODO: Replacment way */ )
    val chosenWay_s3       = OHToUInt(chosenWayOH_s3)
    val randomChosenWay_s3 = RandomPriorityEncoder(freeWayMask_s3, reqValid_s2 || replReqValid_s2) /* Select a random way, as a way to prevent deadlock */
    val finalWay_s3 = Mux(
        freeWayMask_s3(chosenWay_s3),
        chosenWay_s3,
        // PriorityEncoder(freeWayMask_s3)
        randomChosenWay_s3
    )
    val finalWayOH_s3 = UIntToOH(finalWay_s3)
    val respWayOH_s3  = Mux(hit_s3, hitOH_s3, finalWayOH_s3)

    assert(!(io.resetFinish && PopCount(hitOH_s3) > 1.U))
    assert(!(io.resetFinish && PopCount(finalWayOH_s3) > 1.U))

    io.dirResp_s3.valid      := reqValid_s3
    io.dirResp_s3.bits.hit   := hit_s3
    io.dirResp_s3.bits.meta  := Mux1H(respWayOH_s3, metaRead_s3)
    io.dirResp_s3.bits.wayOH := respWayOH_s3

    io.replResp_s3.valid       := replReqValid_s3
    io.replResp_s3.bits.mshrId := mshrId_s3
    io.replResp_s3.bits.retry  := replRetry_s3
    io.replResp_s3.bits.meta   := Mux1H(finalWayOH_s3, metaRead_s3)
    io.replResp_s3.bits.wayOH  := finalWayOH_s3

    /** 
     * Reset all SRAM data when reset. 
     * If not reset, all the SRAM data(including meta info) will be X.
     * [[Directory]] should not be accessed before reset finished.
     */
    when(!io.resetFinish) { resetIdx := resetIdx - 1.U }
    io.resetFinish := resetIdx === 0.U && !reset.asBool

    when(io.resetFinish) {
        val sramWrReady      = metaSRAMs.map(_.io.w.req.ready).reduce(_ & _)
        val dirWriteReady_s3 = io.resetFinish && sramWrReady
        assert(!(io.dirWrite_s3.valid && !dirWriteReady_s3))
        assert(!(io.dirWrite_s3.valid && !sramWrReady), "dirWrite_s3 while metaSRAM is not ready!")
        assert(!(io.dirWrite_s3.valid && PopCount(io.dirWrite_s3.bits.wayOH) > 1.U))
    }
    when(!io.resetFinish) {
        assert(!io.dirRead_s1.fire, "cannot read directory while not reset finished!")
        assert(!io.dirWrite_s3.fire, "cannot write directory while not reset finished!")
        metaSRAMs.foreach { sram =>
            assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "write metaSRAM should always be ready!")
        }
    }

    dontTouch(io)
}

object Directory extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Directory()(config), name = "Directory", split = true)
}
