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
import Utils.LeakChecker

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
    val s_cbwrdata   = Bool()
    val s_compack    = Bool() // response CompAck downwards
    val s_makeunique = Bool()
    val s_accessack  = Bool()
    val s_repl       = Bool() // send replTask to MainPipe

    // w: wait
    val w_grantack        = Bool()
    val w_compdat         = Bool()
    val w_compdat_first   = Bool()
    val w_aprobeack       = Bool()
    val w_aprobeack_first = Bool()
    val w_rprobeack       = Bool()
    val w_rprobeack_first = Bool()
    val w_sprobeack       = Bool()
    val w_sprobeack_first = Bool()
    val w_dbidresp        = Bool()
    val w_comp            = Bool()
    val w_replResp        = Bool()
}

class MshrInfo()(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}

class MshrStatus()(implicit p: Parameters) extends L2Bundle {
    val valid     = Bool()
    val set       = UInt(setBits.W)
    val reqTag    = UInt(tagBits.W)
    val metaTag   = UInt(tagBits.W)
    val needsRepl = Bool()
    val wayOH     = UInt(ways.W)
    val dirHit    = Bool()
    val lockWay   = Bool()
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

class MshrRetryStage2()(implicit p: Parameters) extends L2Bundle {
    val grant_s2     = Bool() // GrantData
    val accessack_s2 = Bool() // AccessAckData
    val cbwrdata_s2  = Bool() // CopyBackWrData
    val snpresp_s2   = Bool() // SnpRespData
}

class MshrRetryStage4()(implicit p: Parameters) extends L2Bundle {
    val snpresp_s4 = Bool() // SnpResp
}

class MshrRetryTasks()(implicit p: Parameters) extends L2Bundle {
    val stage2 = new MshrRetryStage2
    val stage4 = new MshrRetryStage4
}

class MSHR()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val alloc_s3    = Flipped(ValidIO(new MshrAllocBundle))
        val replResp_s3 = Flipped(ValidIO(new DirReplResp))
        val status      = Output(new MshrStatus)
        val tasks       = new MshrTasks
        val resps       = new MshrResps
        val retryTasks  = Input(new MshrRetryTasks)
        val id          = Input(UInt(mshrBits.W))
    })

    val valid   = RegInit(false.B)
    val req     = RegInit(0.U.asTypeOf(new TaskBundle))
    val dirResp = RegInit(0.U.asTypeOf(new DirResp))

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
    val gotDirty        = RegInit(false.B)
    val gotT            = RegInit(false.B)
    val needProbe       = RegInit(false.B)
    val needPromote     = RegInit(false.B)
    val probeAckParams  = RegInit(VecInit(Seq.fill(nrClients)(0.U.asTypeOf(chiselTypeOf(io.resps.sinkc.bits.param)))))
    val probeAckClients = RegInit(0.U(nrClients.W))
    val probeFinish     = WireInit(false.B)
    val probeClients    = Mux(!state.w_rprobeack || !state.w_sprobeack, meta.clientsOH, ~reqClientOH & meta.clientsOH) // TODO: multiple clients, for now only support up to 2 clients(core 0 and core 1)
    assert(
        !(!state.s_aprobe && !req.isAliasTask && PopCount(probeClients) === 0.U),
        "Acquir Probe has no probe clients! probeClients: 0b%b reqClientOH: 0b%b meta.clientOH: 0b%b dirHit:%b isAliasTask:%b addr:%x",
        probeClients,
        reqClientOH,
        meta.clientsOH,
        dirResp.hit,
        req.isAliasTask,
        Cat(req.tag, req.set, 0.U(6.W))
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

        needProbe   := !allocState.s_aprobe | !allocState.s_sprobe | !allocState.s_rprobe
        needPromote := !allocState.s_makeunique

        dbid            := 0.U
        gotT            := false.B
        gotDirty        := false.B
        probeAckClients := 0.U
        probeAckParams.foreach(_ := 0.U)
    }

    /**
     * ------------------------------------------------------- 
     * Send [[TXREQ]] task
     * -------------------------------------------------------
     */
    io.tasks.txreq <> DontCare
    io.tasks.txreq.valid := !state.s_read ||
        !state.s_makeunique ||
        (!state.s_evict || !state.s_wb) && state.w_rprobeack // Evict/WriteBackFull should wait for refill and probeack finish
    io.tasks.txreq.bits.opcode := PriorityMux(
        Seq(
            (!state.s_read || !state.s_makeunique) -> ParallelPriorityMux(
                Seq(
                    (req.param === BtoT && dirResp.hit) -> MakeUnique,
                    reqNeedT                            -> ReadUnique,
                    reqNeedB                            -> ReadNotSharedDirty
                )
            ),
            !state.s_evict -> Evict,
            !state.s_wb    -> WriteBackFull
        )
    )
    io.tasks.txreq.bits.addr       := Cat(Mux(!state.s_evict || !state.s_wb, meta.tag, req.tag), req.set, 0.U(6.W)) // TODO: MultiBank
    io.tasks.txreq.bits.allowRetry := true.B                                                                        // TODO: Retry
    io.tasks.txreq.bits.expCompAck := !state.s_read                                                                 // TODO: only for Read not for EvictS
    io.tasks.txreq.bits.size       := log2Ceil(blockBytes).U
    io.tasks.txreq.bits.order      := Order.None                                                                    // No ordering required
    io.tasks.txreq.bits.memAttr    := MemAttr(allocate = !state.s_wb, cacheable = true.B, device = false.B, ewa = true.B)
    io.tasks.txreq.bits.snpAttr    := true.B
    io.tasks.txreq.bits.srcID      := DontCare                                                                      // This value will be assigned in output chi portr
    io.tasks.txreq.bits.txnID      := io.id
    when(io.tasks.txreq.fire) {
        val opcode = io.tasks.txreq.bits.opcode
        state.s_read       := state.s_read || (opcode === ReadUnique || opcode === ReadNotSharedDirty)
        state.s_makeunique := state.s_makeunique || opcode === MakeUnique
        state.s_evict      := state.s_evict || opcode === Evict
        state.s_wb         := state.s_wb || opcode === WriteBackFull
    }
    assert(!(!state.s_evict && !state.s_wb))

    /**
     * ------------------------------------------------------- 
     * Send [[TXRSP]] task
     * -------------------------------------------------------
     */
    io.tasks.txrsp               <> DontCare
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
     * Send [[SourceB]] task
     * -------------------------------------------------------
     */
    io.tasks.sourceb.valid := !state.s_aprobe /* TODO: acquire probe with MakeUnique */ ||
        !state.s_sprobe ||
        !state.s_rprobe // Replace Probe should wait for refill finish, otherwise, it is possible that the ProbeAckData will replce the original CompData in TempDataStorage from downstream cache
    io.tasks.sourceb.bits.opcode  := Probe
    io.tasks.sourceb.bits.param   := Mux(!state.s_sprobe, Mux(CHIOpcodeSNP.isSnpUniqueX(req.opcode) || CHIOpcodeSNP.isSnpToN(req.opcode), toN, toB), Mux(!state.s_rprobe, toN, Mux(reqNeedT, toN, toB)))
    io.tasks.sourceb.bits.address := Cat(Mux(!state.s_rprobe, meta.tag, req.tag), req.set, 0.U(6.W)) // TODO: MultiBank
    io.tasks.sourceb.bits.size    := log2Ceil(blockBytes).U
    io.tasks.sourceb.bits.data    := DontCare
    io.tasks.sourceb.bits.mask    := DontCare
    io.tasks.sourceb.bits.corrupt := DontCare
    io.tasks.sourceb.bits.source  := clientOHToSource(probeClients)                                  // TODO:
    when(io.tasks.sourceb.fire) {
        state.s_aprobe := true.B
        state.s_sprobe := true.B
        state.s_rprobe := true.B

        assert(probeClients.orR, "probeClients:0b%b should not be empty", probeClients)
    }

    /**
     * ------------------------------------------------------- 
     * Send [[MainPipe]] task
     * -------------------------------------------------------
     */
    val mpTask_refill, mpTask_repl, mpTask_wbdata, mpTask_snpresp = WireInit(0.U.asTypeOf(Valid(new TaskBundle)))
    Seq(mpTask_refill, mpTask_repl, mpTask_wbdata, mpTask_snpresp).foreach { task =>
        /** Assignment signal values to common fields */
        task.bits.isMshrTask := true.B
        task.bits.sink       := io.id
        task.bits.source     := req.source
        task.bits.set        := req.set
        task.bits.tag        := req.tag
        task.bits.wayOH      := dirResp.wayOH // TODO:
    }

    /** Send the final refill response to the upper level */
    val needRefillData_hit  = dirResp.hit && (needProbe || needPromote)
    val needRefillData_miss = !dirResp.hit
    val needRefillData      = (req.opcode === AcquireBlock || req.opcode === Get) && (needRefillData_hit || needRefillData_miss)
    val needProbeAckData    = false.B // TODO:
    val needTempDsData      = needRefillData || needProbeAckData
    val mpGrant             = !state.s_grant && !state.w_grantack
    val mpAccessAck         = !state.s_accessack

    // TODO: mshrOpcodes: update directory, write TempDataStorage data in to DataStorage
    mpTask_refill.valid := valid &&
        (mpGrant || mpAccessAck) &&
        state.w_replResp && state.w_rprobeack && state.s_wb && state.s_cbwrdata && RegNext(RegNext(state.s_cbwrdata, true.B), true.B) /* delay two cycle to meet the DataStorage timing */ /* && state.s_evict && state.w_comp */ && // wait for all Evict/WriteBackFull(replacement operations) finish
        (state.s_read && state.w_compdat && state.s_compack) &&                                                                                                                                                                      // wait read finish
        (state.s_makeunique && state.w_comp && state.s_compack) &&
        (state.s_aprobe && state.w_aprobeack) // need to wait for aProbe to finish (cause by Acquire)
    mpTask_refill.bits.opcode := MuxCase(DontCare, Seq((req.opcode === AcquireBlock) -> GrantData, (req.opcode === AcquirePerm) -> Grant, (req.opcode === Get) -> AccessAckData)) // TODO:
    mpTask_refill.bits.param := Mux(
        reqIsGet || reqIsPrefetch,
        0.U,       // Get -> AccessAckData
        MuxLookup( // Acquire -> Grant
            req.param,
            req.param
        )(
            Seq(
                NtoB -> Mux(reqPromoteT, toT, toB),
                BtoT -> toT,
                NtoT -> toT
            )
        )
    )
    mpTask_refill.bits.readTempDs := needTempDsData
    mpTask_refill.bits.isReplTask := !dirResp.hit && !meta.isInvalid && !state.w_replResp
    mpTask_refill.bits.updateDir  := !reqIsGet || reqIsGet && needProbe || !dirResp.hit
    mpTask_refill.bits.tempDsDest := Mux(
        !dirResp.hit || dirResp.hit && meta.isTrunk,
        /** For TRUNK state, dirty data will be written into [[DataStorage]] after receiving ProbeAckData */
        DataDestination.SourceD | DataDestination.DataStorage,
        DataDestination.SourceD
    )
    mpTask_refill.bits.newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = gotDirty || dirResp.hit && meta.isDirty, // TODO: Release ?
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

    /** Send CopyBack task to [[MainPipe]], including: WriteBackFull */
    mpTask_wbdata.valid            := !state.s_cbwrdata && state.w_dbidresp
    mpTask_wbdata.bits.txnID       := dbid
    mpTask_wbdata.bits.isCHIOpcode := true.B
    mpTask_wbdata.bits.opcode      := CopyBackWrData
    mpTask_wbdata.bits.channel     := CHIChannel.TXDAT
    mpTask_wbdata.bits.readTempDs  := false.B
    mpTask_wbdata.bits.updateDir   := false.B
    mpTask_wbdata.bits.resp        := Mux(!meta.isInvalid, Resp.UD_PD, Resp.I)
    assert(!(mpTask_wbdata.fire && meta.isBranch), "CopyBackWrData is only for Tip Dirty")

    def stateToResp(rawState: UInt, dirty: Bool, passDirty: Bool) = {
        val resp = Mux(
            dirty,
            MuxLookup(rawState, Resp.I)(
                Seq(
                    BRANCH -> Resp.SD,
                    TIP    -> Resp.UD,
                    TRUNK  -> Resp.UD
                )
            ),
            MuxLookup(rawState, Resp.I)(
                Seq(
                    BRANCH -> Resp.SC,
                    TIP    -> Resp.UC,
                    TRUNK  -> Resp.UC
                )
            )
        )

        Resp.setPassDirty(resp, passDirty)
    }

    /** Send SnpRespData/SnpResp task to [[MainPipe]] */
    val isSnpOnceX        = CHIOpcodeSNP.isSnpOnceX(req.opcode)
    val isSnpSharedX      = CHIOpcodeSNP.isSnpSharedX(req.opcode)
    val snprespPassDirty  = !isSnpOnceX && (meta.isDirty || gotDirty)
    val snprespFinalDirty = isSnpOnceX && meta.isDirty
    val snprespFinalState = Mux(isSnpOnceX, meta.rawState, Mux(isSnpSharedX, BRANCH, INVALID))
    mpTask_snpresp.valid            := !state.s_snpresp && state.w_sprobeack
    mpTask_snpresp.bits.txnID       := req.txnID
    mpTask_snpresp.bits.isCHIOpcode := true.B
    mpTask_snpresp.bits.opcode      := Mux(gotDirty || req.retToSrc || meta.isDirty, SnpRespData, SnpResp)
    mpTask_snpresp.bits.resp        := stateToResp(snprespFinalState, snprespFinalDirty, snprespPassDirty)
    mpTask_snpresp.bits.channel     := Mux(gotDirty || req.retToSrc || meta.isDirty, CHIChannel.TXDAT, CHIChannel.TXRSP)
    mpTask_snpresp.bits.readTempDs  := gotDirty || req.retToSrc
    mpTask_snpresp.bits.tempDsDest  := DataDestination.TXDAT // | DataDestination.DataStorage
    mpTask_snpresp.bits.updateDir   := true.B
    mpTask_snpresp.bits.newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = snprespFinalDirty,
        state = snprespFinalState,
        alias = req.aliasOpt.getOrElse(0.U),
        clientsOH = Mux(isSnpSharedX || isSnpOnceX, meta.clientsOH, Fill(nrClients, false.B)),
        fromPrefetch = false.B
    )
    assert(!(valid && snprespPassDirty && snprespFinalDirty))

    mpTask_repl.valid           := !state.s_repl && !state.w_replResp && state.s_read && state.s_makeunique && state.w_comp && state.w_compdat && state.s_compack
    mpTask_repl.bits.isReplTask := true.B
    mpTask_repl.bits.readTempDs := false.B
    mpTask_repl.bits.updateDir  := false.B

    /** Arbitration between multiple [[MainPipe]] tasks */
    io.tasks.mpTask.valid := mpTask_refill.valid || mpTask_wbdata.valid || mpTask_repl.valid || mpTask_snpresp.valid
    io.tasks.mpTask.bits := PriorityMux(
        Seq(
            mpTask_refill.valid  -> mpTask_refill.bits,
            mpTask_wbdata.valid  -> mpTask_wbdata.bits,
            mpTask_snpresp.valid -> mpTask_snpresp.bits,
            mpTask_repl.valid    -> mpTask_repl.bits
        )
    )

    /** When the mpTask fires, reset the corresponding state flag */
    when(io.tasks.mpTask.ready) {
        when(mpTask_refill.valid) {
            state.s_grant     := true.B
            state.s_accessack := true.B
        }

        when(mpTask_wbdata.valid) {
            state.s_cbwrdata := true.B // TODO: Extra signals to indicate whether the cbwrdata is sent and leaves L2Cache.
        }

        when(mpTask_repl.valid) {
            state.s_repl := true.B
        }

        when(mpTask_snpresp.valid) {
            state.s_snpresp := true.B
        }
    }

    /** mpTask needs to be retried due to insufficent resources  */
    when(io.retryTasks.stage2.accessack_s2) {
        state.s_accessack := false.B
        assert(state.s_accessack, "try to retry an already activated task!")
        assert(valid, "retry on an invalid mshr!")
    }
    when(io.retryTasks.stage2.grant_s2) {
        state.s_grant := false.B
        assert(state.s_grant, "try to retry an already activated task!")
        assert(valid, "retry on an invalid mshr!")
    }
    when(io.retryTasks.stage2.cbwrdata_s2) {
        state.s_cbwrdata := false.B
        assert(state.s_cbwrdata, "try to retry an already activated task!")
        assert(valid, "retry on an invalid mshr!")
    }
    when(io.retryTasks.stage2.snpresp_s2) {
        state.s_snpresp := false.B
        assert(state.s_snpresp, "try to retry an already activated task!")
        assert(valid, "retry on an invalid mshr!")
    }
    when(io.retryTasks.stage4.snpresp_s4) {
        state.s_snpresp := false.B
        assert(state.s_snpresp, "try to retry an already activated task!")
        assert(valid, "retry on an invalid mshr!")
    }
    val retryVec_s2 = VecInit(io.retryTasks.stage2.elements.map(_._2).toSeq).asUInt
    assert(!(PopCount(retryVec_s2) > 1.U), "only allow one retry task at stage2! retryVec_s2:0b%b", retryVec_s2)

    /** Receive [[RXDAT]] responses, including: CompData */
    val rxdat = io.resps.rxdat
    when(rxdat.fire) {
        when(rxdat.bits.last) {
            state.w_compdat := true.B

            when(rxdat.bits.chiOpcode === CompData) {
                gotT     := rxdat.bits.resp === Resp.UC || rxdat.bits.resp === Resp.UC_PD
                gotDirty := gotDirty || rxdat.bits.resp === Resp.UC_PD
                dbid     := rxdat.bits.dbID
            }
        }.otherwise {
            state.w_compdat_first := true.B
        }
    }
    assert(!(rxdat.fire && state.w_compdat), s"mshr is not watting for rxdat")

    /** Receive [[RXRSP]] response, including: Comp */
    val rxrsp = io.resps.rxrsp
    when(rxrsp.fire && rxrsp.bits.last) {
        dbid := rxrsp.bits.dbID

        val opcode = rxrsp.bits.chiOpcode
        when(opcode === Comp) {
            state.w_comp := true.B
        }.elsewhen(opcode === CompDBIDResp) {
            state.w_dbidresp := true.B
        }
    }
    assert(!(rxrsp.fire && state.w_comp && state.w_dbidresp), s"mshr is not watting for rxrsp")

    /**
     * A [[MSHR]] will wait GrantAck for Acquire requests.
     * If mshr not receive a GrantAck, it cannot be freed. 
     * This allow external logic to check the mshr to determine whether the Grant/GrantData has been received by upstream cache.
     * If we ignore this response, then we have to add some logic in [[SourceD]], so that we could do the same thing as we implement here.
     */
    val sinke = io.resps.sinke
    when(sinke.fire && state.s_grant) {
        state.w_grantack := true.B
    }

    /** Should not check w_grantack beacuse it is possible that the GrantAck is received even if no mshr is matched due to the request hit on [[MainPipe]]. */
    // assert(!(sinke.fire && state.w_grantack), s"mshr is not watting for sinke")

    /** Receive ProbeAck/ProbeAckData response */
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
            state.w_aprobeack_first := true.B
            state.w_aprobeack       := state.w_aprobeack | probeFinish | (nextProbeAckClients === probeClients)

            state.w_rprobeack_first := true.B
            state.w_rprobeack       := state.w_rprobeack | probeFinish | (nextProbeAckClients === probeClients)

            state.w_sprobeack_first := true.B
            state.w_sprobeack       := state.w_sprobeack | probeFinish | (nextProbeAckClients === probeClients)
        }.elsewhen(sinkc.bits.opcode === ProbeAckData) {
            gotDirty := Mux(!state.w_rprobeack, gotDirty, true.B) // rprobeack is NOT the same cacheline as the request cacheline

            state.w_aprobeack_first := true.B
            state.w_aprobeack       := state.w_aprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            state.w_rprobeack_first := true.B
            state.w_rprobeack       := state.w_rprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            state.w_sprobeack_first := true.B
            state.w_sprobeack       := state.w_sprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            when(!state.s_evict) {
                state.s_evict := true.B
                state.w_comp  := true.B

                state.s_wb       := false.B
                state.w_dbidresp := false.B
                state.s_cbwrdata := false.B
            }
        }.otherwise {
            // TODO:
            assert(false.B, "TODO:")
        }
    }
    probeFinish := probeClients === probeAckClients
    assert(!(sinkc.fire && state.w_aprobeack && state.w_sprobeack && state.w_rprobeack), s"MSHR is not watting for sinkc")
    // assert(!(sinkc.fire && !state.w_rprobeack && !state.s_grant), "Should not receive ProbeAck until Grant/GrantData is sent!")

    // val rxrsp = io.resps.rxrsp

    val replResp = io.replResp_s3
    when(replResp.fire) {

        when(!replResp.bits.retry) {
            state.w_replResp := true.B

            /** Update directory meta */
            dirResp.meta  := replResp.bits.meta
            dirResp.wayOH := replResp.bits.wayOH

            when(replResp.bits.meta.state =/= MixedState.I) {

                /** 
                 * Select the replace operation according to the received response information.
                 * The selected replace operation can also be changed based on the received ProbeAck response.
                 * e.g. If replResp indicates that it is not a dirty way while the received ProbeAckData 
                 *      indicates that it has dirty data from clients, hence the final replace operation 
                 *      will be changed from Evict to WriteBackFull.
                 */
                when(replResp.bits.meta.isDirty) {
                    state.s_wb       := false.B // Send WriteBackFull is it is a dirty way
                    state.w_dbidresp := false.B // Wait CompDBIDResp
                    state.s_cbwrdata := false.B // Send CopyBackWrData
                }.otherwise {
                    state.s_evict := false.B // Send Evict if it is not a dirty way
                    state.w_comp  := false.B // Wait Comp
                }

                /** Send Probe if victim way has clients */
                when(replResp.bits.meta.clientsOH.orR) {
                    // rprobe: Probe trigged by replacement operation(Evict/WriteBackFull)
                    state.s_rprobe          := false.B
                    state.w_rprobeack       := false.B
                    state.w_rprobeack_first := false.B
                }
            }
        }.otherwise {
            state.s_repl      := false.B
            state.s_accessack := !reqIsGet
            state.s_grant     := !reqIsAcquire
            dirResp.wayOH     := replResp.bits.wayOH
        }
    }
    assert(!(replResp.fire && state.w_replResp), s"mshr is not watting for replResp_s3")

    /**
     * Check if there is any request in the [[MSHR]] waiting for responses or waiting for sehcduling tasks.
     * If there is, then we cannot free the [[MSHR]].
     */
    val noWait     = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("w_")) => signal }.toSeq).asUInt.andR
    val noSchedule = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("s_")) => signal }.toSeq).asUInt.andR

    def recursiveRegNext(n: Int, init: Bool): Bool = {
        if (n == 0) init
        else RegNext(recursiveRegNext(n - 1, init), true.B)
    }
    val maxWaitCnt_base       = 3
    val maxWaitCnt_extra      = 2 // We need extra cycles to wait for retry beacuse stage1 may be stalled due to DataStorage being busy(multi-cycle path), hence we cannot determine the definite cycle to wait for retry.
    val maxWaitCnt            = maxWaitCnt_base + maxWaitCnt_extra
    val waitForSent_grant     = (0 until maxWaitCnt).map(i => recursiveRegNext(i, state.s_grant)).reduce(_ && _)
    val waitForSent_accessack = (0 until maxWaitCnt).map(i => recursiveRegNext(i, state.s_accessack)).reduce(_ && _)
    val waitForSent_snpresp   = (0 until maxWaitCnt + 2).map(i => recursiveRegNext(i, state.s_snpresp)).reduce(_ && _)
    val hasRetry              = VecInit(io.retryTasks.stage2.elements.map(_._2).toSeq).asUInt.orR || VecInit(io.retryTasks.stage4.elements.map(_._2).toSeq).asUInt.orR

    val willFree = noWait && noSchedule && waitForSent_grant && waitForSent_accessack && waitForSent_snpresp && !hasRetry
    when(valid && willFree) {
        valid := false.B
    }

    // TODO: deadlock check
    val evictNotSent = !state.s_evict
    val wbNotSent    = !state.s_wb
    io.status.valid     := valid
    io.status.set       := req.set
    io.status.reqTag    := req.tag
    io.status.metaTag   := dirResp.meta.tag
    io.status.needsRepl := evictNotSent || wbNotSent                                                                                                // Used by MissHandler to guide the ProbeAck/ProbeAckData response to the match the correct MSHR
    io.status.wayOH     := dirResp.wayOH
    io.status.lockWay   := !dirResp.hit && meta.isInvalid || !dirResp.hit && !(state.s_evict && state.w_comp && state.s_grant && state.s_accessack) // Lock the CacheLine way that will be used in later Evict or WriteBackFull. TODO:
    io.status.dirHit    := dirResp.hit                                                                                                              // Used by Directory to occupy a particular way.

    LeakChecker(io.status.valid, !io.status.valid, Some(s"mshr_valid"), maxCount = deadlockThreshold)
    // TODO: LeakChecker(io.retryTasks.stage2.accessack_s2, io.alloc_s3.fire, Some("mshr_accessack_retry"), maxCount = deadlockThreshold - 100)
}

object MSHR extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MSHR()(config), name = "MSHR", split = false)
}
