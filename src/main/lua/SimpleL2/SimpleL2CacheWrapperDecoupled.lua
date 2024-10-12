local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local chi = require "CHI"
local verilua = verilua
local f = string.format

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeB = tl.TLOpcodeB
local TLOpcodeC = tl.TLOpcodeC
local TLOpcodeD = tl.TLOpcodeD
local MixedState = tl.MixedState

local OpcodeDAT = chi.OpcodeDAT
local OpcodeREQ = chi.OpcodeREQ
local OpcodeRSP = chi.OpcodeRSP
local OpcodeSNP = chi.OpcodeSNP
local CHIResp = chi.CHIResp

local channels = require("Channel").build_channel("dcache_in_0_0_", "l2_chi_")

local tl_a = channels.tl_a
local tl_b = channels.tl_b
local tl_c = channels.tl_c
local tl_d = channels.tl_d
local tl_e = channels.tl_e
local chi_txreq = channels.chi_txreq
local chi_txdat = channels.chi_txdat
local chi_txrsp = channels.chi_txrsp
local chi_rxsnp = channels.chi_rxsnp
local chi_rxrsp = channels.chi_rxrsp
local chi_rxdat = channels.chi_rxdat

local pf_recv_addrs_0 = ([[
    | valid
    | addr
    | pfSource
]]):bdl {name = "pf_recv_addrs_0", hier = cfg.top, prefix = "io_prefetchOpt_recv_addrs_0_"}

local pf_recv_addrs_1 = ([[
    | valid
    | addr
    | pfSource
]]):bdl {name = "pf_recv_addrs_1", hier = cfg.top, prefix = "io_prefetchOpt_recv_addrs_1_"}

local l2 = dut.u_SimpleL2CacheWrapperDecoupled.l2
local slices_0 = l2.slices_0
local slices_1 = l2.slices_1
local reqBuf_0 = slices_0.reqBuf
local reqBuf_1 = slices_1.reqBuf
local mp_0 = slices_0.mainPipe
local mp_1 = slices_1.mainPipe
local dir_0 = slices_0.dir
local dir_1 = slices_1.dir
local missHandler_0 = slices_0.missHandler
local missHandler_1 = slices_1.missHandler

local mshrs_0 = {}
for i = 0, 15 do
    mshrs_0[i] = missHandler_0["mshrs_" .. i]
end

local mshrs_1 = {}
for i = 0, 15 do
    mshrs_1[i] = missHandler_1["mshrs_" .. i]
end

local offset_bits = 6
local bank_bits = 1
local set_bits = mp_0.task_s3_set.get_width()
local tag_bits = mp_0.task_s3_tag.get_width()

local function switch_channel(core_id)
    if core_id == 0 or core_id == 1 then
        local channels = require("Channel").build_channel(f("tl_adcache_in_%d_0_", core_id), "l2_chi_")

        tl_a = channels.tl_a
        tl_b = channels.tl_b
        tl_c = channels.tl_c
        tl_d = channels.tl_d
        tl_e = channels.tl_e
        chi_txreq = channels.chi_txreq
        chi_txdat = channels.chi_txdat
        chi_txrsp = channels.chi_txrsp
        chi_rxsnp = channels.chi_rxsnp
        chi_rxrsp = channels.chi_rxrsp
        chi_rxdat = channels.chi_rxdat
    else
        assert(false, "Unknown core_id: " .. core_id)
    end
end

local function to_address(bank, set, tag)
    assert(bank == 0 or bank == 1)

    return utils.bitpat_to_hexstr({
        {
            -- bank field
            s = offset_bits,
            e = offset_bits + bank_bits,
            v = bank,
        },
        {   -- set field
            s = offset_bits + bank_bits,   
            e = offset_bits + bank_bits + set_bits - 1,
            v = set
        },
        {   -- tag field
            s = offset_bits + bank_bits + set_bits, 
            e = offset_bits + bank_bits + set_bits + tag_bits - 1, 
            v = tag
        }
    }, 64):number()
end

