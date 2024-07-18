local env = require "env"
local expect = env.expect

local dsWrite_s2 = ([[
    | valid
    | ready
    | set
    | wayOH
    | data
]]):bundle {hier = cfg.top, prefix = "io_dsWrite_s2_", name = "dsWrite_s2"}

local refillWrite_s2 = ([[
    | valid
    | set
    | wayOH
    | data
]]):bundle {hier = cfg.top, prefix = "io_refillWrite_s2_", name = "refillWrite_s2"}

local dsRead_s3 = ([[
    | valid
    | set
    | wayOH
    | dest
]]):bundle {hier = cfg.top, prefix = "io_fromMainPipe_dsRead_s3_", name = "dsRead_s3"}

local tempDS_write = ([[
    | valid
    | data
    | idx
]]):bundle {hier = cfg.top, prefix = "io_toTempDS_write_s5_", name = "tempDS_write_s5"}

local sourceD_data = ([[
    | valid
    | ready
    | data
]]):bundle {hier = cfg.top, prefix = "io_toSourceD_dsResp_s6s7_", name = "sourceD_data"}

local txdat_data = ([[
    | valid
    | ready
    | data
]]):bundle {hier = cfg.top, prefix = "io_toTXDAT_dsResp_s6s7_", name = "txdat_data"}

local TXDAT = ("0b0001"):number()
local SourceD = ("0b0010"):number()
local TempDataStorage = ("0b0100"):number()

local ds = dut.u_DataStorage

local function refill_data(set, wayOH, data_str)
    env.negedge()
        refillWrite_s2.valid:set(1)
        refillWrite_s2.bits.data:set_str(data_str)
        refillWrite_s2.bits.wayOH:set(wayOH)
        refillWrite_s2.bits.set:set(set)
    env.negedge()
        refillWrite_s2.valid:set(0)
end

local function read(set, wayOH, dest)
    env.negedge()
        dsRead_s3.valid:set(1)
        dsRead_s3.bits.set:set(set)
        dsRead_s3.bits.wayOH:set(wayOH)
        dsRead_s3.bits.dest:set(dest)
    env.negedge()
        dsRead_s3.valid:set(0)
end

local test_basic_read_write = env.register_test_case "test_basic_read_write" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            check_task = function ()        
                local read_data = 0xff

                env.expect_happen_until(100, function (c)
                    return tempDS_write:fire()
                end)
                tempDS_write:dump()
                read_data = tempDS_write.bits.data:get()[1]
                expect.equal(read_data, 0xdead)
                tempDS_write.bits.idx:expect(4)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return tempDS_write:fire()
                end)
            end
        }

        env.negedge()
            -- write "0xdead" into set=0x01, wayOH=0x01
            dsWrite_s2.ready:expect(1)
            dsWrite_s2.valid:set(1)
            dsWrite_s2.bits.set:set(2)
            dsWrite_s2.bits.data:set(0xdead, true)
        env.negedge()
            -- write way is provided in Stage 3
            dut.io_fromMainPipe_dsWrWayOH_s3_bits:set(1)
            dut.io_fromMainPipe_dsWrWayOH_s3_valid:set(1)
            dsWrite_s2.valid:set(0)        
        env.negedge()
            dut.io_fromMainPipe_dsWrWayOH_s3_bits:set(0)
            dut.io_fromMainPipe_dsWrWayOH_s3_valid:set(0)
        env.negedge()
            -- read from set=0x01, wayOH=0x01, mshrId_s3=0x04
            dsRead_s3.valid:set(1)
            dsRead_s3.bits.set:set(2)
            dsRead_s3.bits.wayOH:set(1)
            dsRead_s3.bits.dest:set(TempDataStorage)
            dut.io_fromMainPipe_mshrId_s3:set(4)

        env.negedge()
            dsRead_s3.valid:set(0)

        env.posedge(200)
    end
}

