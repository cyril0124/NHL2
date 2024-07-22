local utils = require "LuaUtils"
local env = require "env"
local assert = assert
local expect = env.expect

local task_in = ([[
    | valid
    | ready
    | opcode
    | param
]]):bundle {hier = cfg.top, prefix = "io_taskIn_", name = "taskIn"}

local task_out = ([[
    | valid
    | ready
    | opcode
    | param
]]):bundle {hier = cfg.top, prefix = "io_taskOut_", name = "taskOut"}

local reqBuf = dut.u_RequestBuffer

local test_task_flow = env.register_test_case "test_task_flow" {
    function ()
        env.dut_reset()

        task_out.ready:set(1)

        reqBuf.insertOH:expect(1)
        
        env.negedge()
            task_in.valid:set(1)
            env.posedge()
            task_in.ready:expect(1)
        env.negedge()
            reqBuf.storeTask:expect(0)
            reqBuf.insertOH:expect(1)
            task_in.valid:set(0)

        env.posedge(100)
    end
}

local test_use_buffer = env.register_test_case "test_use_buffer" {
    function ()
        env.dut_reset()

        task_out.ready:set(0)
        
        reqBuf.insertOH:expect(1)

        env.negedge()
            task_in.valid:set(1)
            env.posedge()
            task_in.ready:expect(1)
        env.negedge()
            reqBuf.storeTask:expect(1)
            reqBuf.insertOH:expect(2)
            task_in.valid:set(0)

        reqBuf.hasEntry:expect(1)

        -- store until full
        repeat
            env.negedge()
                task_in.valid:set(1)
            env.negedge()
                task_in.valid:set(0)
        until reqBuf.hasEntry:is(0)

        reqBuf.hasEntry:expect(0)
        task_in.ready:expect(0)

        env.negedge()
            task_out.ready:set(1)
        env.negedge()
            reqBuf.hasEntry:expect(1)

        -- use buffer until empty
        repeat
            env.negedge()
        until task_out.valid:is(0)

        task_out.valid:expect(0)

        env.posedge(100)
    end
}


verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_task_flow()
        test_use_buffer()

        env.TEST_SUCCESS()
    end
}