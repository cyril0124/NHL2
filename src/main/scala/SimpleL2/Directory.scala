package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

object TLState {
    val width   = 2
    val INVALID = "b00".U
    val BRANCH  = "b01".U
    val TRUNK   = "b10".U
    val TIP     = "b11".U
}

object MixedState {
    val width = 4

    //
    // MixedState:
    //      MSB  <--  | Meta[1:0] | Shared | Dirty |  --> LSB
    //
    // Branch Branch: Branch with Shared
    //
    val I   = "b0000".U // Invalid
    val BC  = "b0100".U // Branch Clean
    val BD  = "b0101".U // Branch Dirty
    val BBC = "b0110".U // Branch Branch Clean
    val BBD = "b0111".U // Branch Branch Dirty
    val TTC = "b1010".U // Trunk Clean
    val TTD = "b1011".U // Trunk Dirty
    val TC  = "b1100".U // Tip Clean
    val TD  = "b1101".U // Tip Dirty
}

class MixedState {
    val state = UInt(MixedState.width.W)

    def isDirty() = state(0)
    def isShared() = state(1)
    def isBranch() = state(3, 2) === TLState.BRANCH
    def isTrunk() = state(3, 2) === TLState.TRUNK
    def isTip() = state(3, 2) === TLState.TIP
}

trait HasMixedState {
    val state = UInt(MixedState.width.W)

    def isDirty() = state(0)
    def isShared() = state(1)
    def isBranch() = state(3, 2) === TLState.BRANCH
    def isTrunk() = state(3, 2) === TLState.TRUNK
    def isTip() = state(3, 2) === TLState.TIP
}

class DirectoryMetaEntry(implicit p: Parameters) extends L2Bundle with HasMixedState {
    val fromPrefetch = Bool()
    val tag          = UInt(tagBits.W)
    val alias        = aliasBitsOpt.map(width => UInt(width.W))
}

class DirRead(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}

class DirResp(implicit p: Parameters) extends L2Bundle {
    val meta  = new DirectoryMetaEntry
    val wayOH = UInt(ways.W)
    val hit   = Bool()
}

class Directory()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val dirRead_s1 = Flipped(Decoupled(new DirRead))
        val dirResp_s3 = Output(new DirResp)
    })

    // TODO: ECC

    io <> DontCare

    val metaArray = Module(
        new SRAMTemplate(
            new DirectoryMetaEntry,
            sets,
            ways,
            singlePort = true,
            hasMbist = false /* TODO */,
            hasShareBus = false /* TDOO */,
            hasClkGate = enableClockGate
            // parentName = parentName + "meta_" /* TODO */
        )
    )

    val repl = ReplacementPolicy.fromString(replacementPolicy, ways)
    
    // @formatter:off
    val replacerSRAM_opt = if (replacementPolicy == "random") None else {
            Some(
                Module(
                    new SRAMTemplate(
                        UInt(repl.nBits.W),
                        sets,
                        1,
                        singlePort = true,
                        shouldReset = true,
                        hasMbist = false /* TODO */,
                        hasShareBus = false /* TODO */,
                        hasClkGate = enableClockGate
                        // parentName = parentName + "repl_"
                    )
                )
            )
        }
    // @formatter:on

    metaArray.io <> DontCare

    // TODO: when should we update replacer SRAM
    replacerSRAM_opt.foreach { sram =>
        sram.io <> DontCare
        dontTouch(sram.io)
    }

    // -----------------------------------------------------------------------------------------
    // Stage 1
    // -----------------------------------------------------------------------------------------
    metaArray.io.r.req.bits.setIdx := io.dirRead_s1.bits.set
    metaArray.io.r.req.valid       := io.dirRead_s1.fire
    io.dirRead_s1.ready            := metaArray.io.r.req.ready

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val metaRead_s2 = Wire(Vec(ways, new DirectoryMetaEntry()))
    val reqValid_s2 = RegNext(io.dirRead_s1.fire, false.B)
    val reqTag_s2   = RegEnable(io.dirRead_s1.bits.tag, io.dirRead_s1.fire)
    metaRead_s2 := metaArray.io.r.resp.data

    // -----------------------------------------------------------------------------------------
    // Stage 3
    // -----------------------------------------------------------------------------------------
    val metaRead_s3 = RegEnable(metaRead_s2, reqValid_s2)
    val reqValid_s3 = RegEnable(reqValid_s2, reqValid_s2)
    val reqTag_s3   = RegEnable(reqTag_s2, reqValid_s2)
    val stateAll_s3 = metaRead_s3.map(_.state)
    val tagAll_s3   = metaRead_s3.map(_.tag)
    val hitOH_s3 = VecInit(
        stateAll_s3
            .zip(tagAll_s3)
            .map { case (state, tag) =>
                state =/= MixedState.I && tag === reqTag_s3
            }
            .reverse
    ).asUInt
    val hit_s3        = hitOH_s3.asUInt.orR
    val finalWayOH_s3 = Mux(hit_s3, hitOH_s3, random.LFSR(16)(ways - 1, 0) /* TODO */ )
    assert(PopCount(hitOH_s3) <= 1.U)
    assert(PopCount(finalWayOH_s3) <= 1.U)

    io.dirResp_s3.hit   := hit_s3
    io.dirResp_s3.meta  := Mux1H(finalWayOH_s3, metaRead_s3)
    io.dirResp_s3.wayOH := finalWayOH_s3

    dontTouch(metaArray.io)
    dontTouch(hitOH_s3)
    dontTouch(io)
}

object Directory extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Directory()(config), name = "Directory", split = true)
}