local test_refill_write = env.register_test_case "test_refill_write" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            check_data_resp = function ()
                env.expect_happen_until(100, function()
                    return  tempDS_write.valid:get() == 1 and tempDS_write.bits.data:get()[1] == 0xdead
                end)
                tempDS_write:dump()
            end
        }

        env.negedge()
            refillWrite_s2.valid:set(1)
            refillWrite_s2.bits.data:set_str("0xdead")
            refillWrite_s2.bits.wayOH:set(("0b0010"):number())
            refillWrite_s2.bits.set:set(0x02)
        env.negedge()
            refillWrite_s2.valid:set(0)
        env.negedge(10)
            dsRead_s3.valid:set(1) -- read back
            dsRead_s3.bits.set:set(0x02)
            dsRead_s3.bits.wayOH:set(("0b0010"):number())
            dsRead_s3.bits.dest:set(TempDataStorage)
            dut.io_fromMainPipe_mshrId_s3:set(4)
        env.negedge()
            dsRead_s3.valid:set(0)
            
        env.posedge(100)
    end
}

local test_operate_diffrent_way = env.register_test_case "test_operate_diffrent_way" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return tempDS_write:fire()
                end)
                tempDS_write:dump()
                expect.equal(tempDS_write.bits.data:get()[1], 0)
                expect.equal(tempDS_write.bits.idx:get(), 4)
            end
        }

        env.negedge()
            refillWrite_s2.valid:set(1)
            refillWrite_s2.bits.data:set_str("0xdead")
            refillWrite_s2.bits.wayOH:set(("0b0010"):number())
            refillWrite_s2.bits.set:set(0x03)
            dsRead_s3.valid:set(1)
            dsRead_s3.bits.set:set(0x03)
            dsRead_s3.bits.wayOH:set(("0b0001"):number())
            dsRead_s3.bits.dest:set(TempDataStorage)
            dut.io_fromMainPipe_mshrId_s3:set(4)
        env.negedge()
            refillWrite_s2.valid:set(0)
            dsRead_s3.valid:set(0)

        env.posedge(100)
    end
}

local test_read_to_sourceD = env.register_test_case "test_read_to_sourceD" {
    function ()

        local function read_to_sourced(set, wayOH)
            env.negedge()
                dsRead_s3.valid:set(1)
                dsRead_s3.bits.set:set(set)
                dsRead_s3.bits.wayOH:set(wayOH)
                dsRead_s3.bits.dest:set(SourceD)
            env.negedge()
                dsRead_s3.valid:set(0)
        end

        env.dut_reset()

        sourceD_data.ready:set(1)

        refill_data(0x00, ("0b0001"):number(), "0xdead")
        refill_data(0x00, ("0b0010"):number(), "0xbeef")
        refill_data(0x00, ("0b0100"):number(), "0xabab")

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return sourceD_data:fire() and sourceD_data.bits.data:get()[1] == 0xdead
                end)
                -- sourceD_data:dump()

                env.negedge()
                env.expect_happen_until(100, function ()
                    return sourceD_data:fire() and sourceD_data.bits.data:get()[1] == 0xbeef
                end)
                -- sourceD_data:dump()
            end
        }

        -- normal read(no stall)
        read_to_sourced(0x00, ("0b0001"):number())
        read_to_sourced(0x00, ("0b0010"):number())

        env.negedge(10)

        -- stall(two)
        sourceD_data.ready:set(0)
        read_to_sourced(0x00, ("0b0001"):number())
        read_to_sourced(0x00, ("0b0010"):number())

        env.negedge(10, function ()
            expect.equal(sourceD_data.bits.data:get()[1], 0xdead)
            sourceD_data.valid:expect(1)
            sourceD_data.ready:expect(0)
        end)

        env.negedge()
            sourceD_data.ready:set(1)
        env.posedge()
            expect.equal(sourceD_data.bits.data:get()[1], 0xdead)
            sourceD_data.valid:expect(1)
            sourceD_data.ready:expect(1)
        env.posedge()
            expect.equal(sourceD_data.bits.data:get()[1], 0xbeef)
            sourceD_data.valid:expect(1)
            sourceD_data.ready:expect(1)
        env.posedge()
        env.posedge(10, function ()
            sourceD_data.valid:expect(0)
        end)


        -- stall(three)
        -- sourceD_data.ready:set(0)
        -- read_to_sourced(0x00, ("0b0001"):number())
        -- read_to_sourced(0x00, ("0b0010"):number())
        -- read_to_sourced(0x00, ("0b0100"):number())

        -- env.negedge(10, function ()
        --     expect.equal(sourceD_data.bits.data:get()[1], 0xdead)
        --     sourceD_data.valid:expect(1)
        --     sourceD_data.ready:expect(0)
        -- end)

        -- env.negedge()
        --     sourceD_data.ready:set(1)
        -- env.posedge()
        --     expect.equal(sourceD_data.bits.data:get()[1], 0xdead)
        --     sourceD_data.valid:expect(1)
        --     sourceD_data.ready:expect(1)
        -- env.posedge()
        --     expect.equal(sourceD_data.bits.data:get()[1], 0xbeef)
        --     sourceD_data.valid:expect(1)
        --     sourceD_data.ready:expect(1)
        -- env.posedge()
        -- env.posedge(10, function ()
        --     sourceD_data.valid:expect(0)
        -- end)

        env.posedge(100)
    end
}

