package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.{GenerateVerilog, MultiDontTouch}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._

// TODO: Replay

class MainPipe()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {

        /** Stage 2 */
        val mpReq_s2   = Flipped(ValidIO(new TaskBundle))
        val sourceD_s2 = Decoupled(new TaskBundle)

        /** Stage 3 */
        val dirResp_s3    = Flipped(ValidIO(new DirResp))
        val dirWrite_s3   = ValidIO(new DirWrite)
        val mshrAlloc_s3  = DecoupledIO(new MshrAllocBundle)
        val mshrFreeOH_s3 = Input(UInt(nrMSHR.W))
        val dsRead_s3     = ValidIO(new DSRead)
        val dsWrWay_s3    = Output(UInt(wayBits.W))
        val txrsp_s3      = DecoupledIO(new CHIBundleREQ(chiBundleParams)) // Snp* hit and does not require data will be sent to txrsp_s3

        /** Stage 4 */
        val replay_s4  = DecoupledIO(new TaskBundle)
        val sourceD_s4 = DecoupledIO(new TaskBundle)

        val dsRdCrd = Input(Bool()) // TODO:
    })

    io <> DontCare

    val dsRdCnt = RegInit(0.U(log2Ceil(rdQueueEntries + 1).W))
    when(io.dsRdCrd && !io.dsRead_s3.valid) {
        dsRdCnt := dsRdCnt + 1.U
    }.elsewhen(!io.dsRdCrd && io.dsRead_s3.valid) {
        dsRdCnt := dsRdCnt - 1.U
        assert(dsRdCnt =/= 0.U)
    }

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val task_s2  = WireInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2 = WireInit(false.B)

    valid_s2 := io.mpReq_s2.valid
    task_s2  := io.mpReq_s2.bits

    val isSourceD_s2 = task_s2.opcode === Grant || task_s2.opcode === GrantData || task_s2.opcode === AccessAckData || task_s2.opcode === AccessAck || task_s2.opcode === ReleaseAck
    io.sourceD_s2.valid := valid_s2 && task_s2.isMshrTask && isSourceD_s2
    io.sourceD_s2.bits  <> task_s2

    assert(!(io.sourceD_s2.valid && !io.sourceD_s2.ready), "sourceD_s2 should always be ready")

    // -----------------------------------------------------------------------------------------
    // Stage 3
    // -----------------------------------------------------------------------------------------
    val dirResp_s3 = io.dirResp_s3.bits
    val task_s3    = RegEnable(task_s2, valid_s2)
    val valid_s3   = RegNext(valid_s2, false.B)
    val hit_s3     = dirResp_s3.hit
    val meta_s3    = dirResp_s3.meta
    val state_s3   = meta_s3.state
    assert(!(valid_s3 && !io.dirResp_s3.fire), "Directory response should be valid!")

    io.dsWrWay_s3 := OHToUInt(dirResp_s3.wayOH)

    val reqNeedT_s3           = needT(task_s3.opcode, task_s3.param)
    val reqClientOH_s3        = getClientBitOH(task_s3.source)
    val isReqClient_s3        = (reqClientOH_s3 & meta_s3.clientsOH).orR // whether the request is from the same client
    val sinkRespPromoteT_a_s3 = hit_s3 && meta_s3.isTip

    val isGet_s3          = task_s3.opcode === Get && task_s3.isChannelA
    val isAcquireBlock_s3 = task_s3.opcode === AcquireBlock && task_s3.isChannelA
    val isAcquirePerm_s3  = task_s3.opcode === AcquirePerm && task_s3.isChannelA
    val isAcquire_s3      = isAcquireBlock_s3 || isAcquirePerm_s3
    val isPrefetch_s3     = task_s3.opcode === Hint && task_s3.isChannelA                                                                   // TODO: Prefetch
    val cacheAlias_s3     = isAcquire_s3 && hit_s3 && isReqClient_s3 && meta_s3.aliasOpt.getOrElse(0.U) =/= task_s3.aliasOpt.getOrElse(0.U) // TODO: Cache Alias
    val isRelease_s3      = (task_s3.opcode === Release || task_s3.opcode === ReleaseData) && task_s3.isChannelC

    val needReadOnMiss_a_s3   = isGet_s3 || isAcquire_s3 || isPrefetch_s3
    val needReadOnHit_a_s3    = !isPrefetch_s3 && reqNeedT_s3 && meta_s3.isBranch // send MakeUnique
    val needReadDownward_a_s3 = task_s3.isChannelA && (hit_s3 && needReadOnHit_a_s3 || !hit_s3 && needReadOnMiss_a_s3)
    val needProbeOnHit_a_s3 = if (nrClients > 1) {
        isGet_s3 && hit_s3 && meta_s3.isTrunk ||
        isAcquire_s3 && hit_s3 && Mux(
            reqNeedT_s3,

            /** Acquire.NtoT / Acquire.BtoT */
            meta_s3.isTrunk || meta_s3.isTip && PopCount(meta_s3.clientsOH) > 1.U,

            /** Acquire.NtoB */
            meta_s3.isTrunk
        )
    } else {
        isGet_s3 && hit_s3 && meta_s3.isTrunk
    }
    val needProbeOnMiss_a_s3 = task_s3.isChannelA && !hit_s3 && meta_s3.clientsOH.orR
    val needProbe_a_s3       = task_s3.isChannelA && (hit_s3 && needProbeOnHit_a_s3 || !hit_s3 && needProbeOnMiss_a_s3)

    // val isSnpUnique_s3 = task_s3.isSnoop && task_s3.chiOpcode ===
    val needProbe_b_s3 = hit_s3 && false.B // TODO: Snoop
    // TODO: Snoop miss did not need mshr, response with SnpResp_I

    val mshrAlloc_a_s3 = needReadDownward_a_s3 || needProbe_a_s3 || cacheAlias_s3
    val mshrAlloc_b_s3 = needProbe_b_s3 // TODO: Snoop
    val mshrAlloc_c_s3 = false.B        // for inclusive cache, Release/ReleaseData always hit
    val mshrAlloc_s3   = (mshrAlloc_a_s3 || mshrAlloc_b_s3 || mshrAlloc_c_s3) && valid_s3

    val mshrAllocStates = WireInit(0.U.asTypeOf(new MshrFsmState))
    mshrAllocStates.elements.foreach(_._2 := true.B)
    when(task_s3.isChannelA) {
        when(isGet_s3) {
            mshrAllocStates.s_accessack := false.B
        }

        when(isAcquire_s3) {
            mshrAllocStates.s_grant    := false.B
            mshrAllocStates.w_grantack := false.B
        }

        when(needReadDownward_a_s3) {
            when(needReadOnHit_a_s3) {
                mshrAllocStates.s_makeunique := false.B
                mshrAllocStates.w_comp       := false.B
            }

            when(needReadOnMiss_a_s3) {
                mshrAllocStates.s_read    := false.B
                mshrAllocStates.w_compdat := false.B
                mshrAllocStates.s_compack := false.B
            }
        }

        when(cacheAlias_s3 || needProbeOnHit_a_s3) {
            mshrAllocStates.s_probe    := false.B
            mshrAllocStates.w_probeack := false.B
        }

        when(needProbeOnMiss_a_s3) {
            mshrAllocStates.s_rprobe    := false.B
            mshrAllocStates.w_rprobeack := false.B
        }
    }.elsewhen(task_s3.isChannelB) {
        mshrAllocStates.s_snpresp := false.B

        when(needProbe_b_s3) {
            mshrAllocStates.s_pprobe    := false.B
            mshrAllocStates.w_pprobeack := false.B
        }
    }.elsewhen(task_s3.isChannelC) {
        // TODO: Release should always hit
    }

    io.mshrAlloc_s3.valid                := mshrAlloc_s3
    io.mshrAlloc_s3.bits.dirResp         := dirResp_s3
    io.mshrAlloc_s3.bits.fsmState        := mshrAllocStates
    io.mshrAlloc_s3.bits.req             := task_s3
    io.mshrAlloc_s3.bits.req.isAliasTask := cacheAlias_s3

    /** coherency check */
    when(valid_s3) {
        assert(!(dirResp_s3.hit && state_s3 === MixedState.I), "Hit on INVALID state!")
        assert(!(meta_s3.isTrunk && !dirResp_s3.meta.clientsOH.orR), "Trunk should have clientsOH!")
        assert(!(meta_s3.isTrunk && PopCount(dirResp_s3.meta.clientsOH) > 1.U), "Trunk should have only one client!")

        assert(!(task_s3.isChannelA && task_s3.param === BtoT && isAcquire_s3 && !hit_s3), "Acquire.BtoT should always hit!")
        assert(!(task_s3.isChannelA && task_s3.param === BtoT && hit_s3 && !isReqClient_s3), "Acquire.BtoT should have clientsOH!")

        assert(!(task_s3.isChannelC && isRelease_s3 && !dirResp_s3.hit), "Release/ReleaseData should always hit! addr => TODO: ")
        assert(!(isRelease_s3 && task_s3.param === TtoB && task_s3.opcode =/= Release), "TtoB can only be used in Release") // TODO: ReleaseData.TtoB
        assert(!(isRelease_s3 && task_s3.param === NtoN), "Unsupported Release.NtoN")
    }

    /** 
      * Get/Prefetch is not required to writeback [[Directory]].
      *     => Get is a TL-UL message which will not modify the coherent state
      *     => Prefetch only change the L2 state and not response upwards to L1
      */
    val dirWen_mshr_s3 = task_s3.isMshrTask && task_s3.updateDir
    val dirWen_a_s3    = task_s3.isChannelA && !mshrAlloc_s3 && !isGet_s3 && !isPrefetch_s3
    val dirWen_b_s3    = false.B // TODO: Snoop
    val dirWen_c_s3    = task_s3.isChannelC && hit_s3

    val newMeta_mshr_s3 = DirectoryMetaEntry(task_s3.tag, task_s3.newMetaEntry)

    val newMeta_a_s3 = DirectoryMetaEntry(
        fromPrefetch = false.B,                                                                                                 // TODO:
        state = Mux(reqNeedT_s3 || sinkRespPromoteT_a_s3, Mux(meta_s3.isDirty, MixedState.TTD, MixedState.TTC), meta_s3.state), // TODO:
        tag = task_s3.tag,
        aliasOpt = Some(task_s3.aliasOpt.getOrElse(0.U)),
        clientsOH = reqClientOH_s3 | meta_s3.clientsOH
    )

    // val newMeta_b_s3 = DirectoryMetaEntry(
    //     fromPrefetch = false.B, // TODO:
    //     state =
    //     tag = meta_s3.tag,
    //     aliasOpt = Some(meta_s3.aliasOpt.getOrElse(0.U)),
    //     clientsOH = meta_s3.clientsOH
    // )

    val newMeta_c_s3 = DirectoryMetaEntry(
        fromPrefetch = false.B, // TODO:
        state = MuxCase(
            MixedState.I,
            Seq(
                (task_s3.param === TtoN && meta_s3.state === MixedState.TTC)                                        -> MixedState.TD, // TODO: TTC?
                (task_s3.param === TtoN && meta_s3.state === MixedState.TTD)                                        -> MixedState.TD,
                (task_s3.param === TtoB && meta_s3.state === MixedState.TTC)                                        -> MixedState.TC,
                (task_s3.param === TtoB && meta_s3.state === MixedState.TTD)                                        -> MixedState.TD,
                (task_s3.param === TtoB && meta_s3.state === MixedState.TC)                                         -> MixedState.TC,
                (task_s3.param === TtoB && meta_s3.state === MixedState.TD)                                         -> MixedState.TD,
                (task_s3.param === BtoN && meta_s3.state === MixedState.TC)                                         -> MixedState.TC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.TD)                                         -> MixedState.TD,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BBC && PopCount(meta_s3.clientsOH) > 1.U)   -> MixedState.BBC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BBC && PopCount(meta_s3.clientsOH) === 1.U) -> MixedState.BC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BBD && PopCount(meta_s3.clientsOH) > 1.U)   -> MixedState.BBD,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BBD && PopCount(meta_s3.clientsOH) === 1.U) -> MixedState.BD
            )
        ),
        tag = meta_s3.tag,
        aliasOpt = Some(meta_s3.aliasOpt.getOrElse(0.U)),
        clientsOH = Mux(task_s3.param === TtoN || task_s3.param === BtoN, meta_s3.clientsOH & ~reqClientOH_s3, meta_s3.clientsOH /* Release.TtoB */ )
    )

    val dirWen_s3 = !mshrAlloc_s3 && (dirWen_mshr_s3 || dirWen_a_s3 || dirWen_b_s3 || dirWen_c_s3) && valid_s3
    io.dirWrite_s3.valid      := dirWen_s3
    io.dirWrite_s3.bits.set   := task_s3.set
    io.dirWrite_s3.bits.wayOH := Mux(hit_s3, dirResp_s3.wayOH, task_s3.wayOH)
    io.dirWrite_s3.bits.meta := MuxCase(
        0.U.asTypeOf(new DirectoryMetaEntry),
        Seq(
            dirWen_mshr_s3 -> newMeta_mshr_s3,
            dirWen_a_s3    -> newMeta_a_s3,
            // dirWen_b_s3 -> newMeta_b_s3, // TODO:
            dirWen_c_s3 -> newMeta_c_s3 // TODO:
        )
    )

    /** read data from [[DataStorage]] */
    val dsRen_s3 = hit_s3 && (isAcquireBlock_s3 || isGet_s3) && !io.mshrAlloc_s3.valid && valid_s3
    io.dsRead_s3.bits.set  := task_s3.set
    io.dsRead_s3.bits.way  := OHToUInt(io.dirResp_s3.bits.wayOH)
    io.dsRead_s3.bits.dest := DataDestination.SourceD // TODO: DataDestination.TempDataStorage
    io.dsRead_s3.valid     := dsRen_s3

    val replayValid_s3 = io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready && valid_s3
    val respValid_s3   = !mshrAlloc_s3 && !task_s3.isMshrTask && valid_s3
    val respOpcode_s3  = WireInit(0.U(math.max(task_s3.opcode.getWidth, task_s3.chiOpcode.getWidth).W))
    respOpcode_s3 := MuxCase( // TODO:
        DontCare,
        Seq(
            isAcquireBlock_s3 -> GrantData,     // to SourceD
            isAcquirePerm_s3  -> Grant,         // to SourceD
            isGet_s3          -> AccessAckData, // to SourceD
            isRelease_s3      -> ReleaseAck     // to SourceD
            // TODO: Snoop
        )
    )
    val respParam_s3 = Mux(task_s3.isChannelA, Mux(task_s3.param === NtoB && !sinkRespPromoteT_a_s3, toB, toT), DontCare)
    val fire_s3      = replayValid_s3 || respValid_s3
    assert(!(replayValid_s3 && respValid_s3), "Only one of replayValid_s3 and respValid_s3 can be true!")

    // -----------------------------------------------------------------------------------------
    // Stage 4
    // -----------------------------------------------------------------------------------------
    val task_s4        = Reg(new TaskBundle)
    val replayValid_s4 = RegNext(replayValid_s3, false.B)
    val respValid_s4   = RegNext(respValid_s3, false.B)
    val valid_s4       = replayValid_s4 | respValid_s4
    MultiDontTouch(replayValid_s4, respValid_s4, valid_s4)

    when(fire_s3) {
        task_s4 := task_s3
        when(respValid_s3) {
            task_s4.opcode    := respOpcode_s3
            task_s4.chiOpcode := respOpcode_s3
            task_s4.param     := respParam_s3
        }
    }

    io.replay_s4.valid := replayValid_s4
    io.replay_s4.bits  := task_s4

    io.sourceD_s4.valid := respValid_s4
    io.sourceD_s4.bits  := task_s4

    assert(!(io.replay_s4.valid && !io.replay_s4.ready), "replay_s4 should always ready!")
    assert(!(io.sourceD_s4.valid && !io.sourceD_s4.ready), "sourceD_s4 should always ready!")

    dontTouch(io)
}

object MainPipe extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new MainPipe()(config), name = "MainPipe", split = false)
}
