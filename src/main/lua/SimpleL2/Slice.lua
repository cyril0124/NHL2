local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local verilua = verilua
local assert = assert
local expect = env.expect

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeC = tl.TLOpcodeC
local TLOpcodeD = tl.TLOpcodeD
local MixedState = tl.MixedState

local resetFinish = (cfg.top .. ".u_Slice.dir.io_resetFinish"):chdl()

local tl_a = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
    | user_alias
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_a_"}

local tl_c = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
    | data
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_c_"}

local tl_d = ([[
    | valid
    | ready
    | data
    | source
    | opcode
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_d_"}

tl_a.acquire_block = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.AcquireBlock)
    this.bits.address:set(addr, true)
    this.bits.param:set(param)
    this.bits.source:set(source or 0)
    this.bits.size:set(5) -- 2^5 == 32
end

tl_a.acquire_perm = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.AcquirePerm)
    this.bits.address:set(addr, true)
    this.bits.param:set(param)
    this.bits.source:set(source or 0)
    this.bits.size:set(5) -- 2^5 == 32
end

tl_a.get = function (this, addr, source)
    assert(addr ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.Get)
    this.bits.address:set(addr, true)
    this.bits.param:set(0)
    this.bits.source:set(source or 0)
    this.bits.size:set(5) -- 2^5 == 32
end

local mpReq_s2 = ([[
    | valid
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top .. ".u_Slice.reqArb", is_decoupled = true, prefix = "io_mpReq_s2_", name = "mpReq_s2"}

local txreq = ([[
    | valid
    | ready
    | opcode
    | addr
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txreq_", name = "txreq"}

local chi_rxdat = ([[
    | valid
    | ready
    | dataID
    | data
    | txnID
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxdat_", name = "rxdat"}

local slice = dut.u_Slice
local mp = slice.mainPipe
local ms = slice.missHandler
local dir = slice.dir
local ds = slice.ds
local tempDS = slice.tempDS
local sourceD = slice.sourceD
local reqArb = slice.reqArb
local rxdat = slice.rxdat

local mshrs = {}
for i = 0, 15 do
    mshrs[i] = ms["mshrs_" .. i]
end

local function write_dir(set, wayOH, tag, state, clientsOH)
    assert(type(set) == "number")
    assert(type(wayOH) == "number")
    assert(type(tag) == "number")
    assert(type(state) == "number")

    local clientsOH = clientsOH or ("0b01"):number()
    
    env.negedge()
        dir:force_all()
            dir.io_dirWrite_s3_valid:set(1)
            dir.io_dirWrite_s3_bits_set:set(set)
            dir.io_dirWrite_s3_bits_wayOH:set(wayOH)
            dir.io_dirWrite_s3_bits_meta_tag:set(tag)
            dir.io_dirWrite_s3_bits_meta_state:set(state)
            dir.io_dirWrite_s3_bits_meta_aliasOpt:set(0)
            dir.io_dirWrite_s3_bits_meta_clientsOH:set(clientsOH)
    
    env.negedge()
        dir:release_all()
    
    env.posedge()
end

local function write_ds(set, way, data_str)
    assert(type(set) == "number")
    assert(type(way) == "number")
    assert(type(data_str) == "string")

    env.negedge()
        ds:force_all()
            ds.io_dsWrite_s2_valid:set(1)
            ds.io_dsWrite_s2_bits_set:set(set)
            ds.io_dsWrite_s2_bits_data:set_str(data_str)

    env.negedge()
        ds:release_all()
        ds:force_all()
            ds.io_dsWrWay_s3:set(way)
    
    env.negedge()
        ds:release_all()
    
    env.posedge()
end

local function to_address(set, tag)
    local offset_bits = 6
    local set_bits = mp.task_s3_set.get_width()
    local tag_bits = mp.task_s3_tag.get_width()

    return utils.bitpat_to_hexstr({
        {   -- set field
            s = offset_bits,   
            e = offset_bits + set_bits - 1,
            v = set
        },
        {   -- tag field
            s = offset_bits + set_bits, 
            e = offset_bits + set_bits + tag_bits - 1, 
            v = tag
        }
    }, 64):number()
end

local function reqArb_release_data(set, tag, way, datas)
    assert(type(datas) == "table")
    assert(#datas == 2)
    assert(type(datas[1]) == "number")

    local sinkC = "io_taskSinkC_s1_"
    env.negedge()
        dut:force_all()
        expect.equal(reqArb[sinkC .. "ready"]:get(), 1)
        reqArb[sinkC .. "valid"]:set(1)
        reqArb[sinkC .. "bits_set"]:set(set)
        reqArb[sinkC .. "bits_tag"]:set(tag)
        reqArb[sinkC .. "bits_opcode"]:set(TLOpcodeC.ReleaseData)
        reqArb[sinkC .. "bits_channel"]:set(("0b100"):number())
        reqArb[sinkC .. "bits_param"]:set(TLParam.TtoN)
        reqArb.io_dataSinkC_s1:set(datas[1])

    env.negedge()
        reqArb.io_dataSinkC_s1:set(datas[2])
    env.negedge()
        dut:release_all()
end

local test_replay_valid = env.register_test_case "test_replay_valid" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        slice.io_tl_d_ready:set(1)
        ms.io_mshrAlloc_s3_ready:set_force(0)

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 8) -- set = 0x00, tag = 0x04
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()
        
        env.posedge()
            expect.equal(mp.valid_s3:get(), 1)
            expect.equal(mp.task_s3_opcode:get(), TLOpcodeA.AcquireBlock)
            expect.equal(mp.task_s3_param:get(), TLParam.NtoT)
            expect.equal(mp.task_s3_source:get(), 8)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_mshrAlloc_s3_ready:get(), 0)

        env.posedge()
            expect.equal(mp.valid_s4:get(), 1) -- replay valid

        ms.io_mshrAlloc_s3_ready:set_release()
        env.posedge(100)
    end
}

local test_load_to_use = env.register_test_case "test_load_to_use" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        slice.io_tl_d_ready:set(1)

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 8)
        env.negedge()
            tl_a.valid:set(0)

        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()

        env.posedge()
            expect.equal(mp.valid_s3:get(), 1)
            expect.equal(mp.task_s3_opcode:get(), TLOpcodeA.AcquireBlock)
            expect.equal(mp.task_s3_param:get(), TLParam.NtoT)
            expect.equal(mp.task_s3_source:get(), 8)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)
            expect.equal(ds.io_dsRead_s3_valid:get(), 1)

        env.posedge()
            -- expect.equal(mp.valid_s4:get(), 1) -- resp valid
            -- expect.equal(ds.ren_s4:get(), 1)
        
        env.posedge()
        --    expect.equal(ds.ren_s5:get(), 1)
        
        env.posedge()
            expect.equal(ds.io_toTempDS_dsResp_ds4_valid:get(), 1)
            expect.equal(ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr), "000000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead")
            expect.equal(sourceD.io_d_valid:get(), 1)
            expect.equal(sourceD.io_d_ready:get(), 1)

        env.posedge()
            expect.equal(sourceD.io_d_valid:get(), 1)
            expect.equal(sourceD.io_d_ready:get(), 1)
        
        env.posedge()
    end
}

local test_load_to_use_latency = env.register_test_case "test_load_to_use_latency" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        tl_d.ready:set(1)

        local start_cycle = 0
        local end_cycle = 0

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 8)
        
        start_cycle = env.cycles()

        env.negedge()
            tl_a.valid:set(0)

        local ok = dut.clock:posedge_until(100, function (c)
            return tl_d:fire()
        end)
        assert(ok)
        
        end_cycle = env.cycles()
        print("load_to_use latency => " .. (end_cycle - start_cycle + 1) .. " cycles")
    end
}

local test_load_to_use_stall = env.register_test_case "test_load_to_use_stall" {
    function ()
        env.dut_reset()
        resetFinish:posedge()
        
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        slice.io_tl_d_ready:set(0)

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 8)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()
        
        env.posedge(4)
            expect.equal(ds.io_toTempDS_dsResp_ds4_valid:get(), 1)
            expect.equal(ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr), "000000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead")            
            expect.equal(sourceD.io_d_valid:get(), 1)
        
        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
                end)

                env.posedge()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                end)

                print("data check ok!")
            end
        }

        env.posedge(math.random(3, 20))
            slice.io_tl_d_ready:set(1)
        
        env.posedge(100)
    end
}

