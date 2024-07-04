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
    val s_aprobe     = Bool() // probe upwards, cause by Acquire
    val s_rprobe     = Bool() // probe upwards, cause by Replace
    val s_sprobe     = Bool() // probe upwards, cause by Snoop
    val s_grant      = Bool() // response grant upwards
    val s_snpresp    = Bool() // resposne SnpResp downwards
    val s_evict      = Bool() // evict downwards(for clean state)
    val s_wb         = Bool() // writeback downwards(for dirty state)
    val s_compack    = Bool() // response CompAck downwards
    val s_makeunique = Bool()
    val s_accessack  = Bool()

    // w: wait
    val w_grantack        = Bool()
    val w_compdat         = Bool()
    val w_aprobeack       = Bool()
    val w_aprobeack_first = Bool()
    val w_rprobeack       = Bool()
    val w_rprobeack_first = Bool()
    val w_sprobeack       = Bool()
    val w_sprobeack_first = Bool()
    val w_dbidresp        = Bool()
    val w_comp            = Bool()
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
    val mpTask  = DecoupledIO(new TaskBundle)
    val txreq   = DecoupledIO(new CHIBundleREQ(chiBundleParams))
    val txrsp   = DecoupledIO(new CHIBundleRSP(chiBundleParams))
    val sourceb = DecoupledIO(new TLBundleB(tlBundleParams))
}

class MshrResps()(implicit p: Parameters) extends L2Bundle {
    val rxdat = Flipped(ValidIO(new CHIRespBundle(chiBundleParams)))
    val rxrsp = Flipped(ValidIO(new CHIRespBundle(chiBundleParams)))
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

    val initState = Wire(new MshrFsmState())
    initState.elements.foreach(_._2 := true.B)
    val state = RegInit(new MshrFsmState, initState)

    val newMetaEntry = WireInit(0.U.asTypeOf(new DirectoryMetaEntryNoTag))

    val meta          = dirResp.meta
    val metaNoClients = !meta.clientsOH.orR
    val reqClientOH   = getClientBitOH(req.source)
    val reqIdx        = OHToUInt(reqClientOH)
    val reqIsGet      = req.opcode === Get
    val reqIsAcquire  = req.opcode === AcquireBlock || req.opcode === AcquirePerm
    val reqIsPrefetch = req.opcode === Hint
    val reqNeedT      = needT(req.opcode, req.param)
    val reqNeedB      = needB(req.opcode, req.param)

    val dbid            = RegInit(0.U(chiBundleParams.DBID_WIDTH.W))
    val dataId          = RegInit(0.U(dataIdBits.W))
    val gotDirty        = RegInit(false.B)
    val gotT            = RegInit(false.B)
    val needProbe       = RegInit(false.B)
    val probeAckParams  = RegInit(VecInit(Seq.fill(nrClients)(0.U.asTypeOf(chiselTypeOf(io.resps.sinkc.bits.param)))))
    val probeAckClients = RegInit(0.U(nrClients.W))
    val probeFinish     = WireInit(false.B)
    val probeClients    = ~reqClientOH & meta.clientsOH // TODO: multiple clients, for now only support up to 2 clients(core 0 and core 1)
    assert(
        !(!state.s_aprobe && !req.isAliasTask && PopCount(probeClients) =/= 1.U),
        "probeClients: 0b%b reqClientOH: 0b%b meta.clientOH: 0b%b",
        probeClients,
        reqClientOH,
        meta.clientsOH
    ) // TODO:

    val promoteT_normal = dirResp.hit && metaNoClients && meta.isTip
    val promoteT_l3     = !dirResp.hit && gotT
    val promoteT_alias  = dirResp.hit && req.isAliasTask && (meta.isTrunk || meta.isTip)
    val reqPromoteT     = (reqIsAcquire || reqIsGet || reqIsPrefetch) && (promoteT_normal || promoteT_l3 || promoteT_alias) // TODO:

