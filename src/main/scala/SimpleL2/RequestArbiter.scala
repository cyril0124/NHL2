package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

// TODO: Replay, stall on noSpaceForReplay

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

        val resetFinish = Input(Bool())
    })

    io <> DontCare

    val fire_s1  = WireInit(false.B)
    val ready_s1 = WireInit(false.B)
    val valid_s1 = WireInit(false.B)

    // TODO: stage 2 may need read/write DataStorage

    // -----------------------------------------------------------------------------------------
    // Stage 0
    // -----------------------------------------------------------------------------------------
    // TODO: block mshr
    io.taskMSHR_s0.ready := !valid_s1 && io.resetFinish

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    val task_s1       = WireInit(0.U.asTypeOf(new TaskBundle))
    val taskMSHR_s1   = Reg(new TaskBundle)
    val isTaskMSHR_s1 = RegInit(false.B)

    when(io.taskMSHR_s0.fire) {
        taskMSHR_s1   := io.taskMSHR_s0.bits
        isTaskMSHR_s1 := true.B
    }.elsewhen(fire_s1 && isTaskMSHR_s1) {
        isTaskMSHR_s1 := false.B
    }

    valid_s1 := isTaskMSHR_s1
    ready_s1 := !isTaskMSHR_s1

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

    chosenTask_s1.ready := io.resetFinish && io.dirRead_s1.ready && !isTaskMSHR_s1
    task_s1 := Mux(
        isTaskMSHR_s1,
        taskMSHR_s1,
        chosenTask_s1.bits
    )
    dontTouch(task_s1)

    fire_s1 := io.dirRead_s1.ready && (valid_s1 && Mux(task_s1.readTempDs, io.tempDsRead_s1.ready, true.B) || chosenTask_s1.fire)

    io.dirRead_s1.valid         := fire_s1 && !task_s1.isMshrTask || fire_s1 && task_s1.isMshrTask && task_s1.isReplTask
    io.dirRead_s1.bits.set      := task_s1.set
    io.dirRead_s1.bits.tag      := task_s1.tag
    io.dirRead_s1.bits.mshrId   := task_s1.mshrId
    io.dirRead_s1.bits.replTask := task_s1.isMshrTask && task_s1.isReplTask

    io.tempDsRead_s1.valid     := isTaskMSHR_s1 && task_s1.readTempDs && valid_s1
    io.tempDsRead_s1.bits.idx  := task_s1.mshrId
    io.tempDsRead_s1.bits.dest := task_s1.tempDsDest
    io.dsWrSet_s1              := task_s1.set
    io.dsWrWayOH_s1            := task_s1.wayOH

    val fireVec_s1 = VecInit(Seq(io.taskSinkA_s1.fire, io.taskSinkC_s1.fire, io.taskSnoop_s1.fire, io.taskCMO_s1.fire, io.taskReplay_s1.fire))
    dontTouch(fireVec_s1)
    assert(PopCount(fireVec_s1.asUInt) <= 1.U)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val task_s2  = RegInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2 = RegInit(false.B)
    val fire_s2  = WireInit(false.B)
    val data_s2  = Reg(UInt((beatBytes * 8).W))

    when(fire_s1) {
        valid_s2 := true.B
        task_s2  := task_s1
    }.elsewhen(fire_s2 && valid_s2) {
        valid_s2 := false.B
    }

    fire_s2           := io.mpReq_s2.fire
    io.mpReq_s2.valid := valid_s2
    io.mpReq_s2.bits  := task_s2

    dontTouch(io)
}

object RequestArbiter extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RequestArbiter()(config), name = "RequestArbiter", split = false)
}
