package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import xs.utils.{ParallelPriorityMux}
import Utils.{GenerateVerilog, LeakChecker}
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
    val s_accessack  = Bool()
    val s_cbwrdata   = Bool()
    val s_snpresp    = Bool() // resposne SnpResp downwards
    val s_evict      = Bool() // evict downwards(for clean state)
    val s_wb         = Bool() // writeback downwards(for dirty state)
    val s_compack    = Bool() // response CompAck downwards
    val s_makeunique = Bool()
    val s_repl       = Bool() // send replTask to MainPipe

    // w: wait
    val w_grant_sent      = Bool()
    val w_accessack_sent  = Bool()
    val w_cbwrdata_sent   = Bool()
    val w_snpresp_sent    = Bool()
    val w_grantack        = Bool()
    val w_compdat         = Bool()
    val w_compdat_first   = Bool()
    val w_aprobeack       = Bool()
    val w_aprobeack_first = Bool()
    val w_rprobeack       = Bool()
    val w_rprobeack_first = Bool()
    val w_sprobeack       = Bool()
    val w_sprobeack_first = Bool()
    val w_compdbid        = Bool()
    val w_comp            = Bool() // comp for read transaction
    val w_evict_comp      = Bool() // comp for evict transaction
    val w_replResp        = Bool()
}

class MshrInfo()(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}

class MshrStatus()(implicit p: Parameters) extends L2Bundle {
    val valid     = Bool()
    val willFree  = Bool()
    val set       = UInt(setBits.W)
    val reqTag    = UInt(tagBits.W)
    val metaTag   = UInt(tagBits.W)
    val needsRepl = Bool()
    val wayOH     = UInt(ways.W)
    val dirHit    = Bool()
    val lockWay   = Bool()
    val state     = UInt(MixedState.width.W)

    val w_replResp   = Bool()
    val w_rprobeack  = Bool()
    val w_evict_comp = Bool()
    val w_compdbid   = Bool()
    val w_comp_first = Bool()

    val waitProbeAck  = Bool()
    val waitGrantAck  = Bool()
    val replGotDirty  = Bool()
    val isChannelA    = Bool()
    val reqAllowSnoop = Bool()
    val gotDirtyData  = Bool() // TempDS has dirty data
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
    val isRetry_s2   = Bool()
    val grant_s2     = Bool() // GrantData
    val accessack_s2 = Bool() // AccessAckData
    val cbwrdata_s2  = Bool() // CopyBackWrData
    val snpresp_s2   = Bool() // SnpRespData
}

class MshrRetryStage4()(implicit p: Parameters) extends L2Bundle {
    val isRetry_s4   = Bool()
    val grant_s4     = Bool() // GrantData
    val accessack_s4 = Bool() // AccessAckData
    val cbwrdata_s4  = Bool() // CopyBackWrData
    val snpresp_s4   = Bool() // SnpResp / SnpRespData
}

class MshrRetryTasks()(implicit p: Parameters) extends L2Bundle {
    val stage2 = ValidIO(new MshrRetryStage2)
    val stage4 = ValidIO(new MshrRetryStage4)
}

class MshrEarlyNested()(implicit p: Parameters) extends L2Bundle {
    // Early nested info to indicate whether the next cycle will make a mshr nested operation.
    val set       = UInt(setBits.W)
    val tag       = UInt(tagBits.W)
    val isMshr    = Bool()
    val isSnpToN  = Bool()
    val isRelease = Bool()
}

class MshrNestedSnoop()(implicit p: Parameters) extends L2Bundle {
    val toN        = Bool()
    val toB        = Bool()
    val cleanDirty = Bool()
}

class MshrNestedRelease()(implicit p: Parameters) extends L2Bundle {
    val TtoN     = Bool()
    val BtoN     = Bool()
    val setDirty = Bool()
}

class MshrNestedWriteback()(implicit p: Parameters) extends L2Bundle {
    val set     = UInt(setBits.W)
    val tag     = UInt(tagBits.W)
    val isMshr  = Bool()
    val mshrId  = UInt(mshrBits.W)
    val source  = UInt(tlBundleParams.sourceBits.W)
    val snoop   = new MshrNestedSnoop
    val release = new MshrNestedRelease
}

