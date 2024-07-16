package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeSNP._

class RXSNP()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxsnp = Flipped(DecoupledIO(new CHIBundleSNP(chiBundleParams)))
        val task  = DecoupledIO(new TaskBundle)
    })

    // Addr in CHI SNP channel has 3 fewer bits than full address
    val snpFullAddr        = Cat(io.rxsnp.bits.addr, 0.U(3.W)) // TODO: move into CHIBridge?
    val (tag, set, offset) = parseAddress(snpFullAddr)

    io.rxsnp.ready           := io.task.ready
    io.task.valid            := io.rxsnp.valid
    io.task.bits             := DontCare
    io.task.bits.set         := set
    io.task.bits.tag         := tag
    io.task.bits.channel     := L2Channel.ChannelB
    io.task.bits.opcode      := io.rxsnp.bits.opcode
    io.task.bits.isCHIOpcode := true.B
    io.task.bits.txnID       := io.rxsnp.bits.txnID

    /**
     * from IHI0050G: P269
     * For Non-forwarding snoops, except SnpMakeInvalid, the rules for returning a copy of the cache line to the Home are:
     *  - Irrespective of the value of RetToSrc, must return a copy if the cache line is Dirty
     *  - Irrespective of the value of RetToSrc, optionally can return a copy if the cache line is Unique Clean([[MixedState.TC]] / [[MixedState.TTC]]).
     *  - If the RetToSrc value is 1, must return a copy if the cache line is Shared Clean([[MixedState.SC]]).
     *  - If the RetToSrc value is 0, must not return a copy if the cache line is Shared Clean([[MixedState.SC]]).
     * For Forwarding snoops where data is being forwarded, the rules for returning a copy of the cache line to the Home are:
     *  - Irrespective of the value of RetToSrc, must return a copy if a Dirty cache line cannot be forwarded or kept.
     *  - If the RetToSrc value is 1, must return a copy if the cache line is Dirty or Clean.
     *  - If the RetToSrc value is 0, must not return a copy if the cache line is Clean
     */
    io.task.bits.retToSrc := io.rxsnp.bits.retToSrc

    /**
     * from IHI0050G: P269
     * RetToSrc is inapplicable and must be set to 0 in:
     *      SnpCleanShared, SnpCleanInvalid, and SnpMakeInvalid
     *      SnpOnceFwd and SnpUniqueFwd
     *      SnpMakeInvalidStash, SnpStashUnique, and SnpStashShared
     *      SnpQuery
     */
    val opcode         = io.rxsnp.bits.opcode
    val checkOpcodes   = Seq(SnpCleanShared, SnpCleanInvalid, SnpMakeInvalid, SnpOnceFwd, SnpUniqueFwd, SnpMakeInvalidStash, SnpStashUnique, SnpStashShared)
    val opcodeMatchVec = VecInit(Seq.fill(checkOpcodes.length)(opcode).zip(checkOpcodes).map(x => x._1 === x._2)).asUInt
    assert(!(io.rxsnp.fire && opcodeMatchVec.orR && io.rxsnp.bits.retToSrc =/= 0.U), "RetToSrc is inapplicable for this opcode and must be set to 0")

    val implOpcodes        = Seq(SnpShared, SnpUnique, SnpCleanInvalid)
    val implOpcodeMatchVec = VecInit(Seq.fill(implOpcodes.length)(opcode).zip(implOpcodes).map(x => x._1 === x._2)).asUInt
    assert(!(io.rxsnp.fire && !implOpcodeMatchVec.orR), "Snp opcode: 0x%x is not implemented", opcode)

    LeakChecker(io.rxsnp.valid, io.rxsnp.fire, Some("RXSNP_valid"), maxCount = deadlockThreshold)
}

object RXSNP extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new RXSNP()(config), name = "RXSNP", split = false)
}
