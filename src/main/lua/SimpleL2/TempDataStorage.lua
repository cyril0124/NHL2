local env = require "env"
local assert = assert
local expect = env.expect

local tempDS = dut.u_TempDataStorage

local ds_write = ([[
    | valid
    | data
]]):bundle{hier = cfg.top, prefix = "io_fromDS_dsResp_ds4_", name = "ds_write"}

local to_ds_write = ([[
    | valid
    | data
    | set
    | way
]]):bundle{hier = cfg.top, prefix = "io_toDS_dsWrite_", name = "to_ds_write"}

local rxdat_write = ([[
    | valid
    | ready
    | wrMaskOH
    | dataId
    | beatData
]]):bundle{hier = cfg.top, prefix = "io_fromRXDAT_write_", name = "rxdat_write"}

local sinkC_write = ([[
    | valid
    | ready
    | wrMaskOH
    | dataId
    | beatData
]]):bundle{hier = cfg.top, prefix = "io_fromSinkC_write_", name = "sinkC_write"}


local sourceD_read = ([[
    | valid
    | ready
    | dataId
]]):bundle{hier = cfg.top, prefix = "io_fromSourceD_read_", is_decoupled = true}

local sourceD_resp = ([[
    | valid
    | bits_data => data
]]):abdl{hier = cfg.top, prefix = "io_fromSourceD_resp_", is_decoupled = false, name = "sourceD_resp"}

local reqArb_read = ([[
    | valid
    | ready
    | dataId
    | dest
]]):bundle{hier = cfg.top, prefix = "io_fromReqArb_read_", is_decoupled = true}

local flush = ([[
    | valid
    | bits => dataId
]]):abdl{hier = cfg.top, prefix = "io_flushEntry_", is_decoupled = false}

local dataOut = ([[
    | valid
    | ready
    | data
    | last
]]):bundle{hier = cfg.top, prefix = "io_toSourceD_beatData_", name = "dataOut"}


local TXDAT = ("0b0001"):number()
local SourceD = ("0b0010"):number()
local TempDataStorage = ("0b0100"):number()
local DataStorage = ("0b1000"):number()
local nrTempDataEntry = 16

local function read_back_and_check(dataId, data_str)
    dut.io_toSourceD_beatData_ready:set(1)
    env.negedge()
        sourceD_read.valid:set(1)
        sourceD_read.bits.dataId:set(dataId)
    env.negedge()
        sourceD_read.valid:set(0)
    env.posedge()
        sourceD_resp.valid:expect(1)
        sourceD_resp:dump()
        expect.equal(sourceD_resp.data:get_str(HexStr), data_str)
    env.posedge()
        sourceD_resp.valid:expect(0)
end

local test_basic_ds_read_write = env.register_test_case "test_basic_ds_read_write" {
    function ()
        env.dut_reset() 

        -- 
        -- ds_write data
        -- 
        print "ds_write data"
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)

        expect.equal(tempDS.wen_ts1:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 1)

        -- 
        -- sourceD_read back data
        -- 
        print "sourceD_read back data"
        env.negedge()
            sourceD_read.valid:set(1)
            sourceD_read.bits.dataId:set(0)
        env.negedge()
            sourceD_read.valid:set(0)
        
        env.posedge()
            expect.equal(sourceD_resp.valid:get(), 1)
            expect.equal(sourceD_resp.data:get()[1], 0x12345678)

        -- 
        -- check data entry
        -- 
        print "check data entry"
        env.negedge()
            tempDS.valids_0_0:expect(0)
            tempDS.valids_0_1:expect(0)
        
        -- 
        -- ds_write two entry data
        -- 
        print "ds_write two entry data"
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)
        
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x87654321")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)
            

        expect.equal(tempDS.wen_ts1:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 2)

        -- 
        -- continuous sourceD_read
        -- 
        print "continuous sourceD_read"
        verilua "appendTasks" {
            check_task = function()
                local first_resp_cycle = 0
                local second_resp_cycle = 0
                env.expect_happen_until(5, function (c)
                    return sourceD_resp.valid:get() == 1 and sourceD_resp.data:get()[1] == 0x87654321
                end)
                first_resp_cycle = env.cycles()

                env.expect_happen_until(5, function (c)
                    return sourceD_resp.valid:get() == 1 and sourceD_resp.data:get()[1] == 0x12345678
                end)
                second_resp_cycle = env.cycles()

                expect.equal(second_resp_cycle, first_resp_cycle + 1)
            end
        }
        env.negedge()
            sourceD_read.valid:set(1)
            sourceD_read.bits.dataId:set(1)
        env.negedge()
            sourceD_read.bits.dataId:set(0)
        env.negedge()
            sourceD_read.valid:set(0)
            tempDS.freeDataIdx:expect(1)
        env.negedge()
            tempDS.freeDataIdx:expect(0)

        env.posedge(100)
    end
}

