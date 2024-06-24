package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import xs.utils.{ParallelPriorityMux}
import Utils.GenerateVerilog
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeREQ._
import SimpleL2.chi.CHIOpcodeRSP._
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

class MSHR(id: Int)(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val alloc_s3 = Flipped(ValidIO(new MshrAllocBundle))
        val status   = Output(new MshrStatus)

        val tasks = new Bundle {
            val mpTask = DecoupledIO(new TaskBundle)
            val txreq  = DecoupledIO(new CHIBundleREQ(chiBundleParams))
            val txrsp  = DecoupledIO(new CHIBundleRSP(chiBundleParams))
        }

        val resps = new Bundle {
            val rxdat = Flipped(ValidIO(new CHIRespBundle(chiBundleParams)))
        }
    })

    io <> DontCare

    val valid   = RegInit(false.B)
    val req     = Reg(new TaskBundle)
    val dirResp = Reg(new DirResp)

    val initState = Wire(new MshrFsmState())
    initState.elements.foreach(_._2 := true.B)
    val state = RegInit(new MshrFsmState, initState)

    val dbid   = RegInit(0.U(chiBundleParams.DBID_WIDTH.W))
    val dataId = RegInit(0.U(dataIdBits.W))

    when(io.alloc_s3.fire) {
        valid   := true.B
        req     := io.alloc_s3.bits.req
        dirResp := io.alloc_s3.bits.dirResp
        state   := io.alloc_s3.bits.fsmState
    }

    val reqNeedT = needT(req.opcode, req.param)
    val reqNeedB = needB(req.opcode, req.param)

    /** deal with txreq */
    io.tasks.txreq.valid := !state.s_read
    io.tasks.txreq.bits.opcode := ParallelPriorityMux(
        Seq(
            (req.opcode === AcquirePerm && req.param === NtoT) -> MakeUnique,
            reqNeedT                                           -> ReadUnique,
            reqNeedB                                           -> ReadNotSharedDirty
        )
    )
    io.tasks.txreq.bits.addr       := Cat(req.tag, req.set, 0.U(6.W)) // TODO:　MultiBank
    io.tasks.txreq.bits.allowRetry := true.B                          // TODO:
    io.tasks.txreq.bits.expCompAck := !state.s_read
    io.tasks.txreq.bits.size       := log2Ceil(blockBytes).U
    io.tasks.txreq.bits.srcID      := DontCare                        // This value will be assigned in output chi portr

    when(io.tasks.txreq.fire) {
        state.s_read := true.B
    }

    /** deal with txrsp */
    io.tasks.txrsp.valid         := !state.s_compack && state.w_compdat
    io.tasks.txrsp.bits.opcode   := CompAck
    io.tasks.txrsp.bits.txnID    := id.U
    io.tasks.txrsp.bits.respErr  := RespErr.NormalOkay
    io.tasks.txrsp.bits.pCrdType := DontCare
    io.tasks.txrsp.bits.dbID     := DontCare
    io.tasks.txrsp.bits.srcID    := DontCare
    // io.tasks.txrsp.bits.resp := // TODO:

    when(io.tasks.txrsp.fire) {
        state.s_compack := true.B
    }

    // TODO: mshrOpcodes: update directory, write TempDataStorage data in to DataStorage
    // io.tasks.mpTask.valid :=
    // io.tasks.mpTask.bits.opcode :=

    /** receive responses */
    when(io.resps.rxdat.fire && io.resps.rxdat.bits.last) {
        state.w_compdat := true.B
        dataId          := io.resps.rxdat.bits.dataId
    }
    assert(!(io.resps.rxdat.fire && state.w_compdat), "mshr is not watting for rxdat")

    io.status.valid := valid
    io.status.set   := req.set
    io.status.tag   := req.tag
    io.status.wayOH := dirResp.wayOH

    dontTouch(io)
}

object MSHR extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MSHR(id = 0)(config), name = "MSHR", split = false)
}
