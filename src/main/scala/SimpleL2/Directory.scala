package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, RandomPriorityEncoderOH}
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
    val dirty           = Bool()
    val state           = UInt(TLState.width.W)
    val clientsOH       = UInt(nrClients.W)
    val aliasOpt        = aliasBitsOpt.map(width => UInt(width.W))
    val fromPrefetchOpt = if (hasPrefetchBit) Some(Bool()) else None
    val prefetchSrcOpt  = if (hasPrefetchSrc) Some(UInt(coupledL2.prefetch.PfSource.pfSourceBits.W)) else None
}

object DirectoryMetaEntryNoTag {
    def apply(dirty: Bool, state: UInt, alias: UInt, clientsOH: UInt, fromPrefetchOpt: Option[Bool] = None, prefetchSrcOpt: Option[UInt] = None)(implicit p: Parameters) = {
        require(state.getWidth == TLState.width)

        val meta = Wire(new DirectoryMetaEntryNoTag)
        meta.aliasOpt.foreach(_ := alias)
        meta.fromPrefetchOpt.foreach(_ := fromPrefetchOpt.getOrElse(false.B))
        meta.prefetchSrcOpt.foreach(_ := prefetchSrcOpt.getOrElse(0.U))
        meta.dirty     := dirty
        meta.state     := state
        meta.clientsOH := clientsOH
        meta
    }
}

class DirectoryMetaEntry(implicit p: Parameters) extends L2Bundle with HasMixedState {
    val tag             = UInt(tagBits.W)
    val clientsOH       = UInt(nrClients.W)
    val aliasOpt        = aliasBitsOpt.map(width => UInt(width.W))
    val fromPrefetchOpt = if (hasPrefetchBit) Some(Bool()) else None
    val prefetchSrcOpt  = if (hasPrefetchSrc) Some(UInt(coupledL2.prefetch.PfSource.pfSourceBits.W)) else None
}

object DirectoryMetaEntry {
    def apply(state: UInt, tag: UInt, aliasOpt: Option[UInt], clientsOH: UInt, fromPrefetchOpt: Option[Bool] = None, prefetchSrcOpt: Option[UInt] = None)(implicit p: Parameters) = {
        val meta = Wire(new DirectoryMetaEntry)
        meta.aliasOpt.foreach(_ := aliasOpt.getOrElse(0.U))
        meta.fromPrefetchOpt.foreach(_ := fromPrefetchOpt.getOrElse(false.B))
        meta.prefetchSrcOpt.foreach(_ := prefetchSrcOpt.getOrElse(0.U))
        meta.state     := state
        meta.tag       := tag
        meta.clientsOH := clientsOH
        meta
    }

