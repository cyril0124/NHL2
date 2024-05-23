package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

// TODO: Replay

class MainPipe()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mpReq_s2     = Flipped(ValidIO(new TaskBundle))
        val dirResp_s3   = Flipped(ValidIO(new DirResp))
        val mshrAlloc_s3 = Decoupled(new MshrAllocBundle)
        val replay_s4    = ValidIO(new TaskBundle)
    })

    io <> DontCare

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val task_s2  = WireInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2 = WireInit(false.B)

    valid_s2 := io.mpReq_s2.valid
    task_s2  := io.mpReq_s2.bits

    // -----------------------------------------------------------------------------------------
    // Stage 3
    // -----------------------------------------------------------------------------------------
    val dirResp_s3 = Reg(new DirResp)
    val task_s3    = Reg(new TaskBundle)
    val valid_s3   = RegNext(valid_s2, false.B)
    val hit_s3     = dirResp_s3.hit
    val meta_s3    = dirResp_s3.meta
    val state_s3   = meta_s3.state

    when(valid_s2) {
        task_s3 := task_s2
    }

    when(io.dirResp_s3.fire) {
        dirResp_s3 := io.dirResp_s3.bits
    }

    // TODO: Cache Alias
    val isGet_s3             = task_s3.opcode === Get && task_s3.isChannelA
    val isAcquire_s3         = (task_s3.opcode(2, 1) === AcquireBlock(2, 1) || task_s3.opcode(2, 1) === AcquirePerm(2, 1)) && task_s3.isChannelA
    val mshrAllloc_a_miss_s3 = !dirResp_s3.hit && (isAcquire_s3 || isGet_s3)
    val mshrAllloc_a_hit_s3  = dirResp_s3.hit && isAcquire_s3 && meta_s3.isShared() && needT(task_s3.param)
    val mshrAlloc_a_s3       = mshrAllloc_a_miss_s3 || mshrAllloc_a_hit_s3

    val mshrAlloc_b_s3 = false.B // TODO:

    val mshrAlloc_c_s3 = false.B // for inclusive cache, Release/ReleaseData always hit

    val mshrAlloc_s3 = mshrAlloc_a_s3 || mshrAlloc_b_s3 || mshrAlloc_c_s3

    io.mshrAlloc_s3.valid := mshrAlloc_s3 && valid_s3

    dontTouch(isGet_s3)
    dontTouch(isAcquire_s3)

    assert(!(valid_s3 && !io.dirResp_s3.fire))
    assert(!(valid_s3 && dirResp_s3.hit && state_s3 === MixedState.I), "Hit on INVALID state!")
    assert(!(task_s3.isChannelC && !dirResp_s3.hit), "Release/ReleaseData should always hit! addr => TODO: ")

    // -----------------------------------------------------------------------------------------
    // Stage 4
    // -----------------------------------------------------------------------------------------
    val task_s4  = Reg(new TaskBundle)
    val valid_s4 = RegNext(io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready, false.B)
    when(valid_s4) {
        task_s4 := task_s3
    }

    io.replay_s4.valid := valid_s4
    io.replay_s4.bits  := task_s4

    dontTouch(io)
}

object MainPipe extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MainPipe()(config), name = "MainPipe", split = false)
}
