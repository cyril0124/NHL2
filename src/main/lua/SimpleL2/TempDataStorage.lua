local env = require "env"
local assert = assert
local expect = env.expect

local tempDS = dut.u_TempDataStorage

local write = ([[
    | valid
    | data
]]):bundle{hier = cfg.top, prefix = "io_fromDS_dsResp_ds4_"}

local read = ([[
    | valid
    | ready
    | dataId
]]):bundle{hier = cfg.top, prefix = "io_fromSourceD_read_", is_decoupled = false}

local resp = ([[
    | valid
    | bits => data
]]):abdl{hier = cfg.top, prefix = "io_fromSourceD_resp_", is_decoupled = false}

local flush = ([[
    | valid
    | bits => dataId
]]):abdl{hier = cfg.top, prefix = "io_flushEntry_", is_decoupled = false}

local dataOut = ([[
    | valid
    | ready
    | data
    | last
]]):bundle{hier = cfg.top, prefix = "io_toSourceD_dataOut_"}


local TXDAT = 0
local SourceD = 1
local TempDataStorage = 2
local nrTempDataEntry = 16

local test_basic_read_write = env.register_test_case "test_basic_read_write" {
    function ()
        env.dut_reset() 

        -- 
        -- write data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            write.valid:set(0)

        expect.equal(tempDS.wen:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 1)

        -- 
        -- read back data
        -- 
        env.negedge()
            read.valid:set(1)
            read.dataId:set(0)
        env.negedge()
            read.valid:set(0)
        
        env.posedge()
            expect.equal(resp.valid:get(), 1)
            expect.equal(resp.data:get()[1], 0x12345678)

        -- 
        -- read again
        -- 
        env.negedge()
            read.valid:set(1)
            read.dataId:set(0)
        env.negedge()
            read.valid:set(0)

        env.posedge()
            expect.equal(resp.valid:get(), 1)
            expect.equal(resp.data:get()[1], 0x12345678)

        -- 
        -- write another data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x87654321")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            write.valid:set(0)

        expect.equal(tempDS.wen:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 2)

        -- 
        -- continuous read
        -- 
        verilua "appendTasks" {
            check_task = function()
                local first_resp_cycle = 0
                local second_resp_cycle = 0
                env.expect_happen_until(5, function (c)
                    return resp.valid:get() == 1 and resp.data:get()[1] == 0x87654321
                end)
                first_resp_cycle = env.cycles()

                env.expect_happen_until(5, function (c)
                    return resp.valid:get() == 1 and resp.data:get()[1] == 0x12345678
                end)
                second_resp_cycle = env.cycles()

                expect.equal(second_resp_cycle, first_resp_cycle + 1)
            end
        }
        env.negedge()
            read.valid:set(1)
            read.dataId:set(1)
        env.negedge()
            read.dataId:set(0)
        env.negedge()
            read.valid:set(0)

        expect.equal(tempDS.freeDataIdx:get(), 2)

        env.posedge(100)
    end
}

local test_flush_entry = env.register_test_case "test_flush_entry" {
    function ()
        env.dut_reset()

        -- 
        -- write data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            write.valid:set(0)

        expect.equal(tempDS.wen:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 1)

        -- 
        -- flush entry
        --
        env.negedge() 
            flush.valid:set(1)
            flush.dataId:set(0)
        env.negedge()
            flush.valid:set(0)
        
        expect.equal(tempDS.freeDataIdx:get(), 0)
        

        env.posedge(100)
    end
}

local test_read_and_flush_entry = env.register_test_case "test_read_and_flush_entry" {
    function ()
        env.dut_reset()

        local sync_ehdl = ("sync"):ehdl()
        verilua "appendTasks" {
            flush_task = function()
                sync_ehdl:wait()

                -- 
                -- flush entry
                --
                env.negedge() 
                    flush.valid:set(1)
                    flush.dataId:set(0)
                env.negedge()
                    flush.valid:set(0)
                
                expect.equal(tempDS.freeDataIdx:get(), 0)
            end,

            read_task = function ()
                sync_ehdl:wait()
                -- 
                -- read back data
                -- 
                env.negedge()
                    read.valid:set(1)
                    read.dataId:set(0)
                env.negedge()
                    read.valid:set(0)

                env.posedge()
                    expect.equal(resp.data:get()[1], 0x12345678)
            end,
        }

        -- 
        -- write data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            write.valid:set(0)

        expect.equal(tempDS.wen:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 1)

        sync_ehdl:send()

        env.posedge(100)
    end
}

local test_write_until_full = env.register_test_case "test_write_until_full" {
    function ()
        env.dut_reset()

        -- 
        -- write data
        -- 
        for i = 1, nrTempDataEntry do
            env.negedge()
                expect.equal(tempDS.freeDataIdx:get(), i - 1)
                write.valid:set(1)
                write.bits.data:set_str("0x1234567" .. i)
                dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        end

        env.negedge()
            write.valid:set(0)

        for i = 1, 10 do
            expect.equal(tempDS.full:get(), 1)
            env.posedge()
        end
    end
}

local test_bypass_sourceD = env.register_test_case "test_bypass_sourceD" {
    function ()
        env.dut_reset()

        dut.io_toSourceD_dataOut_ready:set(1)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(10, function(c)
                    return dataOut:fire() and dataOut.bits.last:get() == 0 and dataOut.bits.data:get()[1] == 0x12345678
                end)

                env.expect_happen_until(10, function(c)
                    return dataOut:fire() and dataOut.bits.last:get() == 1 and dataOut.bits.data:get()[1] == 0x0
                end)
            end
        }

        -- 
        -- write data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(SourceD)
        env.negedge()
            write.valid:set(0)

        expect.equal(tempDS.wen:get(), 0)

        env.posedge(100)
    end
}

local test_write_on_stalled_sourceD = env.register_test_case "test_write_on_stalled_sourceD" {
    function ()
        env.dut_reset()

        dut.io_toSourceD_dataOut_ready:set(0)

        -- 
        -- write data
        -- 
        env.negedge()
            write.valid:set(1)
            write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(SourceD)
        env.negedge()
            write.valid:set(0)
        
        expect.equal(tempDS.wen:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 0)

        -- 
        -- read back data
        -- 
        env.negedge()
            read.valid:set(1)
            read.dataId:set(0)
        env.negedge()
            read.valid:set(0)

        env.posedge()
            expect.equal(resp.data:get()[1], 0x12345678)

        env.posedge(100)
    end
}

verilua "appendTasks" {
    maint_task = function ()
        sim.dump_wave()
        env.dut_reset()

        test_basic_read_write()
        test_flush_entry()
        test_read_and_flush_entry()
        test_write_until_full()
        test_bypass_sourceD()
        test_write_on_stalled_sourceD()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