local test_flush_entry = env.register_test_case "test_flush_entry" {
    function ()
        env.dut_reset()

        -- 
        -- ds_write data
        -- 
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)

        expect.equal(tempDS.wen_ts1:get(), 1)
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
                -- sourceD_read back data
                -- 
                env.negedge()
                    sourceD_read.valid:set(1)
                    sourceD_read.bits.dataId:set(0)
                env.negedge()
                    sourceD_read.valid:set(0)

                env.posedge()
                    expect.equal(sourceD_resp.data:get()[1], 0x12345678)
            end,
        }

        -- 
        -- ds_write data
        -- 
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)

        expect.equal(tempDS.wen_ts1:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 1)

        sync_ehdl:send()

        env.posedge(100)
    end
}

local test_ds_write_until_full = env.register_test_case "test_ds_write_until_full" {
    function ()
        env.dut_reset()

        -- 
        -- ds_write data
        -- 
        for i = 1, nrTempDataEntry do
            env.negedge()
                expect.equal(tempDS.freeDataIdx:get(), i - 1)
                ds_write.valid:set(1)
                ds_write.bits.data:set_str("0x1234567" .. i)
                dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        end

        env.negedge()
            ds_write.valid:set(0)

        for i = 1, 10 do
            expect.equal(tempDS.full:get(), 1)
            env.posedge()
        end
    end
}

local test_bypass_sourceD = env.register_test_case "test_bypass_sourceD" {
    function ()
        env.dut_reset()

        dut.io_toSourceD_beatData_ready:set(1)

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
        -- ds_write data
        -- 
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(SourceD)
        env.negedge()
            ds_write.valid:set(0)

        expect.equal(tempDS.wen_ts1:get(), 0)

        env.posedge(100)
    end
}

local test_write_on_stalled_sourceD = env.register_test_case "test_write_on_stalled_sourceD" {
    function ()
        env.dut_reset()

        dut.io_toSourceD_beatData_ready:set(0)

        -- 
        -- ds_write data
        -- 
        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x12345678")
            dut.io_fromDS_dsDest_ds4:set(SourceD)
        env.negedge()
            ds_write.valid:set(0)
        
        expect.equal(tempDS.wen_ts1:get(), 1)
        expect.equal(tempDS.freeDataIdx:get(), 0)

        -- 
        -- sourceD_read back data
        -- 
        env.negedge()
            sourceD_read.valid:set(1)
            sourceD_read.bits.dataId:set(0)
        env.negedge()
            sourceD_read.valid:set(0)

        env.posedge()
            expect.equal(sourceD_resp.data:get()[1], 0x12345678)

        env.posedge(100)
    end
}

local test_rxdat_write = env.register_test_case "test_rxdat_write" {
    function ()
        env.dut_reset()

        -- 
        -- continuous write
        -- 
        print "continuous write"
        env.negedge()
            rxdat_write.ready:expect(1)
            rxdat_write.valid:set(1)
            rxdat_write.bits.dataId:set(0)
            rxdat_write.bits.beatData:set_str("0xdead")
            rxdat_write.bits.wrMaskOH:set(0x01)
        env.negedge()
            rxdat_write.bits.beatData:set_str("0xbeef")
            rxdat_write.bits.wrMaskOH:set(0x02)
            tempDS.valids_0_0:expect(1)
        env.negedge()
            rxdat_write.valid:set(0)
            tempDS.valids_0_1:expect(1)
        print "start read_back_and_check"
        read_back_and_check(0, "000000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead")
        
        -- 
        -- non-continuous write
        --
        print "non-continuous write"
        env.negedge()
            rxdat_write.ready:expect(1)
            rxdat_write.bits.dataId:set(1)
            rxdat_write.bits.beatData:set_str("0xdddd")
            rxdat_write.bits.wrMaskOH:set(0x01)
            rxdat_write.valid:set(1)
        env.negedge()
            tempDS.valids_1_0:expect(1)
            tempDS.valids_1_1:expect(0)
            rxdat_write.valid:set(0)
        env.negedge(math.random(5, 20))
            tempDS.valids_1_0:expect(1)
            rxdat_write.bits.beatData:set_str("0xbbbb")
            rxdat_write.bits.wrMaskOH:set(0x02)
            rxdat_write.valid:set(1)
        env.negedge()
            rxdat_write.valid:set(0)
            tempDS.valids_1_0:expect(1)
            tempDS.valids_1_1:expect(1)
        print "start read_back_and_check"
        read_back_and_check(1, "000000000000000000000000000000000000000000000000000000000000bbbb000000000000000000000000000000000000000000000000000000000000dddd")

        env.posedge(100)
    end
}

