package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

class MshrFsmState()(implicit p: Parameters) extends L2Bundle {
    // s: send
    val s_read       = Bool() // read downwards
    val s_probe      = Bool() // probe upwards
    val s_rprobe     = Bool() // probe upwards, cause by Replace
    val s_sprobe     = Bool() // probe upwards, cause by Snoop
    val s_pprobe     = Bool()
    val s_grant      = Bool() // response grant upwards
    val s_snpresp    = Bool() // resposne SnpResp downwards
    val s_evict      = Bool() // evict downwards(for clean state)
    val s_wb         = Bool() // writeback downwards(for dirty state)
    val s_compack    = Bool() // response CompAck downwards
    val s_makeunique = Bool()
    val s_accessack  = Bool()

    // w: wait
    val w_grantack  = Bool()
    val w_compdat   = Bool()
    val w_probeack  = Bool()
    val w_rprobeack = Bool()
    val w_pprobeack = Bool()
    val w_dbidresp  = Bool()
    val w_comp      = Bool()
}

class MshrInfo()(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}

class MshrStatus()(implicit p: Parameters) extends L2Bundle {
    val valid = Bool()
    val set   = UInt(setBits.W)
    val tag   = UInt(tagBits.W)
    val wayOH = UInt(ways.W)
}

class MSHR()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val alloc_s3 = Flipped(ValidIO(new MshrAllocBundle))
        val status   = Output(new MshrStatus)
    })

    io <> DontCare

    val valid   = RegInit(false.B)
    val info    = Reg(new MshrStatus)
    val dirResp = Reg(new DirResp)
    val state   = RegInit(0.U.asTypeOf(new MshrFsmState))

    when(io.alloc_s3.fire) {
        valid    := true.B
        info.set := io.alloc_s3.bits.set
        info.tag := io.alloc_s3.bits.tag
        dirResp  := io.alloc_s3.bits.dirResp
    }

    io.status.valid := valid
    io.status.set   := info.set
    io.status.tag   := info.tag
    io.status.wayOH := dirResp.wayOH

    dontTouch(io)
}