    when(io.alloc_s3.fire) {
        val allocState = io.alloc_s3.bits.fsmState

        valid   := true.B
        req     := io.alloc_s3.bits.req
        dirResp := io.alloc_s3.bits.dirResp
        state   := allocState

        needProbe := !allocState.s_aprobe | !allocState.s_sprobe | !allocState.s_rprobe

        dataId          := io.alloc_s3.bits.req.dataId
        gotT            := false.B
        probeAckClients := 0.U
        probeAckParams.foreach(_ := 0.U)
    }

    /**
      * ------------------------------------------------------- 
      * Send txreq task
      * -------------------------------------------------------
      */
    io.tasks.txreq.valid := !state.s_read || !state.s_makeunique
    io.tasks.txreq.bits.opcode := ParallelPriorityMux(
        Seq(
            (req.opcode === AcquirePerm && req.param === NtoT) -> MakeUnique,
            reqNeedT                                           -> ReadUnique,
            reqNeedB                                           -> ReadNotSharedDirty
        )
    )
    io.tasks.txreq.bits.addr       := Cat(req.tag, req.set, 0.U(6.W)) // TODO: MultiBank
    io.tasks.txreq.bits.allowRetry := true.B                          // TODO: Retry
    io.tasks.txreq.bits.expCompAck := !state.s_read                   // TODO: only for Read not for EvictS
    io.tasks.txreq.bits.size       := log2Ceil(blockBytes).U
    io.tasks.txreq.bits.order      := "b00".U                         // No ordering required
    io.tasks.txreq.bits.srcID      := DontCare                        // This value will be assigned in output chi portr
    when(io.tasks.txreq.fire) {
        state.s_read       := true.B
        state.s_makeunique := true.B
    }

    /**
      * ------------------------------------------------------- 
      * Send txrsp task
      * -------------------------------------------------------
      */
    io.tasks.txrsp.valid         := !state.s_compack && state.w_compdat && state.w_comp
    io.tasks.txrsp.bits.opcode   := CompAck
    io.tasks.txrsp.bits.respErr  := RespErr.NormalOkay
    io.tasks.txrsp.bits.pCrdType := DontCare
    io.tasks.txrsp.bits.txnID    := dbid // TODO:
    io.tasks.txrsp.bits.dbID     := DontCare
    io.tasks.txrsp.bits.srcID    := DontCare
    io.tasks.txrsp.bits.resp     := DontCare
    when(io.tasks.txrsp.fire) {
        state.s_compack := true.B
    }

    /**
      * ------------------------------------------------------- 
      * Send sourceb task
      * -------------------------------------------------------
      */
    io.tasks.sourceb.valid        := !state.s_aprobe /* TODO: acquire probe with MakeUnique */ || !state.s_sprobe || !state.s_rprobe
    io.tasks.sourceb.bits.opcode  := Probe
    io.tasks.sourceb.bits.param   := Mux(reqNeedT, toN, toB)         // TODO:
    io.tasks.sourceb.bits.address := Cat(req.tag, req.set, 0.U(6.W)) // TODO: MultiBank
    io.tasks.sourceb.bits.size    := log2Ceil(blockBytes).U
    io.tasks.sourceb.bits.data    := DontCare
    io.tasks.sourceb.bits.mask    := DontCare
    io.tasks.sourceb.bits.corrupt := DontCare
    io.tasks.sourceb.bits.source  := clientOHToSource(probeClients)  // TODO:
    when(io.tasks.sourceb.fire) {
        state.s_aprobe := true.B
        state.s_sprobe := true.B
        state.s_rprobe := true.B
    }

