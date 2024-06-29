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
import SimpleL2.chi.CHIOpcodeDAT._
import SimpleL2.chi.CHIOpcodeSNP._
import SimpleL2.TLState._
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

class MshrTasks()(implicit p: Parameters) extends L2Bundle {
    val mpTask = DecoupledIO(new TaskBundle)
    val txreq  = DecoupledIO(new CHIBundleREQ(chiBundleParams))
    val txrsp  = DecoupledIO(new CHIBundleRSP(chiBundleParams))
}

class MshrResps()(implicit p: Parameters) extends L2Bundle {
    val rxdat = Flipped(ValidIO(new CHIRespBundle(chiBundleParams)))
    val sinke = Flipped(ValidIO(new TLRespBundle(tlBundleParams)))
    val sinkc = Flipped(ValidIO(new TLRespBundle(tlBundleParams)))
}

class MSHR(id: Int)(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val alloc_s3 = Flipped(ValidIO(new MshrAllocBundle))
        val status   = Output(new MshrStatus)

        val tasks = new MshrTasks
        val resps = new MshrResps
    })

    io <> DontCare

    val valid   = RegInit(false.B)
    val req     = Reg(new TaskBundle)
    val dirResp = Reg(new DirResp)

    val dbid     = RegInit(0.U(chiBundleParams.DBID_WIDTH.W))
    val dataId   = RegInit(0.U(dataIdBits.W))
    val gotDirty = RegInit(false.B)
    val gotT     = RegInit(false.B)

    val meta = dirResp.meta
    // val metaNoClients   = !meta.clientsOH.orR
    val reqClientOH     = getClientBitOH(req.source)
    val reqIsGet        = req.opcode === Get
    val reqIsAcquire    = req.opcode === AcquireBlock || req.opcode === AcquirePerm
    val reqIsPrefetch   = req.opcode === Hint
    val reqNeedT        = needT(req.opcode, req.param)
    val reqNeedB        = needB(req.opcode, req.param)
    val promoteT_normal = dirResp.hit && !meta.isShared && meta.isTip
    val promoteT_l3     = !dirResp.hit && gotT
    val promoteT_alias  = dirResp.hit && req.isAliasTask && (meta.isTrunk || meta.isTip)
    val reqPromoteT     = (reqIsAcquire || reqIsGet || reqIsPrefetch) && (promoteT_normal || promoteT_l3 || promoteT_alias) // TODO:

    val initState = Wire(new MshrFsmState())
    initState.elements.foreach(_._2 := true.B)
    val state = RegInit(new MshrFsmState, initState)

    val newMetaEntry = WireInit(0.U.asTypeOf(new DirectoryMetaEntryNoTag))

    when(io.alloc_s3.fire) {
        valid   := true.B
        req     := io.alloc_s3.bits.req
        dirResp := io.alloc_s3.bits.dirResp
        state   := io.alloc_s3.bits.fsmState
    }

    /** Deal with txreq */
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

    /** Deal with txrsp */
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

    /** Deal with mpTask */
    // TODO: mshrOpcodes: update directory, write TempDataStorage data in to DataStorage
    val needRefillData   = (req.opcode === AcquireBlock || req.opcode === Get) && !dirResp.hit
    val needProbeAckData = false.B // TODO:
    val needTempDsData   = needRefillData || needProbeAckData
    io.tasks.mpTask.valid             := valid && !state.s_grant && !state.w_grantack && state.s_read && state.w_compdat && state.s_compack
    io.tasks.mpTask.bits.isMshrTask   := true.B
    io.tasks.mpTask.bits.opcode       := MuxCase(DontCare, Seq((req.opcode === AcquireBlock) -> GrantData, (req.opcode === AcquirePerm) -> Grant, (req.opcode === Get) -> AccessAckData)) // TODO:
    io.tasks.mpTask.bits.sink         := id.U
    io.tasks.mpTask.bits.source       := req.source
    io.tasks.mpTask.bits.set          := req.set
    io.tasks.mpTask.bits.tag          := req.tag
    io.tasks.mpTask.bits.wayOH        := dirResp.wayOH                                                                                                                                    // TODO:
    io.tasks.mpTask.bits.readTempDs   := needTempDsData
    io.tasks.mpTask.bits.tempDsDest   := DataDestination.SourceD | DataDestination.DataStorage
    io.tasks.mpTask.bits.dataId       := dataId
    io.tasks.mpTask.bits.updateDir    := req.opcode =/= Get
    io.tasks.mpTask.bits.newMetaEntry := newMetaEntry

    newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = false.B, // TODO:
        state = Mux(
            reqIsGet,
            Mux(dirResp.hit, Mux(meta.isTip || meta.isTrunk, TIP, BRANCH), Mux(reqPromoteT, TIP, BRANCH)),
            Mux(reqPromoteT || reqNeedT, Mux(reqIsPrefetch, TIP, TRUNK), BRANCH)
        ),
        alias = Mux(
            reqIsGet || reqIsPrefetch,
            meta.aliasOpt.getOrElse(0.U),
            req.aliasOpt.getOrElse(0.U)
        ), // TODO:
        clientsOH = Mux(
            reqIsPrefetch,
            Mux(dirResp.hit, meta.clientsOH, Fill(nrClients, false.B)),
            MuxCase(
                Fill(nrClients, false.B),
                Seq(
                    (reqIsGet)                 -> meta.clientsOH,
                    (reqIsPrefetch)            -> meta.clientsOH,
                    (reqIsAcquire && reqNeedT) -> reqClientOH,
                    (reqIsAcquire && reqNeedB) -> (meta.clientsOH | reqClientOH)
                )
            )
        ),                           // TODO:
        fromPrefetch = reqIsPrefetch // TODO:
    )

    when(io.tasks.mpTask.fire) {
        state.s_grant := true.B
    }

    /**
      * Receive RXDAT responses
      * 1) CompData
      * 2) Comp
      */
    val rxdat = io.resps.rxdat
    when(rxdat.fire && rxdat.bits.last) {
        state.w_compdat := true.B
        dataId          := rxdat.bits.dataId

        when(rxdat.bits.chiOpcode === CompData) {
            gotT     := rxdat.bits.resp === Resp.UC || rxdat.bits.resp === Resp.UC_PD
            gotDirty := gotDirty || rxdat.bits.resp === Resp.UC_PD
        }
    }
    assert(!(rxdat.fire && state.w_compdat), s"mshr_${id} is not watting for rxdat")

    /**
      * A MSHR will wait GrantAck for Acquire requests.
      * If mshr not receive a GrantAck, it cannot be freed. 
      * This allow external logic to check the mshr to determine whether the Grant/GrantData has been received by upstream cache.
      * If we ignore this response, then we have to add some logic in [[SourceD]], so that we could do the same thing as we implement here.
      */
    val sinke = io.resps.sinke
    when(sinke.fire) {
        state.w_grantack := true.B
    }
    assert(!(sinke.fire && state.w_grantack), s"mshr_${id} is not watting for sinke")

    /**
      * Receive ProbeAck/ProbeAckData
      */
    val sinkc = io.resps.sinkc
    when(sinkc.fire) {
        // TODO:
    }
    assert(!(sinkc.fire && !(state.w_probeack && state.w_pprobeack && state.w_rprobeack)), s"mshr_${id} is not watting for sinkc")

    // val rxrsp = io.resps.rxrsp

    /**
      * Check if there has any request in the MSHR waiting for responses or waiting for sehcduling tasks.
      * If there is, then we cannot free the MSHR.
      */
    val noWait     = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("w_")) => signal }.toSeq).asUInt.andR
    val noSchedule = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("s_")) => signal }.toSeq).asUInt.andR
    val willFree   = noWait && noSchedule
    when(valid && willFree) {
        valid := false.B
    }

    // TODO: deadlock check

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
