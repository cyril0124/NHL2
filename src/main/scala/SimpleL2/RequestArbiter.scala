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
import SimpleL2.chi.CHIOpcodeDAT.CopyBackWrData

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
        val willRefillWrite_s1         = Output(Bool()) // to DataStorage
        val mayReadDS_s1               = Output(Bool()) // to DataStorage
        val probeAckDataWillWriteDS_s2 = Input(Bool())
        val willReadDS_s3              = Input(Bool())
        val mshrStatus                 = Vec(nrMSHR, Input(new MshrStatus))
        val replayFreeCnt              = Input(UInt((log2Ceil(nrReplayEntry) + 1).W))
        val mpStatus                   = Input(new MpStatus)
        val resetFinish                = Input(Bool())
    })

    io <> DontCare

    val fire_s1             = WireInit(false.B)
    val ready_s1            = WireInit(false.B)
    val valid_s1            = WireInit(false.B)
    val mshrTaskFull_s1     = RegInit(false.B)
    val blockA_s1           = WireInit(false.B)
    val blockA_forReplay_s1 = WireInit(false.B)
    val blockB_s1           = WireInit(false.B)
    val blockB_forReplay_s1 = WireInit(false.B)
    val blockC_s1           = WireInit(false.B)
    val noSpaceForReplay_s1 = WireInit(false.B)
    val mayReadDS_s2        = WireInit(false.B)
    val willWriteDS_s2      = WireInit(false.B)
    val willRefillDS_s2     = WireInit(false.B)
    val willWriteDS_s3      = WireInit(false.B)
    val willRefillDS_s3     = WireInit(false.B)
    val willReadDS_s3       = io.willReadDS_s3

    val valid_s3 = RegInit(false.B)
    val set_s3   = RegInit(0.U(setBits.W))
    val tag_s3   = RegInit(0.U(tagBits.W))

    // -----------------------------------------------------------------------------------------
    // Stage 0
    // -----------------------------------------------------------------------------------------
    // TODO: block mshr
    io.taskMSHR_s0.ready := !mshrTaskFull_s1 && io.resetFinish

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val task_s1     = WireInit(0.U.asTypeOf(new TaskBundle))
    val taskMSHR_s1 = Reg(new TaskBundle)

    when(io.taskMSHR_s0.fire) {
        taskMSHR_s1     := io.taskMSHR_s0.bits
        mshrTaskFull_s1 := true.B
    }.elsewhen(fire_s1 && mshrTaskFull_s1) {
        mshrTaskFull_s1 := false.B
    }

    ready_s1 := !mshrTaskFull_s1

    /** Task priority: MSHR > Replay > CMO > Snoop > SinkC > SinkA */
    val opcodeSinkC_s1 = io.taskSinkC_s1.bits.opcode
    val otherTasks_s1  = Seq(io.taskReplay_s1, io.taskCMO_s1, io.taskSnoop_s1, io.taskSinkC_s1, io.taskSinkA_s1)
    val chosenTask_s1  = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle)))
    val arb            = Module(new Arbiter(chiselTypeOf(chosenTask_s1.bits), otherTasks_s1.size))
    io.taskReplay_s1 <> arb.io.in(0)
    io.taskCMO_s1    <> arb.io.in(1)
    io.taskSnoop_s1  <> arb.io.in(2)
    io.taskSinkC_s1  <> arb.io.in(3) // TODO: Store Miss Release / PutPartial?
    io.taskSinkA_s1  <> arb.io.in(4)
    arb.io.out       <> chosenTask_s1

    // TODO: CMO Task

    val blockReplay_s1 = MuxLookup(io.taskReplay_s1.bits.channel, false.B)(
        Seq(
            L2Channel.ChannelA -> blockA_forReplay_s1,
            L2Channel.ChannelB -> blockB_forReplay_s1
        )
    )

    arb.io.in(0).valid     := io.taskReplay_s1.valid && !blockReplay_s1
    io.taskReplay_s1.ready := arb.io.in(0).ready && !blockReplay_s1

    arb.io.in(2).valid    := io.taskSnoop_s1.valid && !blockB_s1
    io.taskSnoop_s1.ready := arb.io.in(2).ready && !blockB_s1

    arb.io.in(3).valid    := io.taskSinkC_s1.valid && !blockC_s1
    io.taskSinkC_s1.ready := arb.io.in(3).ready && !blockC_s1

    arb.io.in(4).valid    := io.taskSinkA_s1.valid && !blockA_s1
    io.taskSinkA_s1.ready := arb.io.in(4).ready && !blockA_s1

    val mayReadDS_a_s1 = io.taskSinkA_s1.bits.opcode === AcquireBlock || io.taskSinkA_s1.bits.opcode === Get

    // TODO: block when no space for Replay
    chosenTask_s1.ready := io.resetFinish && !mshrTaskFull_s1 && io.dirRead_s1.ready && Mux(!chosenTask_s1.bits.isReplayTask, !noSpaceForReplay_s1, true.B)
    task_s1 := Mux(
        mshrTaskFull_s1,
        taskMSHR_s1,
        chosenTask_s1.bits
    )
    dontTouch(task_s1)

    val tempDsToDs_s1 = (io.tempDsRead_s1.bits.dest & DataDestination.DataStorage).orR
    valid_s1 := chosenTask_s1.valid || mshrTaskFull_s1
    fire_s1 := mshrTaskFull_s1 && Mux(
        task_s1.readTempDs,
        io.tempDsRead_s1.ready && (tempDsToDs_s1 && !mayReadDS_s2 && !willReadDS_s3 && !willWriteDS_s2 && !willWriteDS_s3 && !willRefillDS_s2 && !willRefillDS_s3 || !tempDsToDs_s1),
        true.B
    ) && Mux(task_s1.isReplTask, io.dirRead_s1.ready, true.B) || chosenTask_s1.fire

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

    io.willRefillWrite_s1 := mshrTaskFull_s1 && task_s1.readTempDs && tempDsToDs_s1
    io.mayReadDS_s1       := valid_s1 && (task_s1.isChannelA && mayReadDS_a_s1 || task_s1.isChannelB) || mshrTaskFull_s1 && task_s1.isCHIOpcode && task_s1.opcode === CopyBackWrData && task_s1.channel === CHIChannel.TXDAT

    val fireVec_s1 = VecInit(Seq(io.taskSinkA_s1.fire, io.taskSinkC_s1.fire, io.taskSnoop_s1.fire, io.taskCMO_s1.fire, io.taskReplay_s1.fire))
    dontTouch(fireVec_s1)
    assert(PopCount(fireVec_s1.asUInt) <= 1.U)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val task_s2       = RegInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2      = RegInit(false.B)
    val fire_s2       = WireInit(false.B)
    val tempDsToDs_s2 = (task_s2.tempDsDest & DataDestination.DataStorage).orR && task_s2.isMshrTask && task_s2.readTempDs
    mayReadDS_s2    := valid_s2 && ((task_s2.isChannelA && task_s2.opcode === AcquireBlock || task_s2.opcode === Get) || task_s2.isChannelB /* TODO: filter snoop opcode, some opcode does not need Data */ )
    willRefillDS_s2 := valid_s2 && tempDsToDs_s2
    willWriteDS_s2  := valid_s2 && (task_s2.isChannelC && task_s2.opcode === ReleaseData) || valid_s2 && io.probeAckDataWillWriteDS_s2

    when(fire_s1) {
        valid_s2 := true.B
        task_s2  := task_s1
    }.elsewhen(fire_s2 && valid_s2) {
        valid_s2 := false.B
    }

    fire_s2           := io.mpReq_s2.fire
    io.mpReq_s2.valid := valid_s2
    io.mpReq_s2.bits  := task_s2

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

    def addrMatchVec(set: UInt, tag: UInt): UInt = {
        VecInit(io.mshrStatus.map { case s =>
            s.valid && s.set === set && s.reqTag === tag
        }).asUInt
    }

    val addrMatchVec_sinka  = addrMatchVec(io.taskSinkA_s1.bits.set, io.taskSinkA_s1.bits.tag)
    val addrMatchVec_replay = addrMatchVec(io.taskReplay_s1.bits.set, io.taskReplay_s1.bits.tag)
    val addrMatchVec_snp    = addrMatchVec(io.taskSnoop_s1.bits.set, io.taskSnoop_s1.bits.tag)

    val blockA_addrConflict           = (task_s1.set === task_s2.set && task_s1.tag === task_s2.tag) && valid_s2 || (task_s1.set === set_s3 && task_s1.tag === tag_s3) && valid_s3 || addrMatchVec_sinka.orR
    val blockA_addrConflict_forReplay = (io.taskReplay_s1.bits.set === task_s2.set && io.taskReplay_s1.bits.tag === task_s2.tag) && valid_s2 || (io.taskReplay_s1.bits.set === set_s3 && io.taskReplay_s1.bits.tag === tag_s3) && valid_s3 || addrMatchVec_replay.orR
    val blockA_mayReadDS              = mayReadDS_a_s1 && mayReadDS_s2
    blockA_s1           := blockA_addrConflict || blockA_mayReadDS
    blockA_forReplay_s1 := blockA_addrConflict_forReplay || blockA_mayReadDS

    val reqBlockSnp           = addrMatchVec_snp.orR
    val reqBlockSnp_forReplay = addrMatchVec_replay.orR
    blockB_s1           := reqBlockSnp
    blockB_forReplay_s1 := reqBlockSnp_forReplay

    blockC_s1 := willRefillDS_s2 || willWriteDS_s2 // This is used to meet the multi-cycle path of DataSRAM

    // Channel C does not need to replay
    val mayReplayCnt = WireInit(0.U(io.replayFreeCnt.getWidth.W))
    val mayReplay_s1 = valid_s1 && !task_s1.isMshrTask && !task_s1.isChannelC
    val mayReplay_s2 = valid_s2 && !task_s2.isMshrTask && !task_s2.isChannelC
    val mayReplay_s3 = valid_s3 && !isMshrTask_s3 && !(channel_s3 === L2Channel.ChannelC)
    mayReplayCnt        := PopCount(Cat(mayReplay_s1, mayReplay_s2, mayReplay_s3))
    noSpaceForReplay_s1 := mayReplayCnt >= io.replayFreeCnt

    dontTouch(io)
}

object RequestArbiter extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestArbiter()(config), name = "RequestArbiter", split = false)
}
