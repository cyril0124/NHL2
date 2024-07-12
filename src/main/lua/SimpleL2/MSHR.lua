local env = require "env"
local expect = env.expect

local ms = dut.u_MSHR

local alloc_req = ([[
    | valid
]]):abdl{hier = cfg.top, prefix = "io_alloc_s3_"}

local test_basic_mshr = env.register_test_case "test_basic_mshr" {
    function ()
        env.dut_reset()

        env.negedge()
            alloc_req.valid:set(1)
        env.negedge()
            alloc_req.valid:set(0)
        

        env.posedge(100)
    end
}

verilua "appendTasks" {
    function ()
        env.dut_reset()


        env.posedge(100)
        env.TEST_SUCCESS()
    end
}