local test_grantdata_continuous_stall_3 = env.register_test_case "test_grantdata_continuous_stall_3" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        -- 0x20000
        write_dir(0x00, ("0b0100"):number(), 0x08, MixedState.TC)    
        write_ds(0x00, 0x02, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead1},
            {s = 256, e = 256 + 63, v = 0xbeef1}
        }, 512))

        -- 0x30000
        write_dir(0x00, ("0b1000"):number(), 0x0C, MixedState.TC)    
        write_ds(0x00, 0x03, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead2},
            {s = 256, e = 256 + 63, v = 0xbeef2}
        }, 512))

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_task = function()
                for i = 1, 3 do
                    env.expect_happen_until(10, function (c)
                        return mpReq_s2.valid:get() == 1
                    end)
                    print("get mpRe_s2 at", dut.cycles:get(), "set:", mpReq_s2.bits.set:get_str(HexStr), "tag:", mpReq_s2.bits.tag:get_str(HexStr))
                    env.negedge()
                end
            end,

            check_task_1 = function()
                for i = 1, 3 do
                    env.expect_happen_until(10, function (c)
                        return ds.io_toTempDS_dsResp_ds4_valid:get() == 1
                    end)
                    print("get toTempDS_dsResp_ds4 at", dut.cycles:get(), ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr))
                    env.negedge()
                end
            end,

            check_task_2 = function ()
                local ok = false
                local datas_0 = {0xdead, 0xdead1, 0xdead2}
                local datas_1 = {0xbeef, 0xbeef1, 0xbeef2}
                for i = 1, 3 do
                    env.expect_happen_until(100, function (c)
                        return tl_d:fire() and tl_d.bits.data:get()[1] == datas_0[i]
                    end)

                    env.expect_happen_until(100, function (c)
                        return tl_d:fire() and tl_d.bits.data:get()[1] == datas_1[i]
                    end)
                end

                env.negedge()
                env.expect_not_happen_until(50, function (c)
                    return tl_d:fire() 
                end)
            end,
        }

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 1)
        env.negedge()
            tl_a:acquire_block(0x20000, TLParam.NtoT, 2)
        env.negedge()
            tl_a:acquire_block(0x30000, TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge(math.random(5, 20))

        for i = 1, 20 do
            env.negedge()
                tl_d.ready:set(math.random(0, 1))
        end
        
        env.posedge(100)
    end
}

