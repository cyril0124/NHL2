package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink.TLMessages._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._
import freechips.rocketchip.util.SeqToAugmentedSeq
import SimpleL2.chi.CHIOpcodeDAT._

class RequestArbiter()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {

        /** [[MSHR]] request */
        val taskMSHR_s0 = Flipped(Decoupled(new TaskBundle)) // Hard wire to MSHRs

        /** Channel request */
        val taskSinkA_s1 = Flipped(Decoupled(new TaskBundle))
        val taskSinkC_s1 = Flipped(Decoupled(new TaskBundle))
        val taskSnoop_s1 = Flipped(Decoupled(new TaskBundle))

        /** Other request */
        val taskCMO_s1 = Flipped(Decoupled(new TaskBundle))

        /** Read directory */
        val dirRead_s1 = Decoupled(new DirRead)

        /** Read [[TempDataStorage]] */
        val tempDsRead_s1 = DecoupledIO(new TempDataReadReq)
        val dsWrSet_s1    = Output(UInt(setBits.W))
        val dsWrWayOH_s1  = Output(UInt(ways.W))

        /** Send task to [[MainPipe]] */
        val mpReq_s2 = ValidIO(new TaskBundle)

        /** Other signals */
        val fromSinkC = new Bundle {
            val willWriteDS_s1 = Input(Bool())
            val willWriteDS_s2 = Input(Bool())
        }
        val toSinkC = new Bundle {
            val mayReadDS_s1    = Output(Bool())
            val willRefillDS_s1 = Output(Bool())
            val mayReadDS_s2    = Output(Bool())
            val willRefillDS_s2 = Output(Bool())
        }