    /**
      * ------------------------------------------------------- 
      * Send mainpipe task
      * -------------------------------------------------------
      */
    // TODO: mshrOpcodes: update directory, write TempDataStorage data in to DataStorage
    val needRefillData_hit  = dirResp.hit && needProbe
    val needRefillData_miss = !dirResp.hit
    val needRefillData      = (req.opcode === AcquireBlock || req.opcode === Get) && (needRefillData_hit || needRefillData_miss)
    val needProbeAckData    = false.B // TODO:
    val needTempDsData      = needRefillData || needProbeAckData
    val mpGrant             = !state.s_grant && !state.w_grantack
    val mpAccessAck         = !state.s_accessack
    io.tasks.mpTask.valid := valid &&
        (mpGrant || mpAccessAck) &&
        (state.s_read && state.w_compdat && state.s_compack) && // wait read finish
        (state.s_makeunique && state.w_comp && state.s_compack) &&
        (state.s_aprobe && state.w_aprobeack) // need to wait Probe finish (cause by Acquire)
    io.tasks.mpTask.bits.isMshrTask   := true.B
    io.tasks.mpTask.bits.opcode       := MuxCase(DontCare, Seq((req.opcode === AcquireBlock) -> GrantData, (req.opcode === AcquirePerm) -> Grant, (req.opcode === Get) -> AccessAckData)) // TODO:
    io.tasks.mpTask.bits.sink         := id.U
    io.tasks.mpTask.bits.source       := req.source
    io.tasks.mpTask.bits.set          := req.set
    io.tasks.mpTask.bits.tag          := req.tag
    io.tasks.mpTask.bits.wayOH        := dirResp.wayOH                                                                                                                                    // TODO:
    io.tasks.mpTask.bits.dataId       := dataId
    io.tasks.mpTask.bits.updateDir    := !reqIsGet || reqIsGet && needProbe || !dirResp.hit
    io.tasks.mpTask.bits.newMetaEntry := newMetaEntry
    io.tasks.mpTask.bits.readTempDs   := needTempDsData
    io.tasks.mpTask.bits.tempDsDest := Mux(
        !dirResp.hit || dirResp.hit && meta.isTrunk,
        /** For TRUNK state, dirty data will be written into [[DataStorage]] after receiving ProbeAckData */
        DataDestination.SourceD | DataDestination.DataStorage,
        DataDestination.SourceD
    )

    newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = gotDirty, // TODO: Release ?
        state = Mux(
            reqIsGet,
            Mux(dirResp.hit, Mux(meta.isTip || meta.isTrunk, TIP, BRANCH), Mux(reqPromoteT, TIP, BRANCH)),
            Mux(reqPromoteT || reqNeedT, Mux(reqIsPrefetch, TIP, TRUNK), Mux(reqNeedB && meta.isTrunk, TIP, BRANCH))
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
        state.s_grant     := true.B
        state.s_accessack := true.B
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
            dbid     := rxdat.bits.dbID
        }
    }
    assert(!(rxdat.fire && state.w_compdat), s"mshr_${id} is not watting for rxdat")

    /** Receive RXRSP response (Comp) */
    val rxrsp = io.resps.rxrsp
    when(rxrsp.fire && rxrsp.bits.last) {
        state.w_comp := true.B
        dbid         := rxrsp.bits.dbID
    }
    assert(!(rxrsp.fire && state.w_comp), s"mshr_${id} is not watting for rxrsp")

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
        val probeAckClient      = getClientBitOH(sinkc.bits.source)
        val probeAckIdx         = OHToUInt(probeAckClient)
        val nextProbeAckClients = probeAckClients | probeAckClient
        probeAckClients             := nextProbeAckClients
        probeAckParams(probeAckIdx) := sinkc.bits.param

        assert(
            (probeAckClient & probeClients).orR,
            "probeAckClient: 0b%b is not required, required probeClients: 0b%b, meta.clientsOH: 0b%b, reqClientOH: 0b%b",
            probeAckClient,
            probeClients,
            meta.clientsOH,
            reqClientOH
        )

        when(sinkc.bits.opcode === ProbeAck) {
            state.w_aprobeack := state.w_aprobeack | probeFinish | (nextProbeAckClients === probeClients)
        }.elsewhen(sinkc.bits.opcode === ProbeAckData) {
            gotDirty := true.B

            state.w_aprobeack_first := true.B
            state.w_aprobeack       := state.w_aprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)
        }.otherwise {
            // TODO:
            assert(false.B, "TODO:")
        }
    }
    probeFinish := probeClients === probeAckClients
    assert(!(sinkc.fire && state.w_aprobeack && state.w_sprobeack && state.w_rprobeack), s"mshr_${id} is not watting for sinkc")

    // val rxrsp = io.resps.rxrsp

    /**
      * Check if there has any request in the [[MSHR]] waiting for responses or waiting for sehcduling tasks.
      * If there is, then we cannot free the [[MSHR]].
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