local test_grantdata_mix_grant = env.register_test_case "test_grantdata_mix_grant" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        -- 0x20000
        write_dir(0x00, ("0b0100"):number(), 0x08, MixedState.TC)    
        write_ds(0x00, 0x02, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead1},
            {s = 256, e = 256 + 63, v = 0xbeef1}
        }, 512))

        -- 0x30000
        write_dir(0x00, ("0b1000"):number(), 0x0C, MixedState.TC)    
        write_ds(0x00, 0x03, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead2},
            {s = 256, e = 256 + 63, v = 0xbeef2}
        }, 512))

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_task = function()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.GrantData and tl_d.bits.data:get()[1] == 0xdead
                end)

                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.GrantData and tl_d.bits.data:get()[1] == 0xbeef
                end)

                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.Grant
                end)

                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.GrantData and tl_d.bits.data:get()[1] == 0xdead2
                end)

                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.GrantData and tl_d.bits.data:get()[1] == 0xbeef2
                end)
            end,
        }

        env.negedge()
            tl_a:acquire_block(0x10000, TLParam.NtoT, 1)
        env.negedge()
            tl_a:acquire_perm(0x20000, TLParam.NtoT, 2)
        env.negedge()
            tl_a:acquire_block(0x30000, TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)

        env.posedge(math.random(5, 20))

        for i = 1, 20 do
            env.negedge()
                tl_d.ready:set(math.random(0, 1))
        end

        env.posedge(200)
    end
}