local function write_dir(slice_id, set, wayOH, tag, state, clientsOH, alias)
    local slice_id = assert(slice_id)
    assert(type(set) == "number")
    assert(type(wayOH) == "number" or type(wayOH) == "cdata")
    assert(type(tag) == "number")
    assert(type(state) == "number")

    local dir = dut["u_SimpleL2CacheWrapperDecoupled.l2.slices_" .. slice_id .. ".dir"]

    local clientsOH = clientsOH or ("0b00"):number()
    local alias = alias or ("0b00"):number()
    
    env.negedge()
        dir:force_all()
            dir.io_dirWrite_s3_valid:set(1)
            dir.io_dirWrite_s3_bits_set:set(set)
            dir.io_dirWrite_s3_bits_wayOH:set(wayOH)
            dir.io_dirWrite_s3_bits_meta_tag:set(tag)
            dir.io_dirWrite_s3_bits_meta_state:set(state)
            dir.io_dirWrite_s3_bits_meta_aliasOpt:set(alias)
            dir.io_dirWrite_s3_bits_meta_clientsOH:set(clientsOH)
    
    env.negedge()
        dir:release_all()
    
    env.posedge()
end

local function write_ds(slice_id, set, wayOH, data_str)
    local slice_id = assert(slice_id)
    assert(type(set) == "number")
    assert(type(wayOH) == "number" or type(wayOH) == "cdata")
    assert(type(data_str) == "string")

    local ds = dut["u_SimpleL2CacheWrapperDecoupled.l2.slices_" .. slice_id .. ".ds"]

    env.negedge()
        ds:force_all()
            ds.io_dsWrite_s2_valid:set(1)
            ds.io_dsWrite_s2_bits_set:set(set)
            ds.io_dsWrite_s2_bits_data:set_str(data_str)

    env.negedge()
        ds:release_all()
        ds:force_all()
            ds.io_fromMainPipe_dsWrWayOH_s3_valid:set(1)
            ds.io_fromMainPipe_dsWrWayOH_s3_bits:set(wayOH)
    
    env.negedge()
        ds.io_fromMainPipe_dsWrWayOH_s3_valid:set(0)
        ds:release_all()
    
    env.posedge()
end

local resetFinish_0 = slices_0.dir.io_resetFinish:chdl()
local resetFinish_1 = slices_0.dir.io_resetFinish:chdl()

local function wait_resetFinish()
    repeat
        env.negedge()
    until resetFinish_0:is(1) and resetFinish_1:is(1)
end

local function initialize_tl_ports()
    tl_d.ready:set(1)
end

local function initialize_chi_ports()
    chi_txreq.ready:set(1)
end

local function init_all()
    env.dut_reset()

    initialize_tl_ports()
    initialize_chi_ports()
    wait_resetFinish()
end