local test_read_stall = env.register_test_case "test_read_stall" {
    function ()
        env.dut_reset()
    
        sourceD_data.ready:set(0)

        refill_data(0x00, ("0b0001"):number(), "0xabcd")
        refill_data(0x00, ("0b0010"):number(), "0xbeef")

        -- one read request
        read(0x00, ("0b0001"):number(), SourceD)
        env.negedge()
            ds.ren_s5:expect(1)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(0)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(0)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge(math.random(1, 10))
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.ready:set(1)
        env.posedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s7:expect(0)
            sourceD_data.valid:expect(0)
        
        env.negedge(10)

        sourceD_data.ready:set(0)

        -- two read request
        read(0x00, ("0b0001"):number(), SourceD)
        read(0x00, ("0b0010"):number(), SourceD)
        env.negedge()
            ds.ren_s5:expect(1)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge(math.random(1, 10))
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            sourceD_data.ready:set(1)
        env.posedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(0)
            sourceD_data.valid:expect(1)
            expect.equal(sourceD_data.bits.data:get()[1], 0xbeef)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(0)
            sourceD_data.valid:expect(0)

        env.posedge(100)
    end
}

local test_drop_overflow_data = env.register_test_case "test_drop_overflow_data" {
    function ()
        env.dut_reset()

        sourceD_data.ready:set(0)

        refill_data(0x00, ("0b0001"):number(), "0xabcd")
        refill_data(0x00, ("0b0010"):number(), "0xbeef")
        refill_data(0x00, ("0b0100"):number(), "0xabab")

        read(0x00, ("0b0001"):number(), SourceD)
        read(0x00, ("0b0010"):number(), SourceD)
        env.negedge()
            ds.ren_s5:expect(1)
            ds.ren_s6:expect(0)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(ds.rdData_s7:get(), 0xabcd)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge()
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(ds.rdData_s6:get(), 0xbeef)
            expect.equal(ds.rdData_s7:get(), 0xabcd)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)
        env.negedge(math.random(5, 10))
            ds.ren_s5:expect(0)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(ds.rdData_s6:get(), 0xbeef)
            expect.equal(ds.rdData_s7:get(), 0xabcd)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)

        read(0x00, ("0b0100"):number(), SourceD)
        env.negedge()
            ds.ren_s5:expect(1)
            ds.ren_s6:expect(1)
            ds.ren_s7:expect(1)
            sourceD_data.valid:expect(1)
            expect.equal(ds.rdData_s6:get(), 0xbeef)
            expect.equal(ds.rdData_s7:get(), 0xabcd)
            expect.equal(sourceD_data.bits.data:get()[1], 0xabcd)


        env.posedge(100)
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()

        test_basic_read_write()
        test_refill_write()
        test_operate_diffrent_way()
        test_read_to_sourceD()
        test_read_stall()
        -- test_drop_overflow_data()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
