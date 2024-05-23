local utils = require "LuaUtils"
local env = require "env"

local assert = assert
local TEST_SUCCESS = env.TEST_SUCCESS
local posedge = env.posedge
local negedge = env.negedge
local dut_reset = env.dut_reset
local send_pulse = env.send_pulse

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
        dut_reset()
        set_ready()

        if reqArb.resetFinish() == 0 then
            reqArb.resetFinish:posedge()
        end
    
        assert(taskMSHR_s0.ready:get() == 1)
    
        negedge()
            taskMSHR_s0.valid:set(1)
        
        negedge()
            taskMSHR_s0.valid:set(0)

        assert(dirRead_s1:fire())
        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 0)
        end
    
        posedge()
            assert(taskMSHR_s0.ready:get() == 0)
    
        posedge()
            assert(taskMSHR_s0.ready:get() == 1)
    
        posedge(10)
        reset_ready()
    end
}

local test_basic_sink_req = env.register_test_case "test_basic_sink_req" {
    function ()
        dut_reset()
        set_ready()

        if reqArb.resetFinish() == 0 then
            reqArb.resetFinish:posedge()
        end

        posedge()

        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 1)
        end

        negedge()
            taskSinkA_s1.valid:set(1)

        negedge()
            taskSinkA_s1.valid:set(0)
            assert(dirRead_s1:fire())

        posedge(10)
        reset_ready()
    end
}

local test_mshr_block_sink_req = env.register_test_case "test_mshr_block_sink_req" {
    function ()
        dut_reset()
        set_ready()

        if reqArb.resetFinish() == 0 then
            reqArb.resetFinish:posedge()
        end

        posedge()

        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 1)
        end

        negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            taskMSHR_s0.valid:set(1)
            taskSinkA_s1.valid:set(0)

        posedge()
            assert(taskSinkA_s1.ready:get() == 1)

        negedge()
            assert(taskMSHR_s0.ready:get() == 0)
            assert(taskSinkA_s1.ready:get() == 0)
            assert(reqArb.isTaskMSHR_s1:get() == 1)
            taskMSHR_s0.valid:set(0)
        
        posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            taskSinkA_s1.valid:set(1)

        negedge()
            assert(taskSinkA_s1.ready:get() == 1)

        posedge()
            taskSinkA_s1.valid:set(0)

        posedge(10)
        reset_ready()
    end
}

local test_sinkC_block_sinkA = env.register_test_case "test_sinkC_block_sinkA" {
    function ()
        dut_reset()
        set_ready()

        if reqArb.resetFinish() == 0 then
            reqArb.resetFinish:posedge()
        end

        posedge()

        negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
            taskSinkC_s1.valid:set(1)
        
        posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
        
        negedge()
            taskSinkC_s1.valid:set(0)
       
        posedge()
            

        posedge(10)
        reset_ready()
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()

        dut_reset()

        test_basic_mshr_req()
        test_basic_sink_req()
        test_mshr_block_sink_req()
        test_sinkC_block_sinkA()

        TEST_SUCCESS()
    end
}

