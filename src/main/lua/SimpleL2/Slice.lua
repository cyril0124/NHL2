local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local verilua = verilua
local assert = assert
local expect = env.expect

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local MixedState = tl.MixedState

local resetFinish = (cfg.top .. ".u_Slice.dir.io_resetFinish"):chdl()
local sync_ehdl = ("sync"):ehdl()
local init_ehdl = ("init"):ehdl()

local tl_a = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_a_"}


local tl_d = ([[
    | valid
    | ready
    | data
    | source
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

local mpReq_s2 = ([[
    | valid
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top .. ".u_Slice.reqArb", is_decoupled = true, prefix = "io_mpReq_s2_"}


local slice = dut.u_Slice
local mp = dut.u_Slice.mainPipe
local dir = dut.u_Slice.dir
local ds = dut.u_Slice.ds
local sourceD = dut.u_Slice.sourceD

local function write_dir(set, wayOH, tag, state)
    assert(type(set) == "number")
    assert(type(wayOH) == "number")
    assert(type(tag) == "number")
    assert(type(state) == "number")
    
    env.negedge()
        dir:force_all()
            dir.io_dirWrite_s3_valid:set(1)
            dir.io_dirWrite_s3_bits_set:set(set)
            dir.io_dirWrite_s3_bits_wayOH:set(wayOH)
            dir.io_dirWrite_s3_bits_meta_tag:set(tag)
            dir.io_dirWrite_s3_bits_meta_state:set(state)
    
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
            ds.io_wrWay_s3:set(way)
    
    env.negedge()
        ds:release_all()
    
    env.posedge()
end


local test_replay_valid = env.register_test_case "test_replay_valid" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        slice.io_tl_d_ready:set(1)

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 8) -- set = 0x00, tag = 0x04
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

        env.posedge(100)
    end
}

local test_load_to_use = env.register_test_case "test_load_to_use" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, tonumber("0010", 2), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        slice.io_tl_d_ready:set(1)

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 8)
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
            expect.equal(mp.valid_s4:get(), 1) -- resp valid
            expect.equal(ds.ren_s4:get(), 1)
        
        env.posedge()
           expect.equal(ds.ren_s5:get(), 1)
        
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

        write_dir(0x00, tonumber("0010", 2), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        tl_d.ready:set(1)

        local start_cycle = 0
        local end_cycle = 0

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 8)
        
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
        
        write_dir(0x00, tonumber("0010", 2), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        slice.io_tl_d_ready:set(0)

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 8)
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
                local ok = false
                ok = dut.clock:posedge_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
                end)
                assert(ok)

                env.posedge()
                ok = dut.clock:posedge_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                end)
                assert(ok)

                print("data check ok!")
            end
        }

        env.posedge(math.random(3, 20))
            slice.io_tl_d_ready:set(1)
        
        env.posedge(100)
    end
}

local test_grantdata_continuous_stall_3 = env.register_test_case "test_grantdata_continuous_stall_2" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 0x100
        write_dir(0x00, tonumber("0010", 2), 0x04, MixedState.TC)    
        write_ds(0x00, 0x01, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        -- 0x200
        write_dir(0x00, tonumber("0100", 2), 0x08, MixedState.TC)    
        write_ds(0x00, 0x02, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead1},
            {s = 256, e = 256 + 63, v = 0xbeef1}
        }, 512))

        -- 0x300
        write_dir(0x00, tonumber("1000", 2), 0x0C, MixedState.TC)    
        write_ds(0x00, 0x03, utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead2},
            {s = 256, e = 256 + 63, v = 0xbeef2}
        }, 512))

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_task = function()
                local ok = false
                for i = 1, 3 do
                    ok = dut.clock:posedge_until(10, function (c)
                        return mpReq_s2.valid:get() == 1
                    end)
                    assert(ok, i)
                    print("get mpRe_s2 at", dut.cycles:get(), "set:", mpReq_s2.bits.set:get_str(HexStr), "tag:", mpReq_s2.bits.tag:get_str(HexStr))
                    env.negedge()
                end
            end,

            check_task_1 = function()
                local ok = false
                for i = 1, 3 do
                    ok = dut.clock:posedge_until(10, function (c)
                        return ds.io_toTempDS_dsResp_ds4_valid:get() == 1
                    end)
                    assert(ok, i)
                    print("get toTempDS_dsResp_ds4 at", dut.cycles:get(), ds.io_toTempDS_dsResp_ds4_bits_data:get_str(HexStr))
                    env.negedge()
                end
            end,
        }

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 1)
        env.negedge()
            tl_a:acquire_block(0x200, TLParam.NtoT, 2)
        env.negedge()
            tl_a:acquire_block(0x300, TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)
        
        
        env.posedge(20)
        
        env.negedge(math.random(1, 10))
            tl_d.ready:set(1)
        env.negedge()
            tl_d.ready:set(0)

        env.negedge(math.random(1, 10))
            tl_d.ready:set(1)
        env.negedge()
            tl_d.ready:set(0)

        -- env.posedge()
        --     expect.equal(mpReq_s2.valid:get(), 1)
        --     mpReq_s2:dump()
        
        -- env.posedge(4)
        --     expect.equal(ds.io_toTempDS_dsResp_ds4_valid:get(), 1)
        
        env.posedge(100)
    end
}

verilua "mainTask" { function ()
    sim.dump_wave()

    -- test_replay_valid()
    -- test_load_to_use()
    -- test_load_to_use_latency()
    -- test_load_to_use_stall()
    test_grantdata_continuous_stall_3()
    
    env.posedge(100)

    env.TEST_SUCCESS()
end }