local test_release_write = env.register_test_case "test_release_write" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        verilua "appendTasks" {
            check_task = function()
                env.expect_happen_until(100, function (c)
                    return ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000100"
                end)

                env.negedge()
                env.expect_happen_until(100, function (c)
                    return ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000300"
                end)
            end
        }

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC)

        reqArb_release_data(0x00, 0x04, ("0b100"):number(), {0x100, 0x200})

        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_dsRead_s3_valid:set(1)
            ds.io_dsRead_s3_bits_set:set(0x00)
            ds.io_dsRead_s3_bits_way:set(0x1)
        env.negedge()
            dut:release_all()
            dut:force_all()
            tempDS.io_fromDS_dsResp_ds4_valid:set(0)
        env.negedge(10)
            dut:release_all()
        
        env.negedge()
            reqArb_release_data(0x00, 0x04, ("0b100"):number(), {0x300, 0x400})
        
        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_dsRead_s3_valid:set(1)
            ds.io_dsRead_s3_bits_set:set(0x00)
            ds.io_dsRead_s3_bits_way:set(0x1)
        env.negedge()
            dut:release_all()
            dut:force_all()
            tempDS.io_fromDS_dsResp_ds4_valid:set(0)
        env.negedge(10)
            dut:release_all()

        env.posedge(100)
    end
}

local test_release_continuous_write = env.register_test_case "test_release_continuous_write" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        verilua "appendTasks" {
            check_task = function()
                env.expect_happen_until(100, function (c)
                    return ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000300"
                end)
            end
        }

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC)

        reqArb_release_data(0x00, 0x04, ("0b100"):number(), {0x100, 0x200})
        reqArb_release_data(0x00, 0x04, ("0b100"):number(), {0x300, 0x400})

        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_dsRead_s3_valid:set(1)
            ds.io_dsRead_s3_bits_set:set(0x00)
            ds.io_dsRead_s3_bits_way:set(0x1)
        
        env.negedge()
            dut:release_all()
            dut:force_all()
            tempDS.io_fromDS_dsResp_ds4_valid:set(0)

        env.posedge(100)
        dut:release_all()
    end
}

local test_sinkA_hit = env.register_test_case "test_sinkA_hit" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        local sync = ("sync"):ehdl()
        
        verilua "appendTasks" {
            check_dir_write = function ()

                -- [1] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_state:get(), MixedState.TTC)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_clientsOH:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_tag:get(), 4)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)

                -- [2] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_state:get(), MixedState.TTC)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_clientsOH:get(), 2)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_tag:get(), 4)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)

                -- [3] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [4] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    -- 
                    -- CacheAlias will hit and with different alias field between 
                    -- task_s3_alias and dirResp_s3_alias 
                    -- 
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.cacheAlias_s3:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [5] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [6] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.needProbeOnHit_a_s3:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [7] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.needProbeOnHit_a_s3:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

            end
        }

        -- 
        -- [1] test AcquirePerm.NtoT hit on Tip Clean
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:acquire_perm(to_address(0, 4), TLParam.NtoT, 1)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(math.random(5, 10))

        -- 
        -- [2] test AcquireBlock.NtoT hit on Tip Clean
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:acquire_block(to_address(0, 4), TLParam.NtoT, 28) -- source = 28 ==> clientsOH = "0b10"
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(math.random(5, 10))

        -- 
        -- [3] test Get hit on Tip Clean
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:get(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        -- 
        -- [4] test AcquireBlock hit on Tip Clean but met CacheAlias
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 2)
        env.negedge()
            tl_a:acquire_block(to_address(0, 4), TLParam.NtoT, 28)
            tl_a.bits.user_alias:set(1)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        -- 
        -- [5] test AcquireBlock.NtoT hit and need read downward
        --
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.BC, 0)
        env.negedge()
            tl_a:acquire_block(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)
        
        -- 
        -- [6] test AcquireBlock.NtoT hit and need probe upward
        --
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, 1)
        env.negedge()
            tl_a:acquire_block(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        -- 
        -- [7] test Get hit and need probe upward
        --
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, 1)
        env.negedge()
            tl_a:get(to_address(0, 4), 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        env.posedge(100)
    end
}

