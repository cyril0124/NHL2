local env = require "env"
local expect = env.expect
local format = string.format

local missHandler = dut.u_MissHandler

local alloc = ([[
    | valid
    | ready
    | req_set
    | req_tag
    | req_source
    | req_channel
    | dirResp_meta_clientsOH
]]):bdl {hier = cfg.top, prefix = "io_mshrAlloc_s3_"}

local test_basic_alloc = env.register_test_case "test_basic_alloc" {
    function ()
        env.dut_reset() 

        env.negedge()
            alloc.valid:set(1)
            alloc.bits.req_set:set(0x12)
            alloc.bits.dirResp_meta_clientsOH:set(0x02) -- prevent assert 
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
                alloc.bits.req_channel:set(tonumber("001", 2))
                alloc.bits.req_set:set(i)
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
                alloc.bits.req_channel:set(tonumber("100", 2))
                alloc.bits.req_set:set(i)
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
                alloc.bits.req_channel:set(tonumber("010", 2))
                alloc.bits.req_set:set(i)
                print("successfully alloc", i)
        end
        env.negedge()
            alloc.valid:set(0)

        expect.equal(alloc.ready:get(), 0)
        
        env.posedge(100)
    end
}

local test_resp_rxdat = env.register_test_case "test_resp_rxdat" {
    function ()
        env.dut_reset()

        env.negedge()
            missHandler.io_mshrFreeOH_s3:expect(1)
            alloc.valid:set(1)
            alloc.bits.req_channel:set(tonumber("010", 2))
            alloc.bits.req_set:set(0)
        env.negedge()
            missHandler.io_mshrFreeOH_s3:expect(2)

        env.negedge()
            dut.io_resps_rxdat_valid:set(1)
            dut.io_resps_rxdat_bits_txnID:set(0)
        env.negedge()
            missHandler.mshrs_0.io_resps_rxdat_valid:expect(1)
            dut.io_resps_rxdat_valid:set(0)
        env.negedge()
            missHandler.mshrs_0.io_resps_rxdat_valid:expect(0)

        env.posedge(100)
    end
}


verilua "appendTasks" {
    main_task = function()
        sim.dump_wave()

        dut.io_mshrAlloc_s3_bits_fsmState_s_evict:set(1)

        test_basic_alloc()
        test_alloc_until_full_a()
        test_alloc_until_full_c()
        test_alloc_until_full_snoop()
        test_resp_rxdat()
        
        env.posedge(100)
        env.TEST_SUCCESS()
    end
}