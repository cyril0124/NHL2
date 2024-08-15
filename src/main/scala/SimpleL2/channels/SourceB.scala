package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class SourceB()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val b    = DecoupledIO(new TLBundleB(tlBundleParams))
        val task = Flipped(DecoupledIO(new TLBundleB(tlBundleParams)))

        /** These signals are used for blocking sourceb tasks. */
        val grantMapStatus = Input(Vec(nrGrantMap, new GrantMapStatus)) // from SinkE
        val mpStatus       = Input(new MpStatus)                        // from MainPipe
        val bufferStatus   = Input(new BufferStatusSourceD)             // from SourceD
    })

    val (tag, set, offset) = parseAddress(io.task.bits.address)

    /**
     * Block sourceb tasks if there exist any Grant which does not get GrantAck or 
     * if there exist any on-going Grant on [[MainPipe]] or [[SourceD]] buffer. 
     */
    val matchVec_sinke    = VecInit(io.grantMapStatus.map { s => s.valid && s.set === set && s.tag === tag }).asUInt
    val shouldBlock_sinke = matchVec_sinke.orR
    assert(!(io.b.fire && PopCount(matchVec_sinke) > 1.U), "matchVec_sinke:%b", matchVec_sinke)

    val matchVec_mp = VecInit(io.mpStatus.elements.map { case (name: String, stage: MpStageInfo) =>
        stage.valid && stage.isRefill && stage.set === set && stage.tag === tag
    }.toSeq).asUInt
    val shouldBlock_mp = matchVec_mp.orR
    assert(!(io.b.fire && PopCount(shouldBlock_mp) > 1.U), "shouldBlock_mp:%b", shouldBlock_mp)

    val shouldBlock_buffer = io.bufferStatus.valid && io.bufferStatus.set === set && io.bufferStatus.tag === tag

    io.b          <> io.task
    io.task.ready := io.b.ready && !shouldBlock_sinke && !shouldBlock_mp && !shouldBlock_buffer
    io.b.valid    := io.task.valid && !shouldBlock_sinke && !shouldBlock_mp && !shouldBlock_buffer
}
