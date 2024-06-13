local utils = require "LuaUtils"
local env = require "env"

local assert = assert
local expect = env.expect
local TEST_SUCCESS = env.TEST_SUCCESS

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
]]):bundle {hier = cfg.top, prefix = "io_taskMSHR_s0_", is_decoupled = true}

local taskReplay_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskReplay_s1_", is_decoupled = true}

local taskCMO_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskCMO_s1_", is_decoupled = true}

local taskSnoop_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSnoop_s1_", is_decoupled = true}

local taskSinkC_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSinkC_s1_", is_decoupled = true}

local taskSinkA_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSinkA_s1_", is_decoupled = true}

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

local function set_ready()
    dut.io_dirRead_s1_ready = 1
end

local function reset_ready()
    dut.io_dirRead_s1_ready = 0
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

        env.posedge()

        for i, task in ipairs(normal_tasks) do
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

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()
        
        dut.io_resetFinish:set(1)

        test_basic_mshr_req()
        test_basic_sink_req()
        test_mshr_block_sink_req()
        test_sinkC_block_sinkA()

        TEST_SUCCESS()
    end
}