local test_chi_retry = env.register_test_case "test_chi_retry" {
    function ()
        init_all()

        local function test(retry_after_pcrd)
            local retry_after_pcrd = retry_after_pcrd or false
            print("retry_after_pcrd => " .. tostring(retry_after_pcrd))

            env.negedge()
                write_dir(0, 0x01, utils.uint_to_onehot(0), 0x01, MixedState.I, 0x00, 0)
            tl_a:acquire_block(to_address(0, 0x01, 0x01), TLParam.NtoB, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNotSharedDirty) and chi_txreq.bits.allowRetry:is(1) and chi_txreq.bits.pCrdType:is(0) end)
            
            local pcrd_type = 1
            local txn_id = 0 -- mshr_0
            local src_id = 3

            if retry_after_pcrd then
                -- chi_rxrsp:pcrd_grant(src_id, pcrd_type)
                -- chi_rxrsp:retry_ack(txn_id, src_id, pcrd_type)

                env.negedge()
                    chi_rxrsp.bits.opcode:set(OpcodeRSP.PCrdGrant)
                    chi_rxrsp.bits.txnID:set(urandom() % 16)
                    chi_rxrsp.bits.srcID:set(src_id)
                    chi_rxrsp.bits.pCrdType:set(pcrd_type)
                    chi_rxrsp.valid:set(1)
                    env.posedge()
                    chi_rxrsp.ready:expect(1)
                env.negedge()
                    chi_rxrsp.valid:set(1)
                    chi_rxrsp.bits.txnID:set(txn_id)
                    chi_rxrsp.bits.opcode:set(OpcodeRSP.RetryAck)
                    chi_rxrsp.bits.srcID:set(src_id)
                    chi_rxrsp.bits.pCrdType:set(pcrd_type)
                    env.posedge()
                    chi_rxrsp.ready:expect(1)
                env.negedge()
                    chi_rxrsp.valid:set(0)
            else
                -- chi_rxrsp:retry_ack(txn_id, src_id, pcrd_type)
                -- chi_rxrsp:pcrd_grant(src_id, pcrd_type)

                env.negedge()
                    chi_rxrsp.bits.txnID:set(txn_id)
                    chi_rxrsp.bits.opcode:set(OpcodeRSP.RetryAck)
                    chi_rxrsp.bits.srcID:set(src_id)
                    chi_rxrsp.bits.pCrdType:set(pcrd_type)
                    chi_rxrsp.valid:set(1)
                    env.posedge()
                    chi_rxrsp.ready:expect(1)
                env.negedge()
                    chi_rxrsp.valid:set(1)
                    chi_rxrsp.bits.opcode:set(OpcodeRSP.PCrdGrant)
                    chi_rxrsp.bits.txnID:set(urandom() % 16)
                    chi_rxrsp.bits.srcID:set(src_id)
                    chi_rxrsp.bits.pCrdType:set(pcrd_type)
                    env.posedge()
                    chi_rxrsp.ready:expect(0) -- optParam.rxrspHasLatch == true
                env.negedge()
                    env.posedge()
                    chi_rxrsp.ready:expect(1)
                env.negedge()
                    chi_rxrsp.valid:set(0)
            end

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNotSharedDirty) and chi_txreq.bits.allowRetry:is(0) and chi_txreq.bits.pCrdType:is(pcrd_type) end)
            chi_txreq:dump()

            env.negedge()
            env.expect_not_happen_until(10, function () return chi_txreq:fire() end)

            if retry_after_pcrd then
                l2.retryHelper.pendingPCrdGrant_0_valid:expect(0)
            end

            chi_rxdat:compdat(txn_id, "0xaabb", "0xccdd", 0, CHIResp.SC)
            env.expect_happen_until(10, function () return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xaabb and tl_d.bits.param:is(TLParam.toB) end)
            env.expect_happen_until(10, function () return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xccdd and tl_d.bits.param:is(TLParam.toB) end)
            local sink = tl_d.bits.sink:get()
            tl_e:grantack(sink)
            env.negedge(10)
            mshrs_0[0].io_status_valid:expect(0)
        end

        test(true)
        test(false)
    end
}

local test_prefetch_recv_req = env.register_test_case "test_prefetch_recv_req" {
    function ()
        init_all()

        local recv_addr = 0x1000
        env.negedge()
            pf_recv_addrs_0.valid:set(1)
            pf_recv_addrs_0.bits.addr:set(recv_addr, true)
            pf_recv_addrs_0.bits.pfSource:set(10) -- MemReqSource.Prefetch2L2SMS
        env.negedge()
            pf_recv_addrs_0.valid:set(0)

        env.expect_happen_until(10, function () return slices_0.io_prefetchReqOpt_valid:is(1) and slices_0.io_prefetchReqOpt_ready:is(1) end)
        env.expect_happen_until(20, function () return chi_txreq:fire() and chi_txreq.bits.addr:is(recv_addr) end)
        chi_txreq:dump()

        chi_rxdat:compdat(0, "0xdead", "0xbeef", 0, CHIResp.UC)

        verilua "appendTasks" {
            function ()
                env.expect_not_happen_until(50, function () return tl_d:fire() end)
            end
        }

        env.expect_happen_until(10, function () return slices_0.io_prefetchRespOpt_valid:is(1) and slices_0.io_prefetchRespOpt_ready:is(1) end)
        env.negedge()
        env.expect_not_happen_until(10, function () return slices_0.io_prefetchRespOpt_valid:is(1) end)

        env.posedge(100)
    end
}

local test_prefetch_train = env.register_test_case "test_prefetch_train" {
    function ()
        init_all()

        local req_addr = to_address(0, 0x01, 0x01)
        local req_source = 10
        local need_hint = 1
        env.negedge()
            write_dir(0, 0x01, utils.uint_to_onehot(0), 0x01, MixedState.I, 0x00, 0)
        tl_a:acquire_block(req_addr, TLParam.NtoB, req_source, need_hint)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.addr:is(req_addr) end)    
            end,
            function ()
                env.expect_happen_until(10, function () return slices_0.io_prefetchTrainOpt_valid:is(1) and slices_0.io_prefetchTrainOpt_ready:is(1) end)
            end
        }

        env.negedge(10)
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 0, CHIResp.UC)
        env.negedge()
            env.expect_happen_until(10, function () return tl_d:fire() and tl_d.bits.source:is(req_source) and tl_d.bits.data:is_hex_str("0xdead") end)
            env.expect_happen_until(10, function () return tl_d:fire() and tl_d.bits.source:is(req_source) and tl_d.bits.data:is_hex_str("0xbeef") end)

        env.posedge(100)
        env.dut_reset()
    end
}

