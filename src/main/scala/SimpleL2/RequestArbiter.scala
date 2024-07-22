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
        val taskCMO_s1    = Flipped(Decoupled(new TaskBundle))
        val taskReplay_s1 = Flipped(Decoupled(new TaskBundle))

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

        val mshrStatus     = Vec(nrMSHR, Input(new MshrStatus))
        val replayFreeCnt  = Input(UInt((log2Ceil(nrReplayEntry) + 1).W))
        val nonDataRespCnt = Input(UInt((log2Ceil(nrNonDataSourceDEntry) + 1).W))
        val mpStatus       = Input(new MpStatus)
        val resetFinish    = Input(Bool())
    })

    io <> DontCare

    val fire_s1             = WireInit(false.B)
    val ready_s1            = WireInit(false.B)
    val valid_s1            = WireInit(false.B)
    val mshrTaskFull_s1     = RegInit(false.B)
    val noSpaceForReplay_s1 = WireInit(false.B)

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

    val valid_s3 = RegInit(false.B)
    val set_s3   = RegInit(0.U(setBits.W))
    val tag_s3   = RegInit(0.U(tagBits.W))

    val taskSnoop_s1  = io.taskSnoop_s1.bits
    val taskReplay_s1 = io.taskReplay_s1.bits

    // -----------------------------------------------------------------------------------------
    // Stage 0
    // -----------------------------------------------------------------------------------------
    // TODO: block mshr
    io.taskMSHR_s0.ready := !mshrTaskFull_s1 && io.resetFinish

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val task_s1     = WireInit(0.U.asTypeOf(new TaskBundle))
    val mshrTask_s1 = Reg(new TaskBundle)

    when(io.taskMSHR_s0.fire) {
        mshrTask_s1     := io.taskMSHR_s0.bits
        mshrTaskFull_s1 := true.B
    }.elsewhen(fire_s1 && mshrTaskFull_s1) {
        mshrTaskFull_s1 := false.B
    }

    ready_s1 := !mshrTaskFull_s1

    def addrMatchVec(set: UInt, tag: UInt): UInt = {
        VecInit(io.mshrStatus.map { case s =>
            // s.valid && s.set === set && (s.reqTag === tag || s.needsRepl && s.metaTag === tag)
            s.valid && s.set === set && (s.reqTag === tag || s.needsRepl && s.metaTag === tag || s.lockWay && s.metaTag === tag)
        }).asUInt
    }

    def addrMatchVec_reqTag(set: UInt, tag: UInt): UInt = {
        VecInit(io.mshrStatus.map { case s =>
            s.valid && s.set === set && s.reqTag === tag
        }).asUInt
    }

    def noFreeWay(set: UInt): Bool = {
        val sameSet_s2     = valid_s2 && task_s2.isChannelA && task_s2.set === set
        val sameSet_s3     = RegNext(valid_s2 && task_s2.isChannelA, false.B) && RegEnable(task_s2.set, valid_s2) === set
        val mshrSameSetVec = VecInit(io.mshrStatus.map(s => s.valid && s.isChannelA && s.set === set))
        (PopCount(mshrSameSetVec) + sameSet_s2 + sameSet_s3) >= ways.U
    }

    def setConflict(set: UInt): Bool = {
        val sameSet_s2 = valid_s2 && (task_s2.isChannelA || task_s2.isMshrTask && task_s2.isReplTask) && task_s2.set === set
        val sameSet_s3 = RegNext(valid_s2 && (task_s2.isChannelA || task_s2.isMshrTask && task_s2.isReplTask), false.B) && RegEnable(task_s2.set, valid_s2) === set
        sameSet_s2 || sameSet_s3
    }

    val addrMatchVec_forSinkA         = addrMatchVec(io.taskSinkA_s1.bits.set, io.taskSinkA_s1.bits.tag)
    val addrMatchVec_forReplay        = addrMatchVec(io.taskReplay_s1.bits.set, io.taskReplay_s1.bits.tag)
    val addrMatchVec_forSnoop         = addrMatchVec(io.taskSnoop_s1.bits.set, io.taskSnoop_s1.bits.tag)
    val addrMatchVec_reqTag_forSnoop  = addrMatchVec_reqTag(io.taskSnoop_s1.bits.set, io.taskSnoop_s1.bits.tag)
    val addrMatchVec_reqTag_forReplay = addrMatchVec_reqTag(io.taskReplay_s1.bits.set, io.taskReplay_s1.bits.tag)

    // TODO: set block in stage 2 and stage 3 in replace of noFreeWay
    val mayReadDS_a_s1_dup            = WireInit(false.B)
    val mayReadDS_replay_s1_dup       = WireInit(false.B)
    val noFreeWay_forSinkA            = noFreeWay(io.taskSinkA_s1.bits.set)
    val noFreeWay_forReplay           = noFreeWay(io.taskReplay_s1.bits.set)
    val setConflict_forSinkA          = setConflict(io.taskSinkA_s1.bits.set)
    val setConflict_forReplay         = setConflict(io.taskReplay_s1.bits.set)
    val blockA_addrConflict           = (task_s1.set === task_s2.set && task_s1.tag === task_s2.tag) && valid_s2 || (task_s1.set === set_s3 && task_s1.tag === tag_s3) && valid_s3 || addrMatchVec_forSinkA.orR
    val blockA_addrConflict_forReplay = (io.taskReplay_s1.bits.set === task_s2.set && io.taskReplay_s1.bits.tag === task_s2.tag) && valid_s2 || (io.taskReplay_s1.bits.set === set_s3 && io.taskReplay_s1.bits.tag === tag_s3) && valid_s3 || addrMatchVec_forReplay.orR
    val blockA_mayReadDS_forSinkA =
        mayReadDS_a_s1_dup && (mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2) // We need to check if stage2 will read the DataStorage. If it is, we should not allow the stage1 request that will read the DataStorage go further to meet the requirement of multi-cycle path of DataSRAM.
    val blockA_mayReadDS_forReplay = mayReadDS_replay_s1_dup && (mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2)
    blockA_s1           := blockA_addrConflict || blockA_mayReadDS_forSinkA || io.fromSinkC.willWriteDS_s1 || noFreeWay_forSinkA || setConflict_forSinkA
    blockA_forReplay_s1 := blockA_addrConflict_forReplay || blockA_mayReadDS_forReplay || io.fromSinkC.willWriteDS_s1 || noFreeWay_forReplay || setConflict_forReplay

    def mshrBlockSnp(set: UInt, tag: UInt): UInt = {
        VecInit(io.mshrStatus.map { s =>
            s.valid && s.set === set && s.metaTag === tag && !s.dirHit && s.w_replResp && !s.w_rprobeack // Snoop nested WrteBackFull/Evict, mshr is waitting for ProbeAck since dirty data may exist in upstream cache.
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
            s.valid && s.set === set && s.metaTag === tag && !s.dirHit && !s.state.isInvalid && s.w_replResp && s.w_rprobeack && s.replGotDirty
        }).asUInt
        assert(PopCount(matchVec) <= 1.U)
        matchVec
    }

    /**
     * After MSHR receives the first beat of CompData, and before L2 receives GrantAck from L1, snoop of X should be **blocked**, 
     * because a slave should not issue a Probe if there is a pending GrantAck on the block according to TileLink spec.
     */
    val reqBlockSnp_forSnoop  = VecInit(io.mshrStatus.map { s => s.valid && s.set === taskSnoop_s1.set && s.reqTag === taskSnoop_s1.tag && !s.willFree && s.w_comp_first }).asUInt.orR
    val reqBlockSnp_forReplay = VecInit(io.mshrStatus.map { s => s.valid && s.set === taskReplay_s1.set && s.reqTag === taskReplay_s1.tag && !s.willFree && s.w_comp_first }).asUInt.orR

    val mshrBlockSnp_forSnoop  = mshrBlockSnp(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    val mshrBlockSnp_forReplay = mshrBlockSnp(taskReplay_s1.set, taskReplay_s1.tag).orR

    val blockB_mayReadDS     = mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2
    val setConflict_forSnoop = setConflict(io.taskSnoop_s1.bits.set)

    blockB_s1           := reqBlockSnp_forSnoop || mshrBlockSnp_forSnoop || blockB_mayReadDS || io.fromSinkC.willWriteDS_s1 || setConflict_forSnoop
    blockB_forReplay_s1 := reqBlockSnp_forReplay || mshrBlockSnp_forReplay || blockB_mayReadDS || io.fromSinkC.willWriteDS_s1 || setConflict_forReplay

    val noSpaceForNonDataResp = io.nonDataRespCnt >= (nrNonDataSourceDEntry - 1).U // No space for ReleaseAck to send out to SourceD

    blockC_s1 := mayReadDS_s2 || willWriteDS_s2 || willRefillDS_s2 || noSpaceForNonDataResp // TODO: Some snoop does not need data // This is used to meet the multi-cycle path of DataSRAM

    /** Task priority: MSHR > Replay > CMO > Snoop > SinkC > SinkA */
    val opcodeSinkC_s1 = io.taskSinkC_s1.bits.opcode
    val otherTasks_s1  = Seq(io.taskReplay_s1, io.taskCMO_s1, io.taskSnoop_s1, io.taskSinkC_s1, io.taskSinkA_s1)
    val chnlTask_s1    = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    val arb            = Module(new Arbiter(chiselTypeOf(chnlTask_s1.bits), otherTasks_s1.size))
    val arbTaskCMO     = arb.io.in(0)
    val arbTaskSnoop   = arb.io.in(1)
    val arbTaskSinkC   = arb.io.in(2)
    val arbTaskReplay  = arb.io.in(3)
    val arbTaskSinkA   = arb.io.in(4)
    io.taskCMO_s1    <> arbTaskCMO
    io.taskSnoop_s1  <> arbTaskSnoop
    io.taskSinkC_s1  <> arbTaskSinkC // TODO: Store Miss Release / PutPartial?
    io.taskReplay_s1 <> arbTaskReplay
    io.taskSinkA_s1  <> arbTaskSinkA
    arb.io.out       <> chnlTask_s1

    // TODO: CMO Task

    val blockReplay_s1 = MuxLookup(io.taskReplay_s1.bits.channel, false.B)(
        Seq(
            L2Channel.ChannelA -> blockA_forReplay_s1,
            L2Channel.ChannelB -> blockB_forReplay_s1
        )
    )

    arbTaskSnoop.bits.snpHitWriteBack := snpHitWriteBack(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    arbTaskSnoop.bits.snpGotDirty     := snpGotDirty(taskSnoop_s1.set, taskSnoop_s1.tag).orR
    arbTaskSnoop.valid                := io.taskSnoop_s1.valid && !noSpaceForReplay_s1 && !blockB_s1
    io.taskSnoop_s1.ready             := arbTaskSnoop.ready && !noSpaceForReplay_s1 && !blockB_s1

    arbTaskSinkC.valid    := io.taskSinkC_s1.valid && !blockC_s1
    io.taskSinkC_s1.ready := arbTaskSinkC.ready && !blockC_s1

    arbTaskReplay.valid    := io.taskReplay_s1.valid && !blockReplay_s1
    io.taskReplay_s1.ready := arbTaskReplay.ready && !blockReplay_s1

    arbTaskSinkA.valid    := io.taskSinkA_s1.valid && !noSpaceForReplay_s1 && !blockA_s1
    io.taskSinkA_s1.ready := arbTaskSinkA.ready && !noSpaceForReplay_s1 && !blockA_s1

    chnlTask_s1.ready := io.resetFinish && !mshrTaskFull_s1 && io.dirRead_s1.ready
    task_s1 := Mux(
        mshrTaskFull_s1,
        mshrTask_s1,
        chnlTask_s1.bits
    )
    dontTouch(task_s1)

    val tempDsToDs_s1       = (io.tempDsRead_s1.bits.dest & DataDestination.DataStorage).orR
    val mayReadDS_a_s1      = io.taskSinkA_s1.bits.opcode === AcquireBlock || io.taskSinkA_s1.bits.opcode === Get
    val mayReadDS_b_s1      = task_s1.isChannelB
    val mayReadDS_mshr_s1   = mshrTask_s1.isCHIOpcode && (mshrTask_s1.opcode === CopyBackWrData || mshrTask_s1.opcode === SnpRespData) && mshrTask_s1.channel === CHIChannel.TXDAT
    val mayReadDS_replay_s1 = io.taskReplay_s1.bits.opcode === AcquireBlock || io.taskReplay_s1.bits.opcode === Get
    mayReadDS_a_s1_dup      := mayReadDS_a_s1
    mayReadDS_replay_s1_dup := mayReadDS_replay_s1

    val dsReady_s1     = !mayReadDS_s2 && !willWriteDS_s2 && !willRefillDS_s2
    val tempDsReady_s1 = io.tempDsRead_s1.ready && (tempDsToDs_s1 && dsReady_s1 || !tempDsToDs_s1)
    val mshrTaskReady_s1 = Mux(
        mshrTask_s1.isReplTask,
        io.dirRead_s1.ready,
        (mshrTask_s1.readTempDs && tempDsReady_s1 || !mshrTask_s1.readTempDs) && (mayReadDS_mshr_s1 && dsReady_s1 || !mayReadDS_mshr_s1)
    )
    io.toSinkC.mayReadDS_s1    := mshrTaskFull_s1 && mayReadDS_mshr_s1
    io.toSinkC.willRefillDS_s1 := mshrTaskFull_s1 && tempDsToDs_s1 && mshrTask_s1.readTempDs && !mshrTask_s1.isReplTask
    valid_s1                   := chnlTask_s1.valid || mshrTaskFull_s1
    fire_s1                    := mshrTaskFull_s1 && mshrTaskReady_s1 || chnlTask_s1.fire

    io.dirRead_s1.valid         := fire_s1 && !task_s1.isMshrTask || fire_s1 && task_s1.isMshrTask && task_s1.isReplTask
    io.dirRead_s1.bits.set      := task_s1.set
    io.dirRead_s1.bits.tag      := task_s1.tag
    io.dirRead_s1.bits.mshrId   := task_s1.mshrId
    io.dirRead_s1.bits.replTask := task_s1.isMshrTask && task_s1.isReplTask

    io.tempDsRead_s1.valid     := mshrTaskFull_s1 && task_s1.readTempDs && fire_s1
    io.tempDsRead_s1.bits.idx  := task_s1.mshrId
    io.tempDsRead_s1.bits.dest := task_s1.tempDsDest
    io.dsWrSet_s1              := task_s1.set
    io.dsWrWayOH_s1            := task_s1.wayOH

    val fireVec_s1 = VecInit(Seq(io.taskSinkA_s1.fire, io.taskSinkC_s1.fire, io.taskSnoop_s1.fire, io.taskCMO_s1.fire, io.taskReplay_s1.fire)).asUInt
    assert(PopCount(fireVec_s1) <= 1.U, "fireVec_s1:%b", fireVec_s1)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val fire_s2           = WireInit(false.B)
    val tempDsToDs_s2     = (task_s2.tempDsDest & DataDestination.DataStorage).orR && task_s2.isMshrTask && task_s2.readTempDs
    val mayReadDS_a_s2    = task_s2.isChannelA && (task_s2.opcode === AcquireBlock || task_s2.opcode === Get || task_s2.opcode === AcquirePerm /* AcqurirePerm and hit may read DS data into TempDS */ )
    val mayReadDS_b_s2    = task_s2.isChannelB /* TODO: filter snoop opcode, some opcode does not need Data */
    val mayReadDS_mshr_s2 = task_s2.isMshrTask && !task_s2.readTempDs && !task_s2.isReplTask && task_s2.channel === CHIChannel.TXDAT

    mayReadDS_s2    := valid_s2 && (mayReadDS_a_s2 || mayReadDS_b_s2 || mayReadDS_mshr_s2)
    willRefillDS_s2 := valid_s2 && tempDsToDs_s2
    willWriteDS_s2  := io.fromSinkC.willWriteDS_s2

    when(fire_s1) {
        valid_s2 := true.B
        task_s2  := task_s1
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
    }

    // Channel C does not need to replay
    val mayReplayCnt = WireInit(0.U(io.replayFreeCnt.getWidth.W))
    val mayReplay_s1 = valid_s1 && !task_s1.isMshrTask && !task_s1.isChannelC
    val mayReplay_s2 = valid_s2 && !task_s2.isMshrTask && !task_s2.isChannelC
    val mayReplay_s3 = valid_s3 && !isMshrTask_s3 && !(channel_s3 === L2Channel.ChannelC)
    // mayReplayCnt        := PopCount(Cat(mayReplay_s1, mayReplay_s2, mayReplay_s3))
    mayReplayCnt        := PopCount(Cat(1.U, mayReplay_s2, mayReplay_s3)) // TODO:
    noSpaceForReplay_s1 := mayReplayCnt >= io.replayFreeCnt
    // TODO: extra entry for Snoop?

    dontTouch(io)
}

object RequestArbiter extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestArbiter()(config), name = "RequestArbiter", split = false)
}
