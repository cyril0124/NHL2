local env = require "env"
local expect = env.expect
local format = string.format

local missHandler = dut.u_MissHandler

local alloc = ([[
    | valid
    | ready
    | set
    | tag
    | source
    | channel
]]):bdl {hier = cfg.top, prefix = "io_mshrAlloc_s3_"}

local test_basic_alloc = env.register_test_case "test_basic_alloc" {
    function ()
        env.dut_reset() 

        env.negedge()
            alloc.valid:set(1)
            alloc.bits.set:set(0x12)
        env.negedge()
            alloc.valid:set(0)

        env.posedge()
            expect.equal(missHandler.mshrValidVec:get(), 1)
            expect.equal(missHandler.mshrs_0.io_status_valid:get(), 1)
            expect.equal(missHandler.mshrs_0.io_status_set:get(), 0x12)

        env.posedge(100)
    end
}

local test_alloc_until_full_a = env.register_test_case "test_alloc_until_full_a" {
    function ()
        env.dut_reset()

        for i = 1, 14 do
            env.negedge()
                expect.equal(alloc.ready:get(), 1)
                alloc.valid:set(1)
                alloc.bits.channel:set(tonumber("001", 2))
                alloc.bits.set:set(i)
                print("successfully alloc", i)
        end
        env.negedge()
            alloc.valid:set(0)

        expect.equal(alloc.ready:get(), 0)
        
        env.posedge(100)
    end
}

local test_alloc_until_full_c = env.register_test_case "test_alloc_until_full_c" {
    function ()
        env.dut_reset()

        for i = 1, 15 do
            env.negedge()
                expect.equal(alloc.ready:get(), 1)
                alloc.valid:set(1)
                alloc.bits.channel:set(tonumber("100", 2))
                alloc.bits.set:set(i)
                print("successfully alloc", i)
        end
        env.negedge()
            alloc.valid:set(0)

        expect.equal(alloc.ready:get(), 0)
        
        env.posedge(100)
    end
}

local test_alloc_until_full_snoop = env.register_test_case "test_alloc_until_full_snoop" {
    function ()
        env.dut_reset()

        for i = 1, 16 do
            env.negedge()
                expect.equal(alloc.ready:get(), 1)
                alloc.valid:set(1)
                alloc.bits.channel:set(tonumber("010", 2))
                alloc.bits.set:set(i)
                print("successfully alloc", i)
        end
        env.negedge()
            alloc.valid:set(0)

        expect.equal(alloc.ready:get(), 0)
        
        env.posedge(100)
    end
}


verilua "appendTasks" {
    main_task = function()
        sim.dump_wave()

        test_basic_alloc()
        test_alloc_until_full_a()
        test_alloc_until_full_c()
        test_alloc_until_full_snoop()
        
        env.posedge(100)
        env.TEST_SUCCESS()
    end
}