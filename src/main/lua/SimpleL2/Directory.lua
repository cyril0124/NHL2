local env = require "env"
local tl = require "TileLink"
local MixedState = tl.MixedState
local expect = env.expect

local dir = dut.u_Directory
local resetFinish = dir.io_resetFinish

local dirRead_s1 = ([[
    | valid
    | ready
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_dirRead_s1_", name = "dirRead_s1"}

local dirResp_s3 = ([[
    | valid
    | wayOH
    | hit
    | meta_state
    | meta_tag
    | meta_aliasOpt
    | meta_fromPrefetch
]]):bundle {hier = cfg.top, prefix = "io_dirResp_s3_", name = "dirResp_s3"}

local dirWrite_s3 = ([[
    | valid
    | set
    | wayOH
    | meta_state
    | meta_tag
    | meta_aliasOpt
    | meta_fromPrefetch
]]):bundle {hier = cfg.top, prefix = "io_dirWrite_s3_", name = "dirWrite_s3"}


local test_write_and_read_dir = env.register_test_case "test_write_and_read_dir" {
    function ()
        env.dut_reset()

        env.posedge()
            expect.equal(dirRead_s1.ready:get(), 0)
            -- expect.equal(dirWrite_s3.ready:get(), 0)
            expect.equal(dirResp_s3.valid:get(), 0)

        resetFinish:posedge()

        print("resetFinish current cycles => " .. dut.cycles())

        env.negedge()
            expect.equal(dirRead_s1.ready:get(), 1)
            -- expect.equal(dirWrite_s3.ready:get(), 1)
            expect.equal(dirResp_s3.valid:get(), 0)

        env.negedge()
            dirWrite_s3.valid:set(1)
            dirWrite_s3.bits.wayOH:set(("0b00000001"):number())
            dirWrite_s3.bits.set:set(0x11)
            dirWrite_s3.bits.meta_tag:set(0x22)
            dirWrite_s3.bits.meta_state:set(MixedState.TC)
            dirWrite_s3.bits.meta_aliasOpt:set(1)
            dirWrite_s3.bits.meta_fromPrefetch:set(1)
            print(dut.cycles(), dirWrite_s3:dump_str())

        env.negedge()
            dirWrite_s3.valid:set(0)

        env.posedge()
            expect.equal(dirRead_s1.ready:get(), 1)
        
        -- env.posedge(10)

        env.negedge()
            expect.equal(dirRead_s1.ready:get(), 1)
            dirRead_s1.valid:set(1)
            dirRead_s1.bits.set:set(0x11)
            dirRead_s1.bits.tag:set(0x22)
            print(dut.cycles(), dirRead_s1:dump_str())
        
        env.negedge()
            dirRead_s1.valid:set(0)
        
        env.posedge(2)
            assert(dirResp_s3:fire())
            expect.equal(dirResp_s3.bits.wayOH:get(), 0x01)
            expect.equal(dirResp_s3.bits.hit:get(), 1)
            expect.equal(dirResp_s3.bits.meta_state:get(), MixedState.TC)
            expect.equal(dirResp_s3.bits.meta_tag:get(), 0x22)
            expect.equal(dirResp_s3.bits.meta_aliasOpt:get(), 1)
            expect.equal(dirResp_s3.bits.meta_fromPrefetch:get(), 1)
            print(dut.cycles(), dirResp_s3:dump_str())

        local cycles = dut.cycles:chdl()
        print(cycles:dump_str())

    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()
        env.posedge()
        
        test_write_and_read_dir()

        env.posedge(100)

        
        env.TEST_SUCCESS()
    end
}