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
        val b              = DecoupledIO(new TLBundleB(tlBundleParams))
        val task           = Flipped(DecoupledIO(new TLBundleB(tlBundleParams)))
        val grantMapStatus = Input(Vec(nrMSHR, new GrantMapStatus)) // from SinkE
    })

    val (tag, set, offset) = parseAddress(io.task.bits.address)
    val matchVec           = VecInit(io.grantMapStatus.map { s => s.valid && s.set === set && s.tag === tag }).asUInt
    val shouldBlock        = matchVec.orR
    assert(!(io.b.fire && PopCount(matchVec) > 1.U), "matchVec:%b", matchVec)

    io.b          <> io.task
    io.task.ready := io.b.ready && !shouldBlock
    io.b.valid    := io.task.valid && !shouldBlock
}
