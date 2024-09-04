local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local chi = require "CHI"
local verilua = verilua

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

local l2 = dut.u_SimpleL2CacheWrapperDecoupled.l2
local slices_0 = l2.slices_0
local slices_1 = l2.slices_1
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

local test_chi_retry = env.register_test_case "test_chi_retry" {
    function ()
        env.dut_reset()
        
        initialize_tl_ports()
        initialize_chi_ports()
        wait_resetFinish()

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

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_chi_retry()

        sim.finish()
    end
}