    def apply(tag: UInt, dirMetaEntryNoTag: DirectoryMetaEntryNoTag)(implicit p: Parameters) = {
        val meta = WireInit(0.U.asTypeOf(new DirectoryMetaEntry))
        meta.aliasOpt.foreach(_ := dirMetaEntryNoTag.aliasOpt.getOrElse(0.U))
        meta.fromPrefetchOpt.foreach(_ := dirMetaEntryNoTag.fromPrefetchOpt.getOrElse(false.B))
        meta.prefetchSrcOpt.foreach(_ := dirMetaEntryNoTag.prefetchSrcOpt.getOrElse(0.U))
        meta.state     := MixedState(dirMetaEntryNoTag.dirty, dirMetaEntryNoTag.state)
        meta.tag       := tag
        meta.clientsOH := dirMetaEntryNoTag.clientsOH
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

    val updateReplacer = Bool() // Indicates that whether the directory read request should update the replacer.
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

    /** If there is no free way, we need to schecule a replacement task in [[MSHR]] even the cacheline state is INVALID */
    val needsRepl = Bool()
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

    val isRandomRepl = replacementPolicy == "random"
    val repl         = ReplacementPolicy.fromString(replacementPolicy, ways)
    val replacerSRAM_opt =
        if (isRandomRepl) None
        else {
            val width = if (isPow2(repl.nBits)) repl.nBits else (repl.nBits + 1) // Make sure repl.nBits is a power of 2
            Some(
                Module(
                    new SRAMTemplate(
                        gen = UInt(width.W),
                        set = sets,
                        way = 1,
                        singlePort = true,
                        multicycle = 1
                    )
                )
            )
        }

    println(s"[${this.getClass().toString()}] DirectoryMetaEntry bits: ${(new DirectoryMetaEntry).getWidth}")
    println(s"[${this.getClass().toString()}] replacementPolicy: ${replacementPolicy} replacerSRAM entry bits: ${if (isRandomRepl) "None" else repl.nBits}")

    // -----------------------------------------------------------------------------------------
    // Stage 1(dir read)
    // -----------------------------------------------------------------------------------------
    val fire_s1          = io.dirRead_s1.fire
    val sramReadReady_s1 = metaSRAMs.map(_.io.r.req.ready).reduce(_ & _)
    metaSRAMs.zipWithIndex.foreach { case (sram, i) =>
        sram.io.r.req.valid       := io.dirRead_s1.fire
        sram.io.r.req.bits.setIdx := io.dirRead_s1.bits.set
    }

    // -----------------------------------------------------------------------------------------
    // Stage 2(dir read)
    // -----------------------------------------------------------------------------------------
    val metaRead_s2       = Wire(Vec(ways, new DirectoryMetaEntry()))
    val reqValid_s2       = RegNext(fire_s1, false.B)
    val replReqValid_s2   = reqValid_s2 && RegEnable(io.dirRead_s1.bits.replTask, false.B, fire_s1)
    val mshrId_s2         = RegEnable(io.dirRead_s1.bits.mshrId, fire_s1)
    val reqTag_s2         = RegEnable(io.dirRead_s1.bits.tag, fire_s1)
    val reqSet_s2         = RegEnable(io.dirRead_s1.bits.set, fire_s1)
    val updateReplacer_s2 = RegEnable(io.dirRead_s1.bits.updateReplacer, fire_s1)
    val occWayMask_s2 = VecInit(io.mshrStatus.map { mshr =>
        /* Only those set match and dirHit mshr can occupy a way */
        Mux(mshr.valid && mshr.set === reqSet_s2 && (mshr.dirHit || mshr.lockWay), mshr.wayOH, 0.U(ways.W))
    }).reduceTree(_ | _).asUInt // opt for timing, move from Stage 3 to Stage 2, cut the timing path between MSHR, Directory, MainPipe(Stage 3)
    val randomChosenWayOH_s2 = RandomPriorityEncoderOH(~occWayMask_s2, reqValid_s2 || replReqValid_s2) /* Select a random way, as a way to prevent deadlock */

    metaRead_s2 := VecInit(metaSRAMs.map(_.io.r.resp.data)).asTypeOf(Vec(ways, new DirectoryMetaEntry()))

    // -----------------------------------------------------------------------------------------
    // Stage 3(get dir read result) / (dir write)
    // -----------------------------------------------------------------------------------------
    val metaRead_s3       = RegEnable(metaRead_s2, reqValid_s2)
    val reqValid_s3       = RegNext(reqValid_s2, false.B)
    val replReqValid_s3   = reqValid_s3 && RegEnable(replReqValid_s2, false.B, reqValid_s2)
    val mshrId_s3         = RegEnable(mshrId_s2, reqValid_s2)
    val reqTag_s3         = RegEnable(reqTag_s2, reqValid_s2)
    val reqSet_s3         = RegEnable(reqSet_s2, reqValid_s2)
    val updateReplacer_s3 = RegEnable(updateReplacer_s2, reqValid_s2)
    val occWayMask_s3     = RegEnable(occWayMask_s2, reqValid_s2)
    val stateVec_s3       = VecInit(metaRead_s3.map(_.state))
    val tagVec_s3         = VecInit(metaRead_s3.map(_.tag))
    val invVec_s3         = VecInit(stateVec_s3.map(_ === MixedState.I)).asUInt

    val hitOH_s3    = VecInit(stateVec_s3.zip(tagVec_s3).map { case (state, tag) => state =/= MixedState.I && tag === reqTag_s3 }).asUInt
    val hit_s3      = hitOH_s3.orR
    val hasInv_s3   = invVec_s3.orR
    val invWayOH_s3 = PriorityEncoderOH(invVec_s3)

    val freeWayMask_s3 = ~occWayMask_s3
    val replRetry_s3   = occWayMask_s3.andR
    val noFreeWay_s3   = replRetry_s3

    val replacerState_s3 = if (isRandomRepl) {
        when(io.dirWrite_s3.fire) {
            repl.miss // Update the replacer state for random replacement policy
        }
        0.U
    } else {
        val replacerResult_s2 = replacerSRAM_opt.get.io.r(io.dirRead_s1.fire, io.dirRead_s1.bits.set).resp.data(0)(repl.nBits - 1, 0)
        val _replacerState_s3 = RegEnable(replacerResult_s2, 0.U(repl.nBits.W), reqValid_s2)
        _replacerState_s3
    }

    val victimWay_s3         = repl.get_replace_way(replacerState_s3)
    val victimWayOH_s3       = UIntToOH(victimWay_s3)
    val chosenWayOH_s3       = Mux(hasInv_s3, invWayOH_s3, victimWayOH_s3)
    val randomChosenWayOH_s3 = RegEnable(randomChosenWayOH_s2, reqValid_s2 || replReqValid_s2)
    val isFreeWay_s3         = (freeWayMask_s3 & chosenWayOH_s3).orR
    val finalWayOH_s3        = Mux(isFreeWay_s3, chosenWayOH_s3, randomChosenWayOH_s3)
    val respWayOH_s3         = Mux(hit_s3, hitOH_s3, finalWayOH_s3)
    val way_s3               = OHToUInt(Mux(replReqValid_s3, finalWayOH_s3, respWayOH_s3)) // This is used by replacerSRAM

    assert(!(io.resetFinish && reqValid_s3 && PopCount(hitOH_s3) > 1.U))
    assert(!(io.resetFinish && reqValid_s3 && PopCount(finalWayOH_s3) > 1.U))

    io.dirResp_s3.valid          := reqValid_s3
    io.dirResp_s3.bits.hit       := hit_s3
    io.dirResp_s3.bits.meta      := Mux1H(respWayOH_s3, metaRead_s3)
    io.dirResp_s3.bits.wayOH     := respWayOH_s3
    io.dirResp_s3.bits.needsRepl := noFreeWay_s3

    io.replResp_s3.valid       := replReqValid_s3
    io.replResp_s3.bits.mshrId := mshrId_s3
    io.replResp_s3.bits.retry  := replRetry_s3
    io.replResp_s3.bits.meta   := Mux1H(finalWayOH_s3, metaRead_s3)
    io.replResp_s3.bits.wayOH  := finalWayOH_s3

    /**
     * MetaSRAMs should be updated on the following conditions:
     *  1. When io.dirWrite_s3.valid is true.
     *  2. When the reset is not finished and we should write an initial value to every SRAM entry to avoid X propagation.
     */
    metaSRAMs.zipWithIndex.foreach { case (sram, i) =>
        val sramWayMask = io.dirWrite_s3.bits.wayOH(i * 2 + (group - 1), i * 2)
        sram.io.w.req.valid       := !io.resetFinish || io.dirWrite_s3.fire
        sram.io.w.req.bits.setIdx := Mux(io.resetFinish, io.dirWrite_s3.bits.set, resetIdx - 1.U)
        sram.io.w.req.bits.data   := Mux(io.resetFinish, VecInit(Seq.fill(group)(io.dirWrite_s3.bits.meta)), VecInit(Seq.fill(group)(0.U.asTypeOf(new DirectoryMetaEntry))))
        sram.io.w.req.bits.waymask.foreach(_ := Mux(io.resetFinish, sramWayMask, Fill(group, 1.U)))
        assert(PopCount(sramWayMask) <= 1.U, "0b%b", sramWayMask)
    }

    /**
     * ReplacerSRAM should be updated on the following conditions:
     *  1. When the directory is under reseting. We should assign a initial value to the SRAM to avoid X propagation.
     *  2. When the directory result is hit and the request is an Acquire* request from ChannelA.
     *  3. When the directory has been read due to MSHR replTask, which means that the MSHR is going to chose a victim way for Evict/Writeback and the corresponding ReplacerSRAM entry should be updated.
     */
    val replacerUpdateOnHit_s3  = reqValid_s3 && !replReqValid_s3 && hit_s3 && updateReplacer_s3
    val replacerUpdateOnRepl_s3 = replReqValid_s3 && !replRetry_s3
    val replacerUpdate_s3       = (replacerUpdateOnHit_s3 || replacerUpdateOnRepl_s3) && !isRandomRepl.B
    val replacerInitialState_s3 = if (replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru") {
        0.U
    } else {
        // TODO: Other replacement policy.(e.g. rrip)
        assert(false, s"Unimplemented replacement policy: ${replacementPolicy}")
        0.U
    }
    replacerSRAM_opt.foreach { sram =>
        val replacerWrData = if (isPow2(repl.nBits)) repl.get_next_state(replacerState_s3, way_s3) else Cat(0.U(1.W), repl.get_next_state(replacerState_s3, way_s3))
        sram.io.w(
            valid = !io.resetFinish || replacerUpdate_s3,
            data = Mux(io.resetFinish, replacerWrData, replacerInitialState_s3),
            setIdx = Mux(io.resetFinish, reqSet_s3, resetIdx - 1.U),
            waymask = 1.U
        )
        assert(!(io.resetFinish && sram.io.r.req.valid && !sram.io.r.req.ready), "replacerSRAM is not ready for read!")
        assert(!(sram.io.w.req.valid && !sram.io.w.req.ready), "replacerSRAM is not ready for write!")
    }

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

    /**
     * Cannot access directory on the following conditions:
     *  1. Directory reset is not finished.
     *  2. MetaSRAMs are not allow to be read.
     *  3. There is a directory write operation on metaSRAMs.
     *  4. ReplacerSRAM is updating.  
     */
    io.dirRead_s1.ready := io.resetFinish && sramReadReady_s1 && !io.dirWrite_s3.fire && !replacerUpdate_s3

    dontTouch(io)
}

object Directory extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Directory()(config), name = "Directory", split = true)
}