local test_sinkA_miss = env.register_test_case "test_sinkA_miss" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 
        -- [1] test AcquireBlock miss
        -- 
        env.negedge()
            tl_a:acquire_block(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        env.posedge(2)
            expect.equal(mp.io_dirResp_s3_bits_hit:get(), 0)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

        -- 
        -- [2] test Get miss
        -- 
        env.negedge()
            tl_a:get(to_address(0, 4), 28)
        env.negedge()
            tl_a.valid:set(0)
        env.posedge(2)
            expect.equal(mp.io_dirResp_s3_bits_hit:get(), 0)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_dirWrite_s3_valid:get(), 0)
        
        env.posedge(100)
    end
}

local test_release_hit = env.register_test_case "test_release_hit" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 
        -- [1] test ReleaseData.TtoN hit
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TTC, 1)
        env.negedge()
            tl_c.bits.data:set_str("0xdead")
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.ReleaseData)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.TtoN)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.bits.data:set_str("0xbeef")
        env.negedge()
            tl_c.valid:set(0)
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(0x00)
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TD)

        -- 
        -- [2] test Release.TtoB hit on TC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TC, ("0b11"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.TtoB)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x3)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b11"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TC)

        -- 
        -- [3] test Release.TtoB hit on TTC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TTC, ("0b01"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.TtoB)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b01"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TC)

        -- 
        -- [4] test Release BtoN hit on BBC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.BBC, ("0b01"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.BtoN)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b00"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.BC)

        env.posedge(100)
    end
}

local test_sinkA_miss = env.register_test_case "test_sinkA_miss" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        dut.io_chi_txreq_ready:set(1)
        dut.io_chi_txrsp_ready:set(1)

        env.negedge()
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, 3)
            tl_a.valid:set(1)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge(2)
            mp.io_dirResp_s3_bits_hit:expect(0)
        
        env.expect_happen_until(10, function ()
            return txreq.valid:get() == 1 and txreq.ready:get() == 1
        end)
        txreq:dump()
        mshrs[0].io_status_valid:expect(1)
        mshrs[0].io_status_set:expect(0x10)
        mshrs[0].io_status_tag:expect(0x20)
        mshrs[0].state_w_compdat:expect(0)
        for i = 1, 15 do
            mshrs[i].io_status_valid:expect(0)
        end

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(10, function()
                    return mshrs[0].state_w_compdat:get() == 1
                end)

                env.expect_happen_until(10, function()
                    return mshrs[0].state_s_compack:get() == 1
                end)
            end
        }

        -- send data to l2cache
        env.negedge()
            chi_rxdat.bits.txnID:set(0)
            chi_rxdat.bits.dataID:set(0)
            chi_rxdat.bits.data:set_str("0xdead")
            chi_rxdat.valid:set(1)
        env.negedge()
            chi_rxdat.bits.data:set_str("0xbeef")
            chi_rxdat.bits.dataID:set(2) -- last data beat
        env.negedge()
            chi_rxdat.valid:set(0)

        env.posedge(100)
    end
}

verilua "mainTask" { function ()
    -- sim.dump_wave()

    -- 
    -- normal test cases
    -- 
    mp.dirWen_s3:set_force(0)
        reqArb.io_dsWrCrd:set_force(0)
            test_replay_valid()
            test_load_to_use()
            test_load_to_use_latency()
            test_load_to_use_stall()
            test_grantdata_continuous_stall_3()
            test_grantdata_mix_grant()
        reqArb.io_dsWrCrd:set_release()

        test_release_write()
        test_release_continuous_write()
    mp.dirWen_s3:set_release()

    -- 
    -- coherency test cases
    -- 
    test_sinkA_hit()
    test_release_hit()

    sim.dump_wave()
    test_sinkA_miss()

    env.posedge(100)
    env.TEST_SUCCESS()
end }