local test_sinkc_write = env.register_test_case "test_sinkc_write" {
    function ()
        env.dut_reset()

        -- 
        -- continuous write
        -- 
        print "continuous write"
        env.negedge()
            sinkC_write.ready:expect(1)
            sinkC_write.valid:set(1)
            sinkC_write.bits.dataId:set(0)
            sinkC_write.bits.beatData:set_str("0xdead")
            sinkC_write.bits.wrMaskOH:set(0x01)
        env.negedge()
            sinkC_write.bits.beatData:set_str("0xbeef")
            sinkC_write.bits.wrMaskOH:set(0x02)
            tempDS.valids_0_0:expect(1)
        env.negedge()
            sinkC_write.valid:set(0)
            tempDS.valids_0_1:expect(1)
        print "start read_back_and_check"
        read_back_and_check(0, "000000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead")
        
        -- 
        -- non-continuous write
        --
        print "non-continuous write"
        env.negedge()
            sinkC_write.ready:expect(1)
            sinkC_write.bits.dataId:set(1)
            sinkC_write.bits.beatData:set_str("0xdddd")
            sinkC_write.bits.wrMaskOH:set(0x01)
            sinkC_write.valid:set(1)
        env.negedge()
            tempDS.valids_1_0:expect(1)
            sinkC_write.valid:set(0)
        env.negedge(math.random(5, 20))
            tempDS.valids_1_0:expect(1)
            sinkC_write.bits.beatData:set_str("0xbbbb")
            sinkC_write.bits.wrMaskOH:set(0x02)
            sinkC_write.valid:set(1)
        env.negedge()
            sinkC_write.valid:set(0)
            tempDS.valids_1_1:expect(1)
        print "start read_back_and_check"
        read_back_and_check(1, "000000000000000000000000000000000000000000000000000000000000bbbb000000000000000000000000000000000000000000000000000000000000dddd")
                sinkC_write.bits.dataId:set(0)

        env.posedge(100)
    end
}

local test_write_priority = env.register_test_case "test_write_priority" {
    function ()
        env.dut_reset()

        -- DataStorage write should block SinkC and RXDAT write
        env.negedge()
            ds_write.valid:set(1)
                dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
            sinkC_write.valid:set(1)
                sinkC_write.bits.wrMaskOH:set(0x01)
                sinkC_write.bits.dataId:set(0)
            rxdat_write.valid:set(1)
                rxdat_write.bits.wrMaskOH:set(0x01)
                rxdat_write.bits.dataId:set(0)
        env.negedge()
            sinkC_write.ready:expect(0)
            rxdat_write.ready:expect(0)
            ds_write.valid:set(0)
            sinkC_write.valid:set(0)
            rxdat_write.valid:set(0)

        -- RXDAT write should block SinkC write
        env.negedge()
            sinkC_write.valid:set(1)
                sinkC_write.bits.wrMaskOH:set(0x01)
                sinkC_write.bits.dataId:set(1)
            rxdat_write.valid:set(1)
                rxdat_write.bits.wrMaskOH:set(0x01)
                rxdat_write.bits.dataId:set(1)
        env.negedge()
            sinkC_write.ready:expect(0)
            rxdat_write.ready:expect(1)
            sinkC_write.valid:set(0)
            rxdat_write.valid:set(0)

        -- SourceD write should block RXDAT and SinkC write
        dut.io_toSourceD_beatData_ready:set(0)
        env.negedge()
            ds_write.valid:set(1)
                dut.io_fromDS_dsDest_ds4:set(SourceD)
            sinkC_write.valid:set(1)
                sinkC_write.bits.wrMaskOH:set(0x01)
                sinkC_write.bits.dataId:set(2)
            rxdat_write.valid:set(1)
                rxdat_write.bits.wrMaskOH:set(0x01)
                rxdat_write.bits.dataId:set(2)
        env.negedge()
            tempDS.stallOnSourceD_ts1:expect(1)
            sinkC_write.ready:expect(0)
            rxdat_write.ready:expect(0)
            ds_write.valid:set(0)
            rxdat_write.valid:set(0)
            sinkC_write.valid:set(0)

        env.posedge(50)
    end
}

