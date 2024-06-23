local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"

local assert = assert
local expect = env.expect
local TLOpcodeC = tl.TLOpcodeC

local RequestOwner = utils.enum_define {
    Level1     = tonumber("0b001"),
    CMO        = tonumber("0b010"),
    Prefetcher = tonumber("0b011"),
    Snoop      = tonumber("0b100"),
    MSHR       = tonumber("0b101"),
}


local taskMSHR_s0 = ([[
    | ready
    | valid
    | owner
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_taskMSHR_s0_", is_decoupled = true, name = "taskMSHR_s0"}

local taskReplay_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskReplay_s1_", is_decoupled = true, name = "taskReplay_s1"}

local taskCMO_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskCMO_s1_", is_decoupled = true, name = "taskCMO_s1"}

local taskSnoop_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSnoop_s1_", is_decoupled = true, name = "taskSnoop_s1"}

local taskSinkC_s1 = ([[
    | ready
    | valid
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_taskSinkC_s1_", is_decoupled = true, name = "taskSinkC_s1"}

local taskSinkA_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSinkA_s1_", is_decoupled = true, name = "taskSinkA_s1"}

local dirRead_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_dirRead_s1_", is_decoupled = true}

local mpReq_s2 = ([[
    | valid
]]):bundle {hier = cfg.top, prefix = "io_mpReq_s2_", is_decoupled = false}


local normal_tasks = {
    taskReplay_s1,
    taskCMO_s1,
    taskSnoop_s1,
    taskSinkC_s1,
    taskSinkA_s1,
}

local clock = dut.clock:chdl()
local reqArb = dut.u_RequestArbiter

local dataSinkC_s1 = dut.io_dataSinkC_s1

local function set_ready()
    dut.io_dirRead_s1_ready = 1
end

local function reset_ready()
    dut.io_dirRead_s1_ready = 0
end

local function send_wr_crd()
    dut.io_dsWrCrd = 1
    env.negedge()
    dut.io_dsWrCrd = 0
    env.negedge()
end

local test_basic_mshr_req = env.register_test_case "test_basic_mshr_req" {
    function ()
        env.dut_reset()
        set_ready()

        env.posedge()

        expect.equal(taskMSHR_s0.ready:get(), 1)
    
        env.negedge()
            taskMSHR_s0.valid:set(1)
        
        env.negedge()
            taskMSHR_s0.valid:set(0)

        assert(dirRead_s1:fire())
        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 0)
        end

        env.posedge()
            expect.equal(taskMSHR_s0.ready:get(), 0)
    
        env.posedge()
            expect.equal(taskMSHR_s0.ready:get(), 1)
    
        env.posedge(10)
        reset_ready()
    end
}

local test_basic_sink_req = env.register_test_case "test_basic_sink_req" {
    function ()
        env.dut_reset()
        set_ready()
        send_wr_crd()

        env.posedge()

        for i, task in ipairs(normal_tasks) do
            task:dump()
            assert(task.ready:get() == 1)
        end

        env.negedge()
            taskSinkA_s1.valid:set(1)

        env.negedge()
            taskSinkA_s1.valid:set(0)
            assert(dirRead_s1:fire())

        env.posedge(10)
        reset_ready()
    end
}

local test_mshr_block_sink_req = env.register_test_case "test_mshr_block_sink_req" {
    function ()
        env.dut_reset()
        set_ready()
        send_wr_crd()

        env.posedge()

        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 1)
        end

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            taskMSHR_s0.valid:set(1)
            taskSinkA_s1.valid:set(0)

        env.posedge()
            assert(taskSinkA_s1.ready:get() == 1)

        env.negedge()
            assert(taskMSHR_s0.ready:get() == 0)
            assert(taskSinkA_s1.ready:get() == 0)
            assert(reqArb.isTaskMSHR_s1:get() == 1)
            taskMSHR_s0.valid:set(0)
        
        env.posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            taskSinkA_s1.valid:set(1)

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)

        env.posedge()
            taskSinkA_s1.valid:set(0)

        env.posedge(10)
        reset_ready()
    end
}

local test_sinkC_block_sinkA = env.register_test_case "test_sinkC_block_sinkA" {
    function ()
        env.dut_reset()
        set_ready()
        send_wr_crd()

        env.posedge()

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
            taskSinkC_s1.valid:set(1)
        
        env.posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
        
        env.negedge()
            taskSinkC_s1.valid:set(0)
       
        env.posedge()
            

        env.posedge(10)
        reset_ready()
    end
}

local test_basic_release_data = env.register_test_case "test_release_data" {
    function ()
        env.dut_reset()
        set_ready()
        send_wr_crd()

        env.posedge()
        
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.dsWrCnt:get(), 1)
            taskSinkC_s1.valid:set(1)
            taskSinkC_s1.bits.set:set(0x01)
            taskSinkC_s1.bits.tag:set(0x02)
            taskSinkC_s1.bits.opcode:set(TLOpcodeC.ReleaseData)
            dataSinkC_s1:set_str("0x1234") -- beat 0
        env.posedge()
            expect.equal(reqArb.io_dirRead_s1_valid:get(), 1)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.dsWrCnt:get(), 1)
            dataSinkC_s1:set_str("0x5678") -- beat 1
        env.posedge()
            expect.equal(reqArb.io_dsWrite_s2_valid:get(), 1)
            expect.equal(reqArb.io_dsWrite_s2_bits_set:get(), 0x1)
            expect.equal(reqArb.io_dsWrite_s2_bits_data:get_str(HexStr), "00000000000000000000000000000000000000000000000000000000000056780000000000000000000000000000000000000000000000000000000000001234")
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 0)
            expect.equal(reqArb.dsWrCnt:get(), 0)
            taskSinkC_s1.valid:set(0)
        
        env.posedge(math.random(5, 10), function (c)
            expect.equal(taskSinkC_s1.ready:get(), 0)
            expect.equal(reqArb.dsWrCnt:get(), 0) -- should not be valid until new credit return
        end)

        -- Release can be accepted witout watting for the credit return
        env.negedge()
            taskSinkC_s1.bits.opcode:set(TLOpcodeC.Release)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)

        send_wr_crd()
        expect.equal(reqArb.dsWrCnt:get(), 1)
        
        env.posedge(10)
        reset_ready()
    end
}

local test_basic_release = env.register_test_case "test_basic_release" {
    function ()
        env.dut_reset()
        set_ready()
        send_wr_crd()

        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.dsWrCnt:get(), 1)
            taskSinkC_s1.valid:set(1)
            taskSinkC_s1.bits.set:set(0x01)
            taskSinkC_s1.bits.tag:set(0x02)
            taskSinkC_s1.bits.opcode:set(TLOpcodeC.Release)
        env.posedge()
            expect.equal(reqArb.io_dirRead_s1_valid:get(), 1)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.io_dsWrite_s2_valid:get(), 0)
            expect.equal(reqArb.dsWrCnt:get(), 1)
            taskSinkC_s1.valid:set(0)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.dsWrCnt:get(), 1)
        
        env.posedge(math.random(3, 10), function (c)
            expect.equal(taskSinkC_s1.ready:get(), 1)
            expect.equal(reqArb.dsWrCnt:get(), 1)
        end)

        env.posedge(10)
        reset_ready()
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()
        
        dut.io_resetFinish:set(1)

        test_basic_mshr_req()
        test_basic_sink_req()
        test_mshr_block_sink_req()
        test_sinkC_block_sinkA()

        test_basic_release_data()
        test_basic_release()

        env.TEST_SUCCESS()
    end
}

