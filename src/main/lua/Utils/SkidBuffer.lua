local utils = require "LuaUtils"
local env = require "env"
local verilua = verilua
local assert = assert
local expect = env.expect

local enq = ([[
    | valid
    | ready
    | data
]]):bundle {hier = cfg.top, prefix = "io_enq_", name = "io_enq"}

local deq = ([[
    | valid | ready | data
]]):bundle {hier = cfg.top, prefix = "io_deq_", is_decoupled = true, name = "io_deq"}

local test_bypass = env.register_test_case "test_bypass" {
    function ()
        env.dut_reset()

        deq.ready:set(1)

        enq.valid:set(1)
        enq.bits.data:set(42)

        env.negedge()
            enq.ready:expect(1)
            deq.valid:expect(1)
            deq.bits.data:expect(42)

        enq.valid:set(0)

        env.posedge(5)
    end
}

local test_skid = env.register_test_case "test_skid" {
    function ()
        env.dut_reset()

        deq.ready:set(0)

        env.negedge(5)
            enq.ready:expect(1)
            enq.valid:set(1)
            enq.bits.data:set(33)
        env.negedge()
            enq.ready:expect(0)
            enq.valid:set(0)
            enq.bits.data:set(0)
            
            deq.valid:expect(1)
            deq.bits.data:expect(33)
        env.negedge()
            enq.ready:expect(0)
        env.negedge(math.random(1, 10))
            deq.ready:set(1)
            deq.valid:expect(1)
            deq.bits.data:expect(33)
        
        enq.valid:set(0)

        env.posedge(5)
    end
}


verilua "appendTasks" {
    function ()
        sim.dump_wave()
        env.dut_reset()

        test_bypass()
        test_skid()

        
        env.posedge(5)
        env.TEST_SUCCESS()
    end
}