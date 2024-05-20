package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._

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
    // MixState:
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
    val meta = UInt(MixedState.width.W)

    def isDirty() = meta(0)
    def isShared() = meta(1)
    def isBranch() = meta(3, 2) === TLState.BRANCH
    def isTrunk() = meta(3, 2) === TLState.TRUNK
    def isTip() = meta(3, 2) === TLState.TIP
}

trait HasMixedState {
    val meta = UInt(MixedState.width.W)

    def isDirty() = meta(0)
    def isShared() = meta(1)
    def isBranch() = meta(3, 2) === TLState.BRANCH
    def isTrunk() = meta(3, 2) === TLState.TRUNK
    def isTip() = meta(3, 2) === TLState.TIP
}

class DirectoryMetaEntry(implicit p: Parameters) extends L2Bundle with HasMixedState {
    val fromPrefetch = Bool()
    val tag          = UInt(tagBits.W)
}

class Directory()(implicit p: Parameters) extends L2Module {
    val test = RegInit(0.U.asTypeOf(new DirectoryMetaEntry))
    dontTouch(test)

    val metaArray = Module(
        new SRAMTemplate(
            new DirectoryMetaEntry,
            sets,
            ways,
            singlePort = true,
            hasMbist = false /* TODO */,
            hasShareBus = false /* TDOO */,
            hasClkGate = enableClockGate
            // parentName = parentName + "meta_"
        )
    )
    metaArray.io <> DontCare
    dontTouch(metaArray.io)
}

object Directory extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Directory()(config), name = "Directory", split = true)
}