class MSHR()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val alloc_s3      = Flipped(ValidIO(new MshrAllocBundle))
        val replResp_s3   = Flipped(ValidIO(new DirReplResp))
        val status        = Output(new MshrStatus)
        val tasks         = new MshrTasks
        val resps         = new MshrResps
        val retryTasks    = Flipped(new MshrRetryTasks)
        val earlyNested   = Input(new MshrEarlyNested)
        val nested        = Input(new MshrNestedWriteback)
        val respMapCancel = DecoupledIO(UInt(mshrBits.W))
        val id            = Input(UInt(mshrBits.W))
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

    val isRealloc       = RegInit(false.B)
    val reallocTxnID    = RegInit(0.U(req.txnID.getWidth.W))
    val reallocOpcode   = RegInit(0.U(req.opcode.getWidth.W))
    val reallocRetToSrc = RegInit(false.B)

    val rspDBID         = RegInit(0.U(chiBundleParams.DBID_WIDTH.W))
    val datDBID         = RegInit(0.U(chiBundleParams.DBID_WIDTH.W))
    val gotCompData     = RegInit(false.B)
    val gotDirty        = RegInit(false.B)
    val releaseGotDirty = RegInit(false.B)
    val gotT            = RegInit(false.B)
    val replGotDirty    = RegInit(false.B)                                                                                                // gotDirty for replacement cache line
    val needProbe       = RegInit(false.B)
    val needRead        = RegInit(false.B)
    val needPromote     = RegInit(false.B)
    val needWb          = RegInit(false.B)
    val nestedRelease   = RegInit(false.B)
    val probeGotDirty   = RegInit(false.B)
    val snpGotDirty     = RegInit(false.B)                                                                                                // for reallocation
    val probeAckParams  = RegInit(VecInit(Seq.fill(nrClients)(0.U.asTypeOf(chiselTypeOf(io.resps.sinkc.bits.param)))))
    val probeAckClients = RegInit(0.U(nrClients.W))
    val probeFinish     = WireInit(false.B)
    val probeClients    = Mux(!state.w_rprobeack || !state.w_sprobeack || req.isAliasTask, meta.clientsOH, ~reqClientOH & meta.clientsOH) // TODO: multiple clients, for now only support up to 2 clients(core 0 and core 1)
    val finishedProbes  = RegInit(0.U(nrClients.W))
    assert(
        !(!state.s_aprobe && !req.isAliasTask && !nestedRelease && PopCount(probeClients) === 0.U), // It is possible that probeClients is 0 if mshr is nested by Relase.
        "Acquire Probe has no probe clients! probeClients: 0b%b reqClientOH: 0b%b meta.clientOH: 0b%b dirHit:%b isAliasTask:%b nestedRelease:%b addr:%x",
        probeClients,
        reqClientOH,
        meta.clientsOH,
        dirResp.hit,
        req.isAliasTask,
        nestedRelease,
        Cat(req.tag, req.set, 0.U(6.W))
    ) // TODO:

    val promoteT_normal = dirResp.hit && metaNoClients && meta.isTip
    val promoteT_l3     = !dirResp.hit && gotT
    val promoteT_alias  = dirResp.hit && req.isAliasTask && (meta.isTrunk || meta.isTip)
    val reqPromoteT     = (reqIsAcquire || reqIsGet || reqIsPrefetch) && (promoteT_normal || promoteT_l3 || promoteT_alias) // TODO:

    when(io.alloc_s3.fire && !io.alloc_s3.bits.realloc) {
        val allocState = io.alloc_s3.bits.fsmState

        valid   := true.B
        req     := io.alloc_s3.bits.req
        dirResp := io.alloc_s3.bits.dirResp
        state   := allocState

        needProbe   := !allocState.s_aprobe | !allocState.s_sprobe | !allocState.s_rprobe
        needRead    := !allocState.s_read
        needPromote := !allocState.s_makeunique
        needWb      := false.B

        isRealloc := false.B

        rspDBID         := 0.U
        datDBID         := 0.U
        gotCompData     := false.B
        gotT            := false.B
        gotDirty        := false.B
        releaseGotDirty := false.B
        replGotDirty    := false.B
        nestedRelease   := false.B
        probeGotDirty   := false.B
        probeAckClients := 0.U
        finishedProbes  := 0.U
        probeAckParams.foreach(_ := NtoN)
    }

    when(io.alloc_s3.fire && io.alloc_s3.bits.realloc) {
        val allocState = io.alloc_s3.bits.fsmState

        isRealloc       := true.B
        reallocTxnID    := io.alloc_s3.bits.req.txnID
        reallocOpcode   := io.alloc_s3.bits.req.opcode
        reallocRetToSrc := io.alloc_s3.bits.req.retToSrc
        snpGotDirty     := io.alloc_s3.bits.snpGotDirty

        state.s_snpresp      := allocState.s_snpresp
        state.w_snpresp_sent := allocState.w_snpresp_sent

        assert(valid, "mshr should already be valid when reallocating")
    }

    /**
     * ------------------------------------------------------- 
     * Send [[TXREQ]] task
     * -------------------------------------------------------
     */
    val willCancelEvict = WireInit(false.B)
    val willCancelWb    = WireInit(false.B)
    val mayChangeEvict  = WireInit(false.B)
    val mayCancelEvict  = WireInit(false.B)
    val mayCancelWb     = WireInit(false.B)
    mayChangeEvict := RegNext(req.set === io.earlyNested.set && (meta.tag === io.earlyNested.tag || io.replResp_s3.valid && io.replResp_s3.bits.meta.tag === io.earlyNested.tag) && io.earlyNested.isRelease)
    mayCancelEvict := RegNext(req.set === io.earlyNested.set && (meta.tag === io.earlyNested.tag || io.replResp_s3.valid && io.replResp_s3.bits.meta.tag === io.earlyNested.tag) && io.earlyNested.isSnpToN)
    mayCancelWb    := mayCancelEvict

    io.tasks.txreq <> DontCare
    io.tasks.txreq.valid := (!state.s_read ||
        !state.s_makeunique ||
        (!state.s_evict && !mayChangeEvict && !mayCancelEvict || !state.s_wb && !mayCancelWb) && state.w_rprobeack) && state.s_snpresp && state.w_snpresp_sent // Evict/WriteBackFull should wait for refill and probeack finish
    io.tasks.txreq.bits.opcode := PriorityMux(
        Seq(
            (!state.s_read || !state.s_makeunique) -> ParallelPriorityMux(
                Seq(
                    (reqNeedT && dirResp.hit)  -> MakeUnique, // If we are nested by a SnpUnique, data still safe since we have already read data from DataStorage into TempDataStorage after allocation of MSHR at stage 3
                    (reqNeedT && !dirResp.hit) -> ReadUnique,
                    reqNeedB                   -> ReadNotSharedDirty
                )
            ),
            !state.s_evict -> Evict,
            !state.s_wb    -> WriteBackFull
        )
    )
    io.tasks.txreq.bits.addr       := Cat(Mux(!state.s_read || !state.s_makeunique, req.tag, meta.tag), req.set, 0.U(6.W))
    io.tasks.txreq.bits.allowRetry := false.B                              // TODO: Retry
    io.tasks.txreq.bits.expCompAck := !state.s_read || !state.s_makeunique // TODO: only for Read not for Evict
    io.tasks.txreq.bits.size       := log2Ceil(blockBytes).U
    io.tasks.txreq.bits.order      := Order.None                           // No ordering required
    io.tasks.txreq.bits.memAttr    := MemAttr(allocate = !state.s_wb, cacheable = true.B, device = false.B, ewa = true.B)
    io.tasks.txreq.bits.snpAttr    := true.B
    io.tasks.txreq.bits.srcID      := DontCare                             // This value will be assigned in output chi portr
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
    io.tasks.txrsp.bits.txnID    := Mux(needRead, datDBID, rspDBID) // TODO:
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
    val cancelProbe_dup = WireInit(false.B)
    val probeSource     = PriorityEncoderOH(~finishedProbes & probeClients)
    io.tasks.sourceb.valid := !cancelProbe_dup && (!state.s_aprobe /* TODO: acquire probe with MakeUnique */ ||
        !state.s_sprobe ||
        !state.s_rprobe) // Replace Probe should wait for refill finish, otherwise, it is possible that the ProbeAckData will replce the original CompData in TempDataStorage from downstream cache
    io.tasks.sourceb.bits.opcode  := Probe
    io.tasks.sourceb.bits.param   := Mux(!state.s_sprobe, Mux(CHIOpcodeSNP.isSnpUniqueX(req.opcode) || CHIOpcodeSNP.isSnpToN(req.opcode), toN, toB), Mux(!state.s_rprobe, toN, Mux(reqNeedT, toN, toB)))
    io.tasks.sourceb.bits.address := Cat(Mux(!state.s_rprobe, meta.tag, req.tag), req.set, 0.U(6.W)) // TODO: MultiBank
    io.tasks.sourceb.bits.size    := log2Ceil(blockBytes).U
    io.tasks.sourceb.bits.data    := Cat(dirResp.meta.aliasOpt.getOrElse(0.U), 0.U(1.W))
    io.tasks.sourceb.bits.mask    := DontCare
    io.tasks.sourceb.bits.corrupt := DontCare
    io.tasks.sourceb.bits.source  := clientOHToSource(probeSource)
    when(io.tasks.sourceb.fire) {
        assert(
            probeClients.orR,
            "probeClients:0b%b should not be empty s_aprobe:%d s_sprobe:%d s_rprobe:%d",
            probeClients,
            state.s_aprobe,
            state.s_sprobe,
            state.s_rprobe
        )

        val nextFinishedProbes = finishedProbes | getClientBitOH(io.tasks.sourceb.bits.source)
        finishedProbes := nextFinishedProbes

        when(nextFinishedProbes === probeClients) {
            state.s_aprobe := true.B
            state.s_sprobe := true.B
            state.s_rprobe := true.B
        }
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
    val needRefillData_hit  = dirResp.hit && (needProbe && probeGotDirty || needPromote) // if dirResp.hit, data is already read from DataStorage into TempDataStorage at stage3
    val needRefillData_miss = !dirResp.hit
    val needRefillData      = (req.opcode === AcquireBlock || req.opcode === Get) && (needRefillData_hit || needRefillData_miss)
    val needProbeAckData    = false.B                                                    // TODO:
    val needTempDsData      = needRefillData || needProbeAckData
    val mpGrant             = !state.s_grant && !state.w_grantack
    val mpAccessAck         = !state.s_accessack

    // TODO: mshrOpcodes: update directory, write TempDataStorage data in to DataStorage
    mpTask_refill.valid := valid &&
        (mpGrant || mpAccessAck) &&
        (state.w_replResp && state.w_rprobeack && state.s_wb && state.s_cbwrdata && state.w_cbwrdata_sent && state.s_evict && state.w_evict_comp) && // wait for WriteBackFull(replacement operations) finish. It is unnecessary to wait for Evict to complete, since Evict does not need to read the DataStorage; hence, mpTask_refill could be fired without worrying whether the refilled data will replace the victim data in DataStorage
        (state.s_read && state.w_compdat && state.s_compack) && // wait read finish
        (state.s_makeunique && state.w_comp && state.s_compack) &&
        (state.s_aprobe && state.w_aprobeack) &&  // need to wait for aProbe to finish (cause by Acquire)
        (state.s_snpresp && state.w_snpresp_sent) // need to wait for snpresp to finish (cause by realloc)
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
    mpTask_refill.bits.isCHIOpcode := false.B
    mpTask_refill.bits.readTempDs  := needTempDsData
    mpTask_refill.bits.isReplTask  := !dirResp.hit && !meta.isInvalid && !state.w_replResp
    mpTask_refill.bits.updateDir   := !reqIsGet || reqIsGet && needProbe || !dirResp.hit
    mpTask_refill.bits.tempDsDest := Mux(
        io.status.gotDirtyData,
        /** For TRUNK state, dirty data will be written into [[DataStorage]] after receiving ProbeAckData */
        DataDestination.SourceD | DataDestination.DataStorage,
        DataDestination.SourceD
    )

    val refillFinalState = Mux(
        reqIsGet,
        Mux(dirResp.hit, Mux(meta.isTip || meta.isTrunk, TIP, BRANCH), Mux(reqPromoteT, TIP, BRANCH)),
        Mux(reqPromoteT || reqNeedT, Mux(reqIsPrefetch, TIP, TRUNK), Mux(reqNeedB && meta.isTrunk && dirResp.hit, TIP, BRANCH))
    )
    mpTask_refill.bits.newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = (gotDirty || releaseGotDirty || dirResp.hit && meta.isDirty) && refillFinalState =/= BRANCH /* Branch should not have dirty state */,
        state = refillFinalState,
        alias = Mux(
            reqIsGet || reqIsPrefetch,
            meta.aliasOpt.getOrElse(0.U),
            req.aliasOpt.getOrElse(0.U)
        ),
        clientsOH = Mux(
            reqIsPrefetch,
            Mux(dirResp.hit, meta.clientsOH, Fill(nrClients, false.B)),
            MuxCase(
                Fill(nrClients, false.B),
                Seq(
                    (reqIsGet)                 -> Mux(dirResp.hit, meta.clientsOH, Fill(nrClients, false.B)),
                    (reqIsPrefetch)            -> meta.clientsOH,
                    (reqIsAcquire && reqNeedT) -> reqClientOH,
                    (reqIsAcquire && reqNeedB) -> Mux(dirResp.hit, meta.clientsOH | reqClientOH, reqClientOH)
                )
            )
        ),
        fromPrefetch = reqIsPrefetch
    )
    assert(
        !(io.tasks.mpTask.fire && mpTask_refill.valid && mpTask_refill.bits.opcode === AccessAckData && mpTask_refill.bits.updateDir && !dirResp.hit && mpTask_refill.bits.newMetaEntry.clientsOH.orR),
        "clientsOH should be 0 when updateDir for AccessAckData, clientsOH:%b, dirResp.hit:%d",
        mpTask_refill.bits.newMetaEntry.clientsOH,
        dirResp.hit
    )

    /** Send CopyBack task to [[MainPipe]], including: WriteBackFull */
    mpTask_wbdata.valid            := !state.s_cbwrdata && state.s_snpresp && state.w_compdbid
    mpTask_wbdata.bits.txnID       := rspDBID
    mpTask_wbdata.bits.isCHIOpcode := true.B
    mpTask_wbdata.bits.opcode      := CopyBackWrData
    mpTask_wbdata.bits.channel     := CHIChannel.TXDAT
    mpTask_wbdata.bits.tag         := meta.tag
    mpTask_wbdata.bits.readTempDs  := false.B
    mpTask_wbdata.bits.updateDir   := false.B
    mpTask_wbdata.bits.resp := Resp.setPassDirty( // In CopyBackWrData, resp indicates the cacheline state before issuing the WriteBackFull transaction.
        MuxLookup(meta.state, Resp.I)(
            Seq(
                MixedState.BC  -> Resp.SC,
                MixedState.BD  -> Resp.SD_PD,
                MixedState.TC  -> Resp.UC,
                MixedState.TTC -> Resp.UC,
                MixedState.TD  -> Resp.UD_PD,
                MixedState.TTD -> Resp.UD_PD
            )
        ),
        (replGotDirty && !meta.state.isDirty || meta.state.isDirty) && !meta.isInvalid
    )
    // CopyBackWrData can be in other state excpept Tip Dirty due to snoop nested.
    // assert(!(mpTask_wbdata.fire && meta.isBranch), "CopyBackWrData is only for Tip Dirty")
    assert(!(mpTask_wbdata.fire && mpTask_wbdata.bits.resp === Resp.I_PD), "CopyBackWrData should not be sent with the resp filed value of I_PD")

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
    val snpTxnID          = Mux(isRealloc, reallocTxnID, req.txnID)
    val snpOpcode         = Mux(isRealloc, reallocOpcode, req.opcode)
    val snpRetToSrc       = Mux(isRealloc, reallocRetToSrc, req.retToSrc)
    val isSnpOnceX        = CHIOpcodeSNP.isSnpOnceX(snpOpcode)
    val isSnpMakeInvalidX = CHIOpcodeSNP.isSnpMakeInvalidX(snpOpcode)
    val isSnpToB          = CHIOpcodeSNP.isSnpToB(snpOpcode)
    val snprespPassDirty  = !isSnpOnceX && !isSnpMakeInvalidX && (meta.isDirty || gotDirty) || isRealloc && snpGotDirty
    val snprespFinalDirty = isSnpOnceX && meta.isDirty
    val snprespFinalState = Mux(isSnpOnceX, meta.rawState, Mux(isSnpToB, BRANCH, INVALID))
    val snprespNeedData   = Mux(isRealloc, snpGotDirty, gotDirty || probeGotDirty || snpRetToSrc || meta.isDirty || gotCompData /* gotCompData is for SnpHitReq and need mshr realloc */ ) && !isSnpMakeInvalidX
    val hasValidProbeAck  = VecInit(probeAckParams.zip(meta.clientsOH.asBools).map { case (probeAck, en) => en && probeAck =/= NtoN }).asUInt.orR
    mpTask_snpresp.valid            := !state.s_snpresp && state.w_sprobeack
    mpTask_snpresp.bits.txnID       := snpTxnID
    mpTask_snpresp.bits.isCHIOpcode := true.B
    mpTask_snpresp.bits.opcode      := Mux(snprespNeedData, SnpRespData, SnpResp)
    mpTask_snpresp.bits.resp        := stateToResp(snprespFinalState, snprespFinalDirty, snprespPassDirty) // In SnpResp*, resp indicates the final cacheline state after receiving the Snp* transaction.
    mpTask_snpresp.bits.channel     := Mux(snprespNeedData, CHIChannel.TXDAT, CHIChannel.TXRSP)
    mpTask_snpresp.bits.readTempDs  := snprespNeedData && !releaseGotDirty
    mpTask_snpresp.bits.tempDsDest  := Mux(dirResp.hit && needProbe && probeGotDirty, DataDestination.TXDAT | DataDestination.DataStorage, DataDestination.TXDAT)
    mpTask_snpresp.bits.updateDir   := hasValidProbeAck && !isRealloc                                      // Update directory info when then received ProbeAck params are not all NtoN.
    mpTask_snpresp.bits.newMetaEntry := DirectoryMetaEntryNoTag(
        dirty = snprespFinalDirty,
        state = snprespFinalState,
        alias = req.aliasOpt.getOrElse(0.U),
        clientsOH = Mux(isSnpToB || isSnpOnceX, meta.clientsOH, Fill(nrClients, false.B)),
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

        assert(PopCount(Cat(mpTask_refill.valid, mpTask_wbdata.valid, mpTask_repl.valid, mpTask_snpresp.valid)) <= 1.U, "only one mpTask can fire at a time")
    }

    /** mpTask needs to be retried due to insufficent resources  */
    when(io.retryTasks.stage2.fire) {
        when(io.retryTasks.stage2.bits.isRetry_s2) {
            when(io.retryTasks.stage2.bits.accessack_s2) {
                state.s_accessack := false.B
                assert(state.s_accessack, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage2.bits.grant_s2) {
                state.s_grant := false.B
                assert(state.s_grant, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage2.bits.cbwrdata_s2) {
                state.s_cbwrdata := false.B
                assert(state.s_cbwrdata, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage2.bits.snpresp_s2) {
                state.s_snpresp := false.B
                assert(state.s_snpresp, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
        }.otherwise {
            when(io.retryTasks.stage2.bits.accessack_s2) {
                state.w_accessack_sent := true.B
                assert(!state.w_accessack_sent)
            }
            when(io.retryTasks.stage2.bits.grant_s2) {
                state.w_grant_sent := true.B
                assert(!state.w_grant_sent)
            }
            when(io.retryTasks.stage2.bits.cbwrdata_s2) {
                state.w_cbwrdata_sent := true.B
                assert(!state.w_cbwrdata_sent)
            }
            when(io.retryTasks.stage2.bits.snpresp_s2) {
                state.w_snpresp_sent := true.B
                assert(!state.w_snpresp_sent)
            }
        }
    }
    when(io.retryTasks.stage4.fire) {
        when(io.retryTasks.stage4.bits.isRetry_s4) {
            when(io.retryTasks.stage4.bits.grant_s4) {
                state.s_grant := false.B
                assert(state.s_grant, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage4.bits.accessack_s4) {
                state.s_accessack := false.B
                assert(state.s_accessack, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage4.bits.cbwrdata_s4) {
                state.s_cbwrdata := false.B
                assert(state.s_cbwrdata, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
            when(io.retryTasks.stage4.bits.snpresp_s4) {
                state.s_snpresp := false.B
                assert(state.s_snpresp, "try to retry an already activated task!")
                assert(valid, "retry on an invalid mshr!")
            }
        }.otherwise {
            when(io.retryTasks.stage4.bits.accessack_s4) {
                state.w_accessack_sent := true.B
                assert(!state.w_accessack_sent)
            }
            when(io.retryTasks.stage4.bits.grant_s4) {
                state.w_grant_sent := true.B
                assert(!state.w_grant_sent)
            }
            when(io.retryTasks.stage4.bits.cbwrdata_s4) {
                state.w_cbwrdata_sent := true.B
                assert(!state.w_cbwrdata_sent)
            }
            when(io.retryTasks.stage4.bits.snpresp_s4) {
                state.w_snpresp_sent := true.B
                assert(!state.w_snpresp_sent)
            }
        }
    }
    val retryVec_s2 = VecInit(io.retryTasks.stage2.bits.elements.map(_._2).toSeq).asUInt & io.retryTasks.stage2.fire
    assert(!(PopCount(retryVec_s2) > 1.U), "only allow one retry task at stage2! retryVec_s2:0b%b", retryVec_s2)

    /** Receive [[RXDAT]] responses, including: CompData */
    val rxdat = io.resps.rxdat
    when(rxdat.fire) {
        when(rxdat.bits.last) {
            state.w_compdat := true.B

            when(rxdat.bits.chiOpcode === CompData) {
                gotCompData := true.B

                gotT     := rxdat.bits.resp === Resp.UC || rxdat.bits.resp === Resp.UC_PD
                gotDirty := rxdat.bits.resp === Resp.UC_PD
                datDBID  := rxdat.bits.dbID
            }
        }.otherwise {
            state.w_compdat_first := true.B
        }
    }
    assert(!(rxdat.fire && state.w_compdat), s"mshr is not watting for rxdat")

    /** Receive [[RXRSP]] response, including: Comp */
    val rxrsp = io.resps.rxrsp
    when(rxrsp.fire && rxrsp.bits.last) {
        rspDBID := rxrsp.bits.dbID

        val opcode = rxrsp.bits.chiOpcode
        when(opcode === Comp) {
            state.w_comp       := true.B
            state.w_evict_comp := true.B
        }.elsewhen(opcode === CompDBIDResp) {
            state.w_compdbid := true.B
        }
    }
    assert(!(rxrsp.fire && state.w_comp && state.w_evict_comp && state.w_compdbid), s"mshr is not watting for rxrsp")

    /**
     * A [[MSHR]] will wait GrantAck for Acquire requests.
     * If mshr not receive a GrantAck, it cannot be freed. 
     * This allow external logic to check the mshr to determine whether the Grant/GrantData has been received by upstream cache.
     * If we ignore this response, then we have to add some logic in [[SourceD]], so that we could do the same thing as we implement here.
     */
    val sinke = io.resps.sinke
    when(sinke.fire && state.s_grant && !io.alloc_s3.fire && valid) {
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

        when(!nestedRelease) {
            assert(
                (probeAckClient & probeClients).orR,
                "probeAckClient: 0b%b is not required, required probeClients: 0b%b, meta.clientsOH: 0b%b, reqClientOH: 0b%b",
                probeAckClient,
                probeClients,
                meta.clientsOH,
                reqClientOH
            )
        }

        when(sinkc.bits.opcode === ProbeAck) {
            state.w_aprobeack_first := true.B
            state.w_aprobeack       := state.w_aprobeack | probeFinish | (nextProbeAckClients === probeClients)

            state.w_rprobeack_first := true.B
            state.w_rprobeack       := state.w_rprobeack | probeFinish | (nextProbeAckClients === probeClients)

            state.w_sprobeack_first := true.B
            state.w_sprobeack       := state.w_sprobeack | probeFinish | (nextProbeAckClients === probeClients)

            when(!state.s_evict && releaseGotDirty) {
                state.s_evict      := true.B
                state.w_evict_comp := true.B

                state.s_wb            := false.B
                state.w_compdbid      := false.B
                state.s_cbwrdata      := false.B
                state.w_cbwrdata_sent := false.B
            }
        }.elsewhen(sinkc.bits.opcode === ProbeAckData) {
            val probeAckIsDirty = sinkc.bits.param === TtoN || sinkc.bits.param === TtoB
            when(probeAckIsDirty) { // for now we think ProbeAckData.BtoN is not a diry data
                when(!state.w_rprobeack) {
                    replGotDirty := true.B
                }.otherwise {
                    gotDirty := true.B // rprobeack is NOT the same cacheline as the request cacheline
                }

                probeGotDirty := true.B
            }

            state.w_aprobeack_first := true.B
            state.w_aprobeack       := state.w_aprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            state.w_rprobeack_first := true.B
            state.w_rprobeack       := state.w_rprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            state.w_sprobeack_first := true.B
            state.w_sprobeack       := state.w_sprobeack | ((probeFinish | nextProbeAckClients === probeClients) && sinkc.bits.last)

            when(!state.s_evict && probeAckIsDirty) {
                state.s_evict      := true.B
                state.w_evict_comp := true.B

                state.s_wb            := false.B
                state.w_compdbid      := false.B
                state.s_cbwrdata      := false.B
                state.w_cbwrdata_sent := false.B
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

    /** 
     * Receive replacement response(from [[Directory]]).
     * Update directory meta/Setup replacement action(Evict/WriteBackFull) according to the response.
     */
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
                    state.s_wb            := false.B // Send WriteBackFull is it is a dirty way
                    state.w_compdbid      := false.B // Wait CompDBIDResp
                    state.s_cbwrdata      := false.B // Send CopyBackWrData
                    state.w_cbwrdata_sent := false.B
                    needWb                := true.B
                }.otherwise {
                    state.s_evict      := false.B // Send Evict if it is not a dirty way
                    state.w_evict_comp := false.B // Wait Comp
                    needWb             := true.B
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
            state.s_repl := false.B
            // state.s_accessack := !reqIsGet
            // state.s_grant     := !reqIsAcquire
            dirResp.wayOH := replResp.bits.wayOH
        }
    }
    assert(!(replResp.fire && state.w_replResp), s"mshr is not watting for replResp_s3")

    /**
     * Deal with snoop nested and release nested.
     * We should also update meta and other flags according to the nested signals from MainPipe. 
     * Some nested conditions:
     *  (1) If a WriteBackFull/Evict is nested my downstream Snoop(sam address), the [[MainPipe]] should ack the Snoop first and notify the nested [[MSHR]] at Stage 3 to update the directory meta.
     *  (2) ...
     */
    val nestedMatch    = valid && !meta.isInvalid && req.set === io.nested.set && meta.tag === io.nested.tag && state.w_replResp && !(io.nested.isMshr && io.nested.mshrId === io.id)
    val nedtedHitMatch = valid && dirResp.hit && req.set === io.nested.set && meta.tag === io.nested.tag && !(io.nested.isMshr && io.nested.mshrId === io.id)
    when(nestedMatch) {
        val nested = io.nested.snoop
        when(nested.cleanDirty) {
            // gotCompData := false.B // gotCompData is for reqAddr not for metaAddr
            meta.state := MixedState.cleanDirty(meta.state)
        }

        when(nested.toB) {
            meta.state := Mux(meta.isInvalid, MixedState.I, MixedState.BC)
        }

        when(nested.toN) {
            meta.state := MixedState.I
        }

        when(nested.toN) {

            /** If WriteBackFull/Evict is not fired, snoop will cancel the WriteBackFull/Evict under certain conditions. */
            when(!state.s_wb) {
                willCancelWb          := true.B
                state.s_wb            := true.B
                state.w_compdbid      := true.B
                state.s_cbwrdata      := true.B
                state.w_cbwrdata_sent := true.B
                needWb                := false.B
                assert(mayCancelWb)
                // assert(false.B, "TODO: Check! Snoop.toN cancel WriteBackFull") // Already checked
            }

            when(!state.s_evict) {
                willCancelEvict    := true.B
                state.s_evict      := true.B
                state.w_evict_comp := true.B
                needWb             := true.B
                assert(mayCancelEvict)
                // assert(false.B, "TODO: Check! Snoop.toN cancel Evict") // Already checked
            }

            /** Snoop is permitted to cancel the unfired Probe under certain conditions. */
            when(!state.s_aprobe) {
                state.s_aprobe          := true.B
                state.w_aprobeack       := true.B
                state.w_aprobeack_first := true.B
                assert(false.B, "TODO: Check! Snoop.toN cancel sProbe")
            }

            when(!state.s_sprobe) {
                state.s_sprobe          := true.B
                state.w_sprobeack       := true.B
                state.w_sprobeack_first := true.B
                assert(false.B, "TODO: Check! Snoop.toN cancel aProbe")
            }

            when(!state.s_rprobe) {
                state.s_rprobe          := true.B
                state.w_rprobeack       := true.B
                state.w_rprobeack_first := true.B
                assert(false.B, "TODO: Check! Snoop.toN cancel rProbe")
            }
        }

        when(nested.toB) {

            /** Snoop is permitted to cancel the unfired Probe under certain conditions. */
            // TODO:
        }

        assert(!(nested.toB && nested.toN), s"nested toB and toN at the same time")

        /**
         * from IHI0050G: P272
         * While a Snoop transaction response is pending, the only transaction responses that are permitted to be sent to the same address are:
         *  - RetryAck for a CopyBack
         *  - RetryAck and DBIDResp for a WriteUnique and Atomics
         *  - RetryAck and, if applicable, a ReadReceipt for a Read request type
         *  - RetryAck for a Dataless request type
         * Once a completion is sent for a transaction, the HN-F must not send a Snoop request to the same cache line until receiving:
         *  - A CompAck for any Read and Dataless requests except for ReadOnce* and ReadNoSnp
         *  - A CompAck response for a CopyBack request that the HN-F has requested completes without a data transfer
         *  - A CopyBackWriteData response for a CopyBack request that the HN-F has requested completes with a data transfer
         *  - A NonCopyBackWriteData response for an Atomic request
         *  - A WriteData response, and if applicable, CompAck, for a WriteUnique request where the HN-F has not used a DWT flow
         *  - A Comp response from the Subordinate Node for a WriteUnique request where the HN-F has used a DWT flow
         */
        // val hasNestedSnoop = VecInit(io.nested.snoop.elements.map(_._2).toSeq).asUInt.orR
        // assert(
        //     (!state.w_compdbid || !state.w_evict_comp || !state.s_grant || !state.s_accessack || io.status.willFree) && hasNestedSnoop || !hasNestedSnoop || req.channel === L2Channel.ChannelB,
        //     "w_compdbid:%d, w_evict_comp:%d, s_grant:%d, s_accessack:%d",
        //     state.w_compdbid,
        //     state.w_evict_comp,
        //     state.s_grant,
        //     state.s_accessack
        // )
    }

    val respMapCancel   = RegInit(false.B)
    val willCancelProbe = WireInit(false.B)
    val cancelProbe     = RegNext(willCancelProbe)
    cancelProbe_dup := cancelProbe
    when(nestedMatch) {
        val nested = io.nested.release

        when(nested.setDirty) {
            when(nedtedHitMatch) {
                releaseGotDirty := true.B
            }.otherwise {
                replGotDirty := true.B
            }

            when(!state.s_evict) {
                state.s_evict         := true.B
                state.w_evict_comp    := true.B
                state.s_wb            := false.B
                state.w_compdbid      := false.B
                state.s_cbwrdata      := false.B
                state.w_cbwrdata_sent := false.B
                assert(mayChangeEvict)
            }
        }

        when(nested.TtoN) {
            val releaseClientOH = getClientBitOH(io.nested.source)
            val noProbeRequired = !(probeClients & ~releaseClientOH).orR
            meta.clientsOH  := meta.clientsOH & ~releaseClientOH
            probeAckClients := probeAckClients & ~releaseClientOH
            nestedRelease   := true.B
            willCancelProbe := noProbeRequired
        }
    }

    /** Any unfired probe transactions will be canceled. */
    when(cancelProbe /* Optimize for better timing */ && !io.tasks.sourceb.fire) {
        when(!state.s_sprobe) {
            state.s_sprobe          := true.B
            state.w_sprobeack       := true.B
            state.w_sprobeack_first := true.B
            respMapCancel           := true.B
        }

        when(!state.s_aprobe) {
            state.s_aprobe          := true.B
            state.w_aprobeack       := true.B
            state.w_aprobeack_first := true.B
            respMapCancel           := true.B
        }

        when(!state.s_rprobe) {
            state.s_rprobe          := true.B
            state.w_rprobeack       := true.B
            state.w_rprobeack_first := true.B
            respMapCancel           := true.B
        }
    }

    /**
     * [[MSHR]] is permitted to cancel the unfired probe, hence the corresponding respDestMap(at [[SinkC]]) entry should be freed as well. 
     */
    when(io.alloc_s3.fire) {
        respMapCancel := false.B
    }.elsewhen(io.respMapCancel.fire) {
        respMapCancel := false.B
    }.elsewhen(RegNext(io.resps.sinkc.fire && io.resps.sinkc.bits.last, false.B) && probeFinish) {
        respMapCancel := true.B
    }
    io.respMapCancel.valid := respMapCancel || io.status.valid && io.status.willFree
    io.respMapCancel.bits  := io.id
    assert(!(io.respMapCancel.fire && !valid))

    /** 
     * Read reissue:
     *  If the [[MSHR]] is nested by the same address(request address) Snoop on certain conditions([[MSHR]] already gets Comp/CompData from downstream cache), 
     *  the read reissue is required beacuse the data will be snooped back.
     * Read resissue is work with reallocation which is triggered by the Snoop that cannot be handled by the TXDAT at stage 2.
     */
    val snoopMatchReqAddr = valid && !io.status.willFree && io.nested.set === req.set && io.nested.tag === req.tag && state.w_replResp && !io.nested.isMshr
    when(snoopMatchReqAddr) {
        val nested = io.nested.snoop

        when(nested.toN) {
            when(needRead && state.s_compack) {
                state.s_read          := false.B
                state.w_compdat       := false.B
                state.w_compdat_first := false.B
                state.s_compack       := false.B
                assert(state.s_read)
            }

            when(needPromote && state.w_comp && state.s_compack) {
                state.s_makeunique := false.B
                state.w_comp       := false.B
                state.s_compack    := false.B
                assert(state.s_makeunique)
            }
        }

        when(nested.toB) {
            when(needRead && state.s_compack) {
                when(reqNeedT) {
                    state.s_read          := false.B
                    state.w_compdat       := false.B
                    state.w_compdat_first := false.B
                    state.s_compack       := false.B
                    assert(state.s_read)
                }
                assert(state.s_read)
            }

            when(needPromote && state.w_comp && state.s_compack) {
                state.s_makeunique := false.B
                state.w_comp       := false.B
                state.s_compack    := false.B
                assert(state.s_makeunique)
            }
            // TODO: not verified
            // assert(false.B, "TODO:")
        }
    }

    /**
     * Check if there is any request in the [[MSHR]] waiting for responses or waiting for sehcduling tasks.
     * If there is, then we cannot free the [[MSHR]].
     */
    val noWait     = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("w_")) => signal }.toSeq).asUInt.andR
    val noSchedule = VecInit(state.elements.collect { case (name, signal) if (name.startsWith("s_")) => signal }.toSeq).asUInt.andR
    val willFree   = noWait && noSchedule
    when(valid && willFree && io.respMapCancel.ready) {
        valid := false.B
    }

    val evictNotSent     = !state.s_evict
    val wbNotSent        = !state.s_wb
    val getValidReplResp = io.replResp_s3.fire && !io.replResp_s3.bits.retry && valid
    io.status.valid     := valid
    io.status.willFree  := willFree
    io.status.set       := req.set
    io.status.reqTag    := req.tag
    io.status.metaTag   := dirResp.meta.tag
    io.status.needsRepl := evictNotSent || wbNotSent // Used by MissHandler to guide the ProbeAck/ProbeAckData response to the match the correct MSHR
    io.status.wayOH     := dirResp.wayOH
    io.status.dirHit    := dirResp.hit               // Used by Directory to occupy a particular way.
    io.status.state     := meta.state
    io.status.lockWay := !dirResp.hit && meta.isInvalid && !dirResp.needsRepl || !dirResp.hit && state.w_replResp && (!state.s_grant || !state.w_grant_sent || !state.w_grantack || !state.s_accessack || !state.w_accessack_sent) // Lock the CacheLine way that will be used in later Evict or WriteBackFull

    io.status.w_replResp   := state.w_replResp
    io.status.w_rprobeack  := state.w_rprobeack
    io.status.w_evict_comp := state.w_evict_comp
    io.status.w_compdbid   := state.w_compdbid
    io.status.w_comp_first := state.w_compdat_first && state.w_comp

    io.status.waitProbeAck := !state.w_rprobeack || !state.w_aprobeack || !state.w_sprobeack
    io.status.waitGrantAck := state.s_grant && state.w_grant_sent && !state.w_grantack
    io.status.replGotDirty := replGotDirty
    io.status.isChannelA   := req.isChannelA

    // Allow Snoop nested request address. This operation will cause ReadReissue
    val gotReplProbeAck  = state.s_rprobe && state.w_rprobeack
    val gotWbResp        = state.w_compdbid && state.w_evict_comp
    val hasPendingRefill = !state.s_grant && !state.w_grant_sent || !state.s_accessack && !state.w_accessack_sent
    val gotCompResp      = state.w_comp && state.w_compdat_first
    val isAcquireHit     = dirResp.hit && req.isChannelA
    io.status.reqAllowSnoop := {
        // If reqAllowSnoop is true and the MSHR already got refill from downstream, a incoming Snoop may cause ReadReissue.
        state.w_replResp &&
        !(isAcquireHit && gotCompResp) && // If the request is a hit and needT/needB Acquire, we should block the same address Snoop to avoid doing extra Probe for some Snoop(e.g. SnpUnique).
        hasPendingRefill &&               // Does not grant to upstream cache
        gotReplProbeAck &&                // Already got replace ProbeAck(After that we could determine whether to use Evict or WriteBackFull)
        !(needWb && gotWbResp) &&         // Does not get write back resp from downstream cache(the write back may be stalled by the same address Snoop)
        !io.status.waitProbeAck           // Does not wait for any ProbeAck
    }
    io.status.gotDirtyData := gotCompData || probeGotDirty || dirResp.hit && dirResp.meta.isDirty || releaseGotDirty

    val addr_reqTag_debug  = Cat(io.status.reqTag, io.status.set, 0.U(6.W))
    val addr_metaTag_debug = Cat(io.status.metaTag, io.status.set, 0.U(6.W))
    dontTouch(addr_reqTag_debug)
    dontTouch(addr_metaTag_debug)

    LeakChecker.withCallback(io.status.valid, !io.status.valid, Some(s"mshr_valid"), maxCount = deadlockThreshold) {
        this.state.elements.foreach { case (name, data) =>
            printf(cf"state.$name: $data\n")
        }
        this.io.status.elements.foreach { case (name, data) =>
            printf(cf"io.status.$name: $data%x\n")
        }
        printf(cf"addr_reqTag:${addr_reqTag_debug}%x\n")
        printf(cf"addr_metaTag:${addr_metaTag_debug}%x\n")
        printf(cf"channel:${req.channel}\n")
        printf(cf"isCHIOpcode:${req.isCHIOpcode}\n")
        printf(cf"opcode:${req.opcode}\n")
    }
}

object MSHR extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MSHR()(config), name = "MSHR", split = false)
}