local test_prefetch_dup_req = env.register_test_case "test_prefetch_dup_req" {
    function ()
        init_all()

        local addr = 0x1000
        local req_source = 10
        local need_hint = 0

        env.negedge()
            write_dir(0, 0x01, utils.uint_to_onehot(0), 0x01, MixedState.I, 0x00, 0)
        tl_a:acquire_block(addr, TLParam.NtoB, req_source, need_hint)

        env.negedge()
            pf_recv_addrs_0.valid:set(1)
            pf_recv_addrs_0.bits.addr:set(addr, true)
            pf_recv_addrs_0.bits.pfSource:set(10) -- MemReqSource.Prefetch2L2SMS
        env.negedge()
            pf_recv_addrs_0.valid:set(0)

        env.expect_happen_until(5, function () return reqBuf_0.io_taskIn_valid:is(1) and reqBuf_0.io_taskIn_ready:is(1) and reqBuf_0.io_taskIn_bits_opcode:is(TLOpcodeA.Hint) and reqBuf_0.isDupPrefetch:is(1) end)
        
        local nr_req_buf = 4
        for i = 1, nr_req_buf do
            -- 
            -- available when optParam.sinkaStallOnReqArb = false
            -- 
            local buf_state = reqBuf_0["buffers_" .. (i - 1) .. "_state"]
            local buf_opcode = reqBuf_0["buffers_" .. (i - 1) .. "_task_opcode"]

            -- this would make sure that the buffer is not containing a prefetch request
            buf_opcode:_if(function() return buf_state:is_not(0) end):expect_not(TLOpcodeA.Hint)


            -- 
            -- available when optParam.sinkaStallOnReqArb = true
            -- 
            -- local buf_valid = reqBuf_0["valids_" .. (i - 1)]
            -- local buf_opcode = reqBuf_0["buffers_" .. (i - 1) .. "_task_opcode"]
            -- buf_opcode:_if(function() return buf_valid:is_not(0) end):expect_not(TLOpcodeA.Hint)
        end

        env.posedge(100)
        env.dut_reset()
    end
}

local test_prefetch_replay = env.register_test_case "test_prefetch_replay" {
    function ()
        -- optParam.sinkaStallOnReqArb = false
        init_all()

        mp_0.io_mshrAlloc_s3_valid:set_force(0)
        mp_0.io_mshrAlloc_s3_ready:set_force(0)

        local addr = 0x1000
        env.negedge()
            pf_recv_addrs_0.valid:set(1)
            pf_recv_addrs_0.bits.addr:set(addr, true)
            pf_recv_addrs_0.bits.pfSource:set(10) -- MemReqSource.Prefetch2L2SMS
        env.negedge()
            pf_recv_addrs_0.valid:set(0)

        env.expect_happen_until(10, function () return mp_0.valid_s3:is(1) and mp_0.task_s3_opcode:is(TLOpcodeA.Hint) and mp_0.task_s3_isMshrTask:is(0) end)
        env.negedge()
            mp_0.io_reqBufReplay_s4_opt_valid:expect(1)
            mp_0.io_reqBufReplay_s4_opt_bits_isPrefetch:expect(1)
            reqBuf_0.replayMatchVec:expect(0x01)
        mp_0.io_mshrAlloc_s3_valid:set_release()
        mp_0.io_mshrAlloc_s3_ready:set_release()

        -- reissue the task
        env.expect_happen_until(10, function () return mp_0.valid_s3:is(1) and mp_0.task_s3_opcode:is(TLOpcodeA.Hint) and mp_0.task_s3_isMshrTask:is(0) and mp_0.task_s3_isReplayTask:is(1) end)

        env.posedge(100)
        env.dut_reset()
    end
}

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_chi_retry()
        test_prefetch_recv_req()
        test_prefetch_train()
        test_prefetch_dup_req()
        test_prefetch_replay()
        -- TODO: prefetch tlb_req_resp

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}