        val mshrStatus         = Vec(nrMSHR, Input(new MshrStatus))
        val replayFreeCntSinkA = Input(UInt((log2Ceil(nrReplayEntrySinkA) + 1).W))
        val replayFreeCntSnoop = Input(UInt((log2Ceil(nrReplayEntrySnoop) + 1).W))
        val nonDataRespCnt     = Input(UInt((log2Ceil(nrNonDataSourceDEntry) + 1).W))
        val mpStatus           = Input(new MpStatus)
        val bufferStatus       = Input(new BufferStatusSourceD) // from SourceD
        val resetFinish        = Input(Bool())
    })

    io <> DontCare

    val task_s1                 = WireInit(0.U.asTypeOf(new TaskBundle))
    val fire_s1                 = WireInit(false.B)
    val ready_s1                = WireInit(false.B)
    val valid_s1                = WireInit(false.B)
    val mshrTaskFull_s1         = RegInit(false.B)
    val noSpaceForReplay_a_s1   = WireInit(false.B)
    val noSpaceForReplay_snp_s1 = WireInit(false.B)

    val blockA_s1           = WireInit(false.B)
    val blockA_forReplay_s1 = WireInit(false.B)

    val blockB_s1           = WireInit(false.B)
    val blockB_forReplay_s1 = WireInit(false.B)

    val blockC_s1 = WireInit(false.B)

    val task_s2         = RegInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2        = RegInit(false.B)
    val mayReadDS_s2    = WireInit(false.B)
    val willWriteDS_s2  = WireInit(false.B)
    val willRefillDS_s2 = WireInit(false.B)
    val willWriteDS_s3  = WireInit(false.B)
    val willRefillDS_s3 = WireInit(false.B)
    val snpHitReq_s2    = RegInit(false.B)

    val valid_s3     = RegInit(false.B)
    val set_s3       = RegInit(0.U(setBits.W))
    val tag_s3       = RegInit(0.U(tagBits.W))
    val snpHitReq_s3 = RegInit(false.B)

    val arbTaskSnoop_dup_s1 = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    val taskSnoop_s1        = io.taskSnoop_s1.bits

    // -----------------------------------------------------------------------------------------
    // Stage 0
    // -----------------------------------------------------------------------------------------
    val mshrSet_s0        = io.taskMSHR_s0.bits.set
    val mshrTag_s0        = io.taskMSHR_s0.bits.tag
    val hasSnpHitReq_s1   = arbTaskSnoop_dup_s1.bits.snpHitReq && arbTaskSnoop_dup_s1.bits.set === mshrSet_s0 && arbTaskSnoop_dup_s1.bits.tag === mshrTag_s0 && arbTaskSnoop_dup_s1.valid
    val hasSnpHitReq_s2   = valid_s2 && snpHitReq_s2 && task_s2.set === mshrSet_s0 && task_s2.tag === mshrTag_s0
    val hasSnpHitReq_s3   = valid_s3 && snpHitReq_s3 && set_s3 === mshrSet_s0 && tag_s3 === mshrTag_s0
    val blockSnpHitReq_s0 = hasSnpHitReq_s1 || hasSnpHitReq_s2 || hasSnpHitReq_s3 || RegNext(hasSnpHitReq_s3, false.B) // block mpTask_refill to wait snpHitReq which may need mshr reallocation or send snpresp directly
    io.taskMSHR_s0.ready := !mshrTaskFull_s1 && io.resetFinish && !(blockSnpHitReq_s0 && !io.taskMSHR_s0.bits.isCHIOpcode && !io.taskMSHR_s0.bits.isReplTask)

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val mshrTask_s1 = Reg(new TaskBundle)

    when(io.taskMSHR_s0.fire) {
        mshrTask_s1     := io.taskMSHR_s0.bits
        mshrTaskFull_s1 := true.B
    }.elsewhen(fire_s1 && mshrTaskFull_s1) {
        mshrTaskFull_s1 := false.B
    }

    ready_s1 := !mshrTaskFull_s1

    def addrConflict(set: UInt, tag: UInt): Bool = {
        val mshrAddrConflict = VecInit(io.mshrStatus.map { case s =>
            s.valid && s.set === set && (s.reqTag === tag || s.needsRepl && s.metaTag === tag || s.lockWay && s.metaTag === tag)
        }).asUInt.orR

        val mpAddrConflict = VecInit(io.mpStatus.elements.map { case (name: String, stage: MpStageInfo) =>
            stage.valid && stage.isRefill && stage.set === set && stage.tag === tag
        }.toSeq).asUInt.orR

        val bufferAddrConflict = io.bufferStatus.valid && io.bufferStatus.set === set && io.bufferStatus.tag === tag

        mshrAddrConflict || mpAddrConflict || bufferAddrConflict
    }

    def noFreeWay(set: UInt): Bool = {
        val sameSet_s2     = valid_s2 && task_s2.isChannelA && task_s2.set === set
        val sameSet_s3     = RegNext(valid_s2 && task_s2.isChannelA, false.B) && RegEnable(task_s2.set, valid_s2) === set
        val mshrSameSetVec = VecInit(io.mshrStatus.map(s => s.valid && s.isChannelA && s.set === set))
        (PopCount(mshrSameSetVec) + sameSet_s2 + sameSet_s3) >= ways.U
    }

    def setConflict(channel: String, set: UInt, tag: UInt): Bool = {
        // setConflict for channel tasks
        // ! This function only covers stage2 and stage3. Other stages should be covered by other signals.
        // TODO: optimize this
        if (channel == "A") {
            val sameSet_s2 = valid_s2 && (!task_s2.isMshrTask || task_s2.isMshrTask && task_s2.isReplTask) && task_s2.set === set
            val sameSet_s3 = RegNext(valid_s2 && (!task_s2.isMshrTask || task_s2.isMshrTask && task_s2.isReplTask), false.B) && RegEnable(task_s2.set, valid_s2) === set
            sameSet_s2 || sameSet_s3
        } else if (channel == "B") {
            val sameSet_s2 = valid_s2 && (!task_s2.isMshrTask && task_s2.tag === tag || task_s2.isMshrTask && task_s2.isReplTask) && task_s2.set === set
            val sameSet_s3 = RegNext(valid_s2, false.B) && RegEnable(task_s2.set, valid_s2) === set && Mux(RegNext(!task_s2.isMshrTask, false.B), RegEnable(task_s2.tag, valid_s2) === tag, RegNext(task_s2.isReplTask, false.B))
            sameSet_s2 || sameSet_s3
        } else {
            assert(false, "invalid channel => " + channel)
            false.B
        }
    }

    def setConflict_forRepl(set: UInt): Bool = {
        // MSHR repl task should be blocked if stage2 and stage3 have channel tasks since channel tasks will update the directory info; otherwise, repl task will get outdated directory info.
        val sameSet_s2 = valid_s2 && (!task_s2.isMshrTask || task_s2.isMshrTask && task_s2.isReplTask) && task_s2.set === set
        val sameSet_s3 = RegNext(valid_s2 && (!task_s2.isMshrTask || task_s2.isMshrTask && task_s2.isReplTask), false.B) && RegEnable(task_s2.set, valid_s2) === set
        sameSet_s2 || sameSet_s3
    }

    val addrConflict_forSinkA = addrConflict(io.taskSinkA_s1.bits.set, io.taskSinkA_s1.bits.tag)
    val addrConflict_forSnoop = addrConflict(io.taskSnoop_s1.bits.set, io.taskSnoop_s1.bits.tag)

    // TODO: set block in stage 2 and stage 3 in replace of noFreeWay
    val mayReadDS_a_s1_dup   = WireInit(false.B)
    val noFreeWay_forSinkA   = noFreeWay(io.taskSinkA_s1.bits.set)
    val setConflict_forSinkA = setConflict("A", io.taskSinkA_s1.bits.set, io.taskSinkA_s1.bits.tag)
    val blockA_addrConflict  = (io.taskSinkA_s1.bits.set === task_s2.set && io.taskSinkA_s1.bits.tag === task_s2.tag) && valid_s2 || (io.taskSinkA_s1.bits.set === set_s3 && io.taskSinkA_s1.bits.tag === tag_s3) && valid_s3 || addrConflict_forSinkA
    val blockA_mayReadDS_forSinkA =
        mayReadDS_a_s1_dup && (mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2) // We need to check if stage2 will read the DataStorage. If it is, we should not allow the stage1 request that will read the DataStorage go further to meet the requirement of multi-cycle path of DataSRAM.
    blockA_s1 := blockA_addrConflict || blockA_mayReadDS_forSinkA || io.fromSinkC.willWriteDS_s1 || noFreeWay_forSinkA || setConflict_forSinkA

    def mshrBlockSnp(set: UInt, tag: UInt): UInt = {
        VecInit(io.mshrStatus.map { s =>
            s.valid && s.set === set && (s.metaTag === tag && !s.dirHit && s.w_replResp && !s.w_rprobeack || s.reqTag === tag && s.waitGrantAck) // Snoop nested WrteBackFull/Evict, mshr is waitting for ProbeAck since dirty data may exist in upstream cache.
        }).asUInt
    }

    def snpHitWriteBack(set: UInt, tag: UInt): UInt = {
        val matchVec = VecInit(io.mshrStatus.map { s =>
            s.valid && s.set === set && s.metaTag === tag && !s.dirHit && !s.state.isInvalid && s.w_replResp && s.w_rprobeack && (!s.w_evict_comp || !s.w_compdbid)
        }).asUInt
        assert(PopCount(matchVec) <= 1.U)
        matchVec
    }

    def snpGotDirty(set: UInt, tag: UInt): UInt = {
        val matchVec = VecInit(io.mshrStatus.map { s =>
            s.valid && s.set === set && (s.reqTag === tag && s.gotDirtyData || s.metaTag === tag && !s.state.isInvalid && s.w_replResp && s.w_rprobeack && s.replGotDirty)
        }).asUInt
        assert(PopCount(matchVec) <= 1.U)
        matchVec
    }

    def snpHitReq(set: UInt, tag: UInt): UInt = {
        val matchVec = VecInit(io.mshrStatus.map { s =>
            s.valid && s.set === set && s.reqTag === tag && s.reqAllowSnoop
        }).asUInt
        assert(PopCount(matchVec) <= 1.U)
        matchVec
    }

    /**
     * After MSHR receives the first beat of CompData, and before L2 receives GrantAck from L1, snoop of X should be **blocked**, 
     * because a slave should not issue a Probe if there is a pending GrantAck on the block according to TileLink spec.
     */
    val reqBlockSnp_forSnoop = VecInit(io.mshrStatus.map { s => s.valid && s.set === taskSnoop_s1.set && s.reqTag === taskSnoop_s1.tag && !s.willFree && !s.reqAllowSnoop }).asUInt.orR

    val mshrBlockSnp_forSnoop = mshrBlockSnp(taskSnoop_s1.set, taskSnoop_s1.tag).orR

    val blockB_mayReadDS     = mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2
    val setConflict_forSnoop = setConflict("B", io.taskSnoop_s1.bits.set, io.taskSnoop_s1.bits.tag)

    blockB_s1 := reqBlockSnp_forSnoop || mshrBlockSnp_forSnoop || blockB_mayReadDS || io.fromSinkC.willWriteDS_s1 || setConflict_forSnoop

    val noSpaceForNonDataResp = io.nonDataRespCnt >= (nrNonDataSourceDEntry - 1).U // No space for ReleaseAck to send out to SourceD

    blockC_s1 := mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2 || noSpaceForNonDataResp // TODO: Some snoop does not need data // This is used to meet the multi-cycle path of DataSRAM

    /** Task priority: MSHR > CMO > SinkC > Snoop > Replay > SinkA */
    val opcodeSinkC_s1 = io.taskSinkC_s1.bits.opcode
    val otherTasks_s1  = Seq(io.taskCMO_s1, io.taskSnoop_s1, io.taskSinkC_s1, io.taskSinkA_s1)
    val chnlTask_s1    = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    val arb            = Module(new Arbiter(chiselTypeOf(chnlTask_s1.bits), otherTasks_s1.size))
    val arbTaskCMO     = arb.io.in(0) // TODO: CMO Task
    val arbTaskSinkC   = arb.io.in(1)
    val arbTaskSnoop   = arb.io.in(2)
    val arbTaskSinkA   = arb.io.in(3)
    io.taskCMO_s1   <> arbTaskCMO
    io.taskSinkC_s1 <> arbTaskSinkC // TODO: Store Miss Release / PutPartial?
    io.taskSnoop_s1 <> arbTaskSnoop
    io.taskSinkA_s1 <> arbTaskSinkA
    arb.io.out      <> chnlTask_s1

    arbTaskSnoop_dup_s1               := arbTaskSnoop
    arbTaskSnoop.bits.snpHitWriteBack := snpHitWriteBack(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    arbTaskSnoop.bits.snpGotDirty     := snpGotDirty(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    arbTaskSnoop.bits.snpHitReq       := snpHitReq(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    arbTaskSnoop.bits.snpHitMshrId    := OHToUInt(snpHitReq(taskSnoop_s1.set, taskSnoop_s1.tag))
    arbTaskSnoop.bits.readTempDs      := Mux1H(snpHitReq(taskSnoop_s1.set, taskSnoop_s1.tag), io.mshrStatus.map(_.gotDirtyData)) || taskSnoop_s1.retToSrc
    arbTaskSnoop.valid                := io.taskSnoop_s1.valid && !noSpaceForReplay_snp_s1 && !blockB_s1 && Mux(arbTaskSnoop.bits.readTempDs, io.tempDsRead_s1.ready, true.B)
    io.taskSnoop_s1.ready             := arbTaskSnoop.ready && !noSpaceForReplay_snp_s1 && !blockB_s1 && Mux(arbTaskSnoop.bits.readTempDs, io.tempDsRead_s1.ready, true.B)
    assert(!(arbTaskSnoop.valid && arbTaskSnoop.bits.snpHitWriteBack && arbTaskSnoop.bits.snpHitReq), "snpHitWriteBack and snpHitReq should not be both true")

    arbTaskSinkC.valid    := io.taskSinkC_s1.valid && !blockC_s1
    io.taskSinkC_s1.ready := arbTaskSinkC.ready && !blockC_s1

    arbTaskSinkA.valid    := io.taskSinkA_s1.valid && !noSpaceForReplay_a_s1 && !blockA_s1
    io.taskSinkA_s1.ready := arbTaskSinkA.ready && !noSpaceForReplay_a_s1 && !blockA_s1

    chnlTask_s1.ready := io.resetFinish && !mshrTaskFull_s1 && Mux(!chnlTask_s1.bits.snpHitReq, io.dirRead_s1.ready, true.B)
    task_s1 := Mux(
        mshrTaskFull_s1,
        mshrTask_s1,
        chnlTask_s1.bits
    )
    dontTouch(task_s1)

    val tempDsToDs_s1     = (io.tempDsRead_s1.bits.dest & DataDestination.DataStorage).orR
    val mayReadDS_a_s1    = io.taskSinkA_s1.bits.opcode === AcquireBlock || io.taskSinkA_s1.bits.opcode === Get || io.taskSinkA_s1.bits.opcode === AcquirePerm
    val mayReadDS_b_s1    = task_s1.isChannelB
    val mayReadDS_mshr_s1 = (mshrTask_s1.isCHIOpcode && (mshrTask_s1.opcode === CopyBackWrData || mshrTask_s1.opcode === SnpRespData) && mshrTask_s1.channel === CHIChannel.TXDAT) || (!mshrTask_s1.isCHIOpcode && (mshrTask_s1.opcode === GrantData || mshrTask_s1.opcode === AccessAckData))
    mayReadDS_a_s1_dup := mayReadDS_a_s1

    val dsReady_s1             = !mayReadDS_s2 && !willWriteDS_s2 && !willRefillDS_s2
    val tempDsReady_s1         = io.tempDsRead_s1.ready && (tempDsToDs_s1 && dsReady_s1 || !tempDsToDs_s1)
    val setConflict_forRepl_s1 = setConflict_forRepl(mshrTask_s1.set)
    val mshrTaskReady_s1 = Mux(
        mshrTask_s1.isReplTask,
        io.dirRead_s1.ready && !setConflict_forRepl_s1, // MSHR repl task should be blocked if stage2 and stage3 have channel tasks since channel tasks will update the directory info; otherwise, repl task will get outdated directory info.
        (mshrTask_s1.readTempDs && tempDsReady_s1 || !mshrTask_s1.readTempDs) && (mayReadDS_mshr_s1 && dsReady_s1 || !mayReadDS_mshr_s1)
    )
    io.toSinkC.mayReadDS_s1    := mshrTaskFull_s1 && mayReadDS_mshr_s1
    io.toSinkC.willRefillDS_s1 := mshrTaskFull_s1 && tempDsToDs_s1 && mshrTask_s1.readTempDs && !mshrTask_s1.isReplTask
    valid_s1                   := chnlTask_s1.valid || mshrTaskFull_s1
    fire_s1                    := mshrTaskFull_s1 && mshrTaskReady_s1 || chnlTask_s1.fire

    io.dirRead_s1.valid         := fire_s1 && (!task_s1.isMshrTask || task_s1.isMshrTask && task_s1.isReplTask) && !task_s1.snpHitReq
    io.dirRead_s1.bits.set      := task_s1.set
    io.dirRead_s1.bits.tag      := task_s1.tag
    io.dirRead_s1.bits.mshrId   := task_s1.mshrId
    io.dirRead_s1.bits.replTask := task_s1.isMshrTask && task_s1.isReplTask

    io.tempDsRead_s1.valid     := mshrTaskFull_s1 && mshrTaskReady_s1 && mshrTask_s1.readTempDs || arbTaskSnoop.bits.snpHitReq && arbTaskSnoop.bits.readTempDs && io.taskSnoop_s1.fire
    io.tempDsRead_s1.bits.idx  := Mux(arbTaskSnoop.bits.snpHitReq && !mshrTaskFull_s1, arbTaskSnoop.bits.snpHitMshrId, mshrTask_s1.mshrId)
    io.tempDsRead_s1.bits.dest := Mux(arbTaskSnoop.bits.snpHitReq && !mshrTaskFull_s1, DataDestination.TXDAT, mshrTask_s1.tempDsDest)
    io.dsWrSet_s1              := task_s1.set
    io.dsWrWayOH_s1            := task_s1.wayOH

    val fireVec_s1 = VecInit(Seq(io.taskSinkA_s1.fire, io.taskSinkC_s1.fire, io.taskSnoop_s1.fire, io.taskCMO_s1.fire)).asUInt
    assert(PopCount(fireVec_s1) <= 1.U, "fireVec_s1:%b", fireVec_s1)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val fire_s2           = WireInit(false.B)
    val tempDsToDs_s2     = (task_s2.tempDsDest & DataDestination.DataStorage).orR && task_s2.isMshrTask && task_s2.readTempDs
    val mayReadDS_a_s2    = task_s2.isChannelA && (task_s2.opcode === AcquireBlock || task_s2.opcode === Get || task_s2.opcode === AcquirePerm /* AcqurirePerm and hit may read DS data into TempDS */ )
    val mayReadDS_b_s2    = task_s2.isChannelB /* TODO: filter snoop opcode, some opcode does not need Data */
    val mayReadDS_mshr_s2 = task_s2.isMshrTask && !task_s2.readTempDs && !task_s2.isReplTask && (task_s2.channel === CHIChannel.TXDAT || (!task_s2.isCHIOpcode && (task_s2.opcode === GrantData || task_s2.opcode === AccessAckData)))

    mayReadDS_s2    := valid_s2 && (mayReadDS_a_s2 || mayReadDS_b_s2 || mayReadDS_mshr_s2)
    willRefillDS_s2 := valid_s2 && tempDsToDs_s2
    willWriteDS_s2  := io.fromSinkC.willWriteDS_s2

    when(fire_s1) {
        valid_s2     := true.B
        task_s2      := task_s1
        snpHitReq_s2 := task_s1.snpHitReq && task_s1.isChannelB
    }.elsewhen(fire_s2 && valid_s2) {
        valid_s2 := false.B
    }

    fire_s2           := io.mpReq_s2.fire
    io.mpReq_s2.valid := valid_s2
    io.mpReq_s2.bits  := task_s2

    io.toSinkC.mayReadDS_s2    := mayReadDS_s2
    io.toSinkC.willRefillDS_s2 := willRefillDS_s2

    // -----------------------------------------------------------------------------------------
    // Stage 3
    // -----------------------------------------------------------------------------------------
    val isMshrTask_s3 = RegInit(false.B)
    val channel_s3    = RegInit(0.U(L2Channel.width.W))
    willWriteDS_s3  := RegNext(willWriteDS_s2)
    willRefillDS_s3 := RegNext(willRefillDS_s2)
    valid_s3        := fire_s2

    when(fire_s2) {
        isMshrTask_s3 := task_s2.isMshrTask
        channel_s3    := task_s2.channel
        set_s3        := task_s2.set
        tag_s3        := task_s2.tag
        snpHitReq_s3  := snpHitReq_s2
    }

    // Channel C does not need to replay
    val mayReplayCnt_a = WireInit(0.U(io.replayFreeCntSinkA.getWidth.W))
    val mayReplay_a_s2 = valid_s2 && !task_s2.isMshrTask && !task_s2.isChannelC && task_s2.isChannelA
    val mayReplay_a_s3 = valid_s3 && !isMshrTask_s3 && !(channel_s3 === L2Channel.ChannelC) && channel_s3 === L2Channel.ChannelA
    mayReplayCnt_a        := PopCount(Cat(1.U, mayReplay_a_s2, mayReplay_a_s3)) // TODO:
    noSpaceForReplay_a_s1 := mayReplayCnt_a >= io.replayFreeCntSinkA

    val mayReplayCnt_snp = WireInit(0.U(io.replayFreeCntSnoop.getWidth.W))
    val mayReplay_snp_s2 = valid_s2 && !task_s2.isMshrTask && !task_s2.isChannelC
    val mayReplay_snp_s3 = valid_s3 && !isMshrTask_s3 && !(channel_s3 === L2Channel.ChannelC)
    mayReplayCnt_snp        := PopCount(Cat(1.U, mayReplay_snp_s2, mayReplay_snp_s3)) // TODO:
    noSpaceForReplay_snp_s1 := mayReplayCnt_snp >= io.replayFreeCntSnoop

    dontTouch(io)
}

object RequestArbiter extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestArbiter()(config), name = "RequestArbiter", split = false)
}