local test_write_to_ds = env.register_test_case "test_write_to_ds" {
    function ()
        env.dut_reset()

        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x87654321")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return to_ds_write:fire() and to_ds_write.bits.data:get()[1] == 0x87654321 and to_ds_write.bits.set:get() == 0x10 and to_ds_write.bits.way:get() == 0x01
                end)
                to_ds_write:dump()

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return to_ds_write:fire()
                end)
            end
        }

        env.negedge()
            reqArb_read.ready:expect(1)
            reqArb_read.valid:set(1)
            reqArb_read.bits.dataId:set(0)
            reqArb_read.bits.dest:set(DataStorage)
            dut.io_fromReqArb_dsWrSet:set(0x10)
            dut.io_fromReqArb_dsWrWay:set(0x01)
        env.negedge()
            reqArb_read.valid:set(0)

        env.posedge(200)
    end
}

local test_write_to_ds_and_sourceD = env.register_test_case "test_write_to_ds_and_sourceD" {
    function ()
        env.dut_reset()

        dut.io_toSourceD_beatData_ready:set(1)

        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x87654321")
            dut.io_fromDS_dsDest_ds4:set(TempDataStorage)
        env.negedge()
            ds_write.valid:set(0)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return to_ds_write:fire() and to_ds_write.bits.data:get()[1] == 0x87654321 and to_ds_write.bits.set:get() == 0x10 and to_ds_write.bits.way:get() == 0x01
                end)
                to_ds_write:dump()

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return to_ds_write:fire()
                end)
            end,
            function ()
                env.expect_happen_until(100, function ()
                    return dataOut:fire() and dataOut.bits.data:get()[1] == 0x87654321 and dataOut.bits.last:get() == 0
                end)
                dataOut:dump()

                env.posedge()
                env.expect_happen_until(100, function ()
                    return dataOut:fire() and dataOut.bits.data:get()[1] == 0 and dataOut.bits.last:get() == 1
                end)
                dataOut:dump()

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return dataOut:fire()
                end)
            end
        }

        env.negedge()
            reqArb_read.ready:expect(1)
            reqArb_read.valid:set(1)
            reqArb_read.bits.dataId:set(0)
            reqArb_read.bits.dest:set(DataStorage + SourceD)
            dut.io_fromReqArb_dsWrSet:set(0x10)
            dut.io_fromReqArb_dsWrWay:set(0x01)
        env.negedge()
            reqArb_read.valid:set(0)

        env.posedge(200)
    end
}

local test_pre_alloc = env.register_test_case "test_pre_alloc" {
    function ()
        env.dut_reset()

        for i = 0, nrTempDataEntry - 1 do
            print("preAlloc " .. i)
            env.negedge()
                dut.io_preAlloc:set(1)
                tempDS.freeDataIdx:expect(i)
                tempDS["valids_" .. i .. "_0"]:expect(0)
                tempDS["valids_" .. i .. "_1"]:expect(0)
                tempDS["preAllocs_" .. i]:expect(0)
            env.negedge()
                dut.io_preAlloc:set(0)
                tempDS.preAllocFull:dump()
                tempDS.freeDataIdx:_if(tempDS.preAllocFull:is(0)):expect(i + 1)
                tempDS["valids_" .. i .. "_0"]:expect(0)
                tempDS["valids_" .. i .. "_1"]:expect(0)
                tempDS["preAllocs_" .. i]:expect(1)
        end

        for i = 0, nrTempDataEntry - 1 do
            print("sinkC write " .. i)
            env.negedge()
                sinkC_write.ready:expect(1)
                sinkC_write.valid:set(1)
                sinkC_write.bits.dataId:set(i)
                sinkC_write.bits.beatData:set_str("0xdead")
                sinkC_write.bits.wrMaskOH:set(0x01)
                tempDS["valids_" .. i .. "_0"]:expect(0)
                tempDS["valids_" .. i .. "_1"]:expect(0)
            env.negedge()
                sinkC_write.bits.beatData:set_str("0xbeef")
                sinkC_write.bits.wrMaskOH:set(0x02)
                tempDS["valids_" .. i .. "_0"]:expect(1)
                tempDS["valids_" .. i .. "_1"]:expect(0)
            env.negedge()
                sinkC_write.valid:set(0)
                tempDS["valids_" .. i .. "_0"]:expect(1)
                tempDS["valids_" .. i .. "_1"]:expect(1)
            read_back_and_check(i, "000000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead")
            
            env.negedge(2)
        end

        env.posedge(200)
    end
}

verilua "appendTasks" {
    maint_task = function ()
        sim.dump_wave()
        env.dut_reset()

        test_flush_entry()
        test_read_and_flush_entry()
        test_bypass_sourceD()

        test_basic_ds_read_write()
        test_ds_write_until_full()
        test_write_on_stalled_sourceD()
        test_rxdat_write()
        test_sinkc_write()

        test_write_priority()

        test_write_to_ds()
        test_write_to_ds_and_sourceD()

        test_pre_alloc()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
