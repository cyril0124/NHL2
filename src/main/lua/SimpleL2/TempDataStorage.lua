local env = require "env"
local assert = assert
local expect = env.expect

local tempDS = dut.u_TempDataStorage

local ds_write = ([[
    | valid
    | idx
    | data
]]):bundle{hier = cfg.top, prefix = "io_fromDS_write_s5_", name = "ds_write"}

local to_ds_write = ([[
    | valid
    | data
    | set
    | wayOH
]]):bundle{hier = cfg.top, prefix = "io_toDS_refillWrite_s2_", name = "to_ds_refillWrite_s2"}

local rxdat_write = ([[
    | valid
    | ready
    | idx
    | data
]]):bundle{hier = cfg.top, prefix = "io_fromRXDAT_write_", name = "rxdat_write"}

local sinkC_write = ([[
    | valid
    | ready
    | idx
    | data
]]):bundle{hier = cfg.top, prefix = "io_fromSinkC_write_", name = "sinkC_write"}


local sourceD_resp = ([[
    | valid
    | bits => data
]]):abdl{hier = cfg.top, prefix = "io_toSourceD_data_s2_", is_decoupled = true, name = "sourceD_resp"}

local reqArb_read = ([[
    | valid
    | ready
    | idx
    | dest
]]):bundle{hier = cfg.top, prefix = "io_fromReqArb_read_s1_", is_decoupled = true}


local TXDAT = ("0b0001"):number()
local SourceD = ("0b0010"):number()
local TempDataStorage = ("0b0100"):number()
local DataStorage = ("0b1000"):number()
local nrTempDataEntry = 16

local function read_back_and_check(mshrId, data_str)
    env.negedge()
        reqArb_read.valid:set(1)
        reqArb_read.bits.idx:set(mshrId)
        reqArb_read.bits.dest:set(SourceD)
    env.negedge()
        reqArb_read.valid:set(0)
    env.posedge()
        sourceD_resp.valid:expect(1)
        sourceD_resp:dump()
        expect.equal("0x" .. sourceD_resp.data:get_str(HexStr), data_str)
    env.posedge()
        sourceD_resp.valid:expect(0)
end

local test_basic_write = env.register_test_case "test_rxdat_write" {
    function ()
        env.dut_reset()

        for i = 0, 15 do
            local mshrId = i
            local data = ("0x%02d0000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead"):format(mshrId)
            print("write mshrId" .. mshrId)
            env.negedge()
                rxdat_write.ready:expect(1)
                rxdat_write.valid:set(1)
                rxdat_write.bits.idx:set(mshrId)
                rxdat_write.bits.data:set_str(data)
            env.negedge()
                rxdat_write.valid:set(0)
            print "start read_back_and_check"
            read_back_and_check(mshrId, data)
        end

        for i = 0, 15 do
            local mshrId = i
            local data = ("0x%02d1000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead"):format(mshrId)
            print("write mshrId" .. mshrId)
            env.negedge()
                sinkC_write.ready:expect(1)
                sinkC_write.valid:set(1)
                sinkC_write.bits.idx:set(mshrId)
                sinkC_write.bits.data:set_str(data)
            env.negedge()
                sinkC_write.valid:set(0)
            print "start read_back_and_check"
            read_back_and_check(mshrId, data)
        end

        for i = 0, 15 do
            local mshrId = i
            local data = ("0x%02d2000000000000000000000000000000000000000000000000000000000beef000000000000000000000000000000000000000000000000000000000000dead"):format(mshrId)
            print("write mshrId" .. mshrId)
            env.negedge()
                -- ds_write.ready:expect(1)
                ds_write.valid:set(1)
                ds_write.bits.idx:set(mshrId)
                ds_write.bits.data:set_str(data)
            env.negedge()
            ds_write.valid:set(0)
            print "start read_back_and_check"
            read_back_and_check(mshrId, data)
        end


        env.posedge(100)
    end
}

local test_write_priority = env.register_test_case "test_write_priority" {
    function ()
        env.dut_reset()

        -- DataStorage write should block SinkC and RXDAT write
        env.negedge()
            ds_write.valid:set(1)
            sinkC_write.valid:set(1)
            rxdat_write.valid:set(1)
        env.negedge()
            tempDS.wen_ts1:expect(1)
            sinkC_write.ready:expect(0)
            rxdat_write.ready:expect(0)
            ds_write.valid:set(0)
            sinkC_write.valid:set(0)
            rxdat_write.valid:set(0)

        -- RXDAT write should block SinkC write
        env.negedge()
            sinkC_write.valid:set(1)
            rxdat_write.valid:set(1)
        env.negedge()
            sinkC_write.ready:expect(0)
            rxdat_write.ready:expect(1)
            sinkC_write.valid:set(0)
            rxdat_write.valid:set(0)

        env.posedge(50)
    end
}

local test_write_to_ds = env.register_test_case "test_write_to_ds" {
    function ()
        env.dut_reset()

        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x87654321")
            ds_write.bits.idx:set(0)
        env.negedge()
            ds_write.valid:set(0)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return to_ds_write:fire() and to_ds_write.bits.data:get()[1] == 0x87654321 and to_ds_write.bits.set:get() == 0x10 and to_ds_write.bits.wayOH:get() == 0x01
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
            reqArb_read.bits.idx:set(0)
            reqArb_read.bits.dest:set(DataStorage)
            dut.io_fromReqArb_dsWrSet_s1:set(0x10)
            dut.io_fromReqArb_dsWrWayOH_s1:set(0x01)
        env.negedge()
            reqArb_read.valid:set(0)

        env.posedge(200)
    end
}

local test_write_to_ds_and_sourceD = env.register_test_case "test_write_to_ds_and_sourceD" {
    function ()
        env.dut_reset()

        env.negedge()
            ds_write.valid:set(1)
            ds_write.bits.data:set_str("0x87654321")
            ds_write.bits.idx:set(0)
        env.negedge()
            ds_write.valid:set(0)


        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return to_ds_write:fire() and to_ds_write.bits.data:get()[1] == 0x87654321 and to_ds_write.bits.set:is(0x10) and to_ds_write.bits.wayOH:is(0x01)
                end)
                to_ds_write:dump()

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return to_ds_write:fire()
                end)
            end,
            function ()
                env.expect_happen_until(100, function ()
                    return sourceD_resp.valid:is(1) and sourceD_resp.data:get()[1] == 0x87654321
                end)
                sourceD_resp:dump()

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return sourceD_resp.valid:is(1)
                end)
            end
        }

        env.negedge()
            reqArb_read.ready:expect(1)
            reqArb_read.valid:set(1)
            reqArb_read.bits.idx:set(0)
            reqArb_read.bits.dest:set(DataStorage + SourceD)
            dut.io_fromReqArb_dsWrSet_s1:set(0x10)
            dut.io_fromReqArb_dsWrWayOH_s1:set(0x01)
        env.negedge()
            reqArb_read.valid:set(0)

        env.posedge(200)
    end
}

verilua "appendTasks" {
    maint_task = function ()
        sim.dump_wave()
        env.dut_reset()

        test_basic_write()
        test_write_priority()

        test_write_to_ds()
        test_write_to_ds_and_sourceD()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
