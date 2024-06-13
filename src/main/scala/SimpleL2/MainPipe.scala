package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, MultiDontTouch}
import SimpleL2.Configs._
import SimpleL2.Bundles._

// TODO: Replay

class MainPipe()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val mpReq_s2     = Flipped(ValidIO(new TaskBundle))
        val dirResp_s3   = Flipped(ValidIO(new DirResp))
        val mshrAlloc_s3 = DecoupledIO(new MshrAllocBundle)
        val dsRead_s3    = ValidIO(new DSRead)
        val replay_s4    = DecoupledIO(new TaskBundle)
        val sourceD_s4   = DecoupledIO(new TaskBundle)
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
    val dirResp_s3 = io.dirResp_s3.bits
    val task_s3    = Reg(new TaskBundle)
    val valid_s3   = RegNext(valid_s2, false.B)
    val hit_s3     = dirResp_s3.hit
    val meta_s3    = dirResp_s3.meta
    val state_s3   = meta_s3.state

    when(valid_s2) {
        task_s3 := task_s2
    }

    // TODO: Cache Alias
    val isGet_s3             = task_s3.opcode === Get && task_s3.isChannelA
    val isAcquire_s3         = (task_s3.opcode(2, 1) === AcquireBlock(2, 1) || task_s3.opcode(2, 1) === AcquirePerm(2, 1)) && task_s3.isChannelA
    val mshrAllloc_a_miss_s3 = !dirResp_s3.hit && (isAcquire_s3 || isGet_s3)
    val mshrAllloc_a_hit_s3  = dirResp_s3.hit && isAcquire_s3 && meta_s3.isShared && needT(task_s3.param)
    val mshrAlloc_a_s3       = mshrAllloc_a_miss_s3 || mshrAllloc_a_hit_s3
    val mshrAlloc_b_s3       = false.B // TODO:
    val mshrAlloc_c_s3       = false.B // for inclusive cache, Release/ReleaseData always hit
    val mshrAlloc_s3         = (mshrAlloc_a_s3 || mshrAlloc_b_s3 || mshrAlloc_c_s3) && valid_s3
    io.mshrAlloc_s3.valid := mshrAlloc_s3

    MultiDontTouch(isGet_s3, isAcquire_s3, mshrAllloc_a_hit_s3, mshrAllloc_a_miss_s3)

    assert(!(valid_s3 && !io.dirResp_s3.fire))
    assert(!(valid_s3 && dirResp_s3.hit && state_s3 === MixedState.I), "Hit on INVALID state!")
    assert(!(task_s3.isChannelC && !dirResp_s3.hit), "Release/ReleaseData should always hit! addr => TODO: ")

    val dsRen_s3 = hit_s3 && !io.mshrAlloc_s3.valid && valid_s3
    io.dsRead_s3.bits.set  := task_s3.set
    io.dsRead_s3.bits.way  := OHToUInt(io.dirResp_s3.bits.wayOH)
    io.dsRead_s3.bits.dest := DataDestination.SourceD // TODO:
    io.dsRead_s3.valid     := dsRen_s3

    val replayValid_s3 = io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready && valid_s3
    val respValid_s3   = dirResp_s3.hit && !mshrAlloc_s3 && valid_s3
    val fire_s3        = replayValid_s3 || respValid_s3
    assert(PopCount(Seq(replayValid_s3, respValid_s3)) <= 1.U)

    MultiDontTouch(replayValid_s3, respValid_s3, fire_s3)

    // -----------------------------------------------------------------------------------------
    // Stage 4
    // -----------------------------------------------------------------------------------------
    val task_s4        = Reg(new TaskBundle)
    val replayValid_s4 = RegNext(replayValid_s3, false.B)
    val respValid_s4   = RegNext(respValid_s3, false.B)
    val valid_s4       = replayValid_s4 | respValid_s4
    MultiDontTouch(replayValid_s4, respValid_s4, valid_s4)

    when(fire_s3) {
        task_s4        := task_s3
        task_s4.opcode := Mux(respValid_s3, GrantData, DontCare) // TODO:
        task_s4.param  := Mux(respValid_s3, toT, DontCare)       // TODO:
    }

    io.replay_s4.valid := replayValid_s4
    io.replay_s4.bits  := task_s4

    io.sourceD_s4.valid := respValid_s4
    io.sourceD_s4.bits  := task_s4

    assert(!(io.replay_s4.valid && !io.replay_s4.ready), "replay_s4 should always ready!")
    assert(!(io.sourceD_s4.valid && !io.sourceD_s4.ready), "sourceD_s4 should always ready!")

    dontTouch(io)
}

object MainPipe extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MainPipe()(config), name = "MainPipe", split = false)
}
