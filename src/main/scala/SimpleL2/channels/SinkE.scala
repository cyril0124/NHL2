package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import Utils.{GenerateVerilog, IDPoolFree}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._
import freechips.rocketchip.util.SeqToAugmentedSeq

class AllocGrantMap(implicit p: Parameters) extends L2Bundle {
    val sink     = UInt(tlBundleParams.sinkBits.W)
    val mshrTask = Bool()
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
}

class GrantMapStatus(implicit p: Parameters) extends L2Bundle {
    val valid = Bool()
    val set   = UInt(setBits.W)
    val tag   = UInt(tagBits.W)
}

class SinkE()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val e                = Flipped(DecoupledIO(new TLBundleE(tlBundleParams)))
        val resp             = ValidIO(new TLRespBundle(tlBundleParams))
        val allocGrantMap    = Flipped(DecoupledIO(new AllocGrantMap))              // from SourceD
        val grantMapWillFull = Output(Bool())                                       // to SourceD
        val sinkIdFree       = Flipped(new IDPoolFree(log2Ceil(nrExtraSinkId + 1))) // to sinkIDPool TODO: parameterize idBits

        /**
         * If there exist an entry that has the same set and tag as sourceB task then we should block this request until the Grant/GrantData finally gets GrantAck from upstream. 
         * This ensures that the Probe([[SourceB]]) can correctly probe a valid cacheline located in upstream cache.
         * For this reason, the Grant/GrantData in the [[MainPipe]] stage 6 and stage 7 should also block [[SourceB]].(TODO:)
         */
        val grantMapStatus = Output(Vec(nrGrantMap, new GrantMapStatus)) // to SourceB
    })

    require(nrGrantMap >= 2)

    val grantMap = RegInit(VecInit(Seq.fill(nrGrantMap)(0.U.asTypeOf(new Bundle {
        val valid    = Bool()
        val sink     = UInt(tlBundleParams.sinkBits.W)
        val mshrTask = Bool()
        val set      = UInt(setBits.W)
        val tag      = UInt(tagBits.W)
    }))))
    val insertOH         = PriorityEncoderOH(VecInit(grantMap.map(!_.valid)).asUInt)
    val grantMapMatchOH  = VecInit(grantMap.map(e => e.sink === io.e.bits.sink && e.valid)).asUInt
    val hasMatch         = grantMapMatchOH.orR
    val matchEntry       = Mux1H(grantMapMatchOH, grantMap)
    val grantMapValidVec = VecInit(grantMap.map(_.valid)).asUInt
    val grantMapWillFull = PopCount(grantMapValidVec) >= (nrGrantMap - 1).U
    val grantMapFull     = grantMapValidVec.andR
    assert(!(io.e.fire && PopCount(grantMapMatchOH) > 1.U), "grantMap has multiple matchs for sink:%d grantMapMatchOH:%b", io.e.bits.sink, grantMapMatchOH)
    assert(!(io.allocGrantMap.fire && grantMapFull), "no valid grantMap entry!")

    io.allocGrantMap.ready := !grantMapFull
    io.grantMapWillFull    := grantMapWillFull

    when(io.allocGrantMap.fire) {
        grantMap.zip(insertOH.asBools).foreach { case (entry, en) =>
            when(io.allocGrantMap.fire && en) {
                entry.valid    := true.B
                entry.sink     := io.allocGrantMap.bits.sink
                entry.mshrTask := io.allocGrantMap.bits.mshrTask
                entry.set      := io.allocGrantMap.bits.set
                entry.tag      := io.allocGrantMap.bits.tag
                assert(!entry.valid, "entry is already valid!")
            }
        }

        val allocMatchVec = VecInit(grantMap.map(e => e.valid && e.sink === io.allocGrantMap.bits.sink)).asUInt
        val hasDuplicate  = allocMatchVec.orR
        assert(!hasDuplicate, "allocGrantMap has duplicate entry! sink:%d allocMatchVec:%b", io.allocGrantMap.bits.sink, allocMatchVec)
    }

    when(io.e.fire) {
        val popOH = VecInit(grantMap.map(e => e.sink === io.e.bits.sink && e.valid)).asUInt
        assert(PopCount(popOH) <= 1.U, "there are multiple matched entries in grantMap for sink:%d popOH:%b", io.e.bits.sink, popOH)

        grantMap.zip(popOH.asBools).foreach { case (entry, en) =>
            when(en) {
                entry.valid := false.B
                assert(entry.valid, "sink:%d does not match any grantMap entry!", io.e.bits.sink)
            }
        }
    }
    assert(!(io.allocGrantMap.fire && io.e.fire && io.allocGrantMap.bits.sink === io.e.bits.sink))

    io.e.ready        := true.B
    io.resp.valid     := io.e.valid && hasMatch && matchEntry.mshrTask
    io.resp.bits      := DontCare
    io.resp.bits.sink := Mux(hasMatch, matchEntry.sink, 0.U)

    io.sinkIdFree.valid := io.e.fire && hasMatch && !matchEntry.mshrTask
    io.sinkIdFree.idIn  := io.e.bits.sink

    io.grantMapStatus.zip(grantMap).foreach { case (s, map) =>
        s.valid := map.valid
        s.set   := map.set
        s.tag   := map.tag
    }
}

object SinkE extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new SinkE()(config), name = "SinkE", split = false)
}
