local env = require "env"
local expect = env.expect

local dsWrite_s2 = ([[
    | valid
    | ready
    | set
    | way
    | data
]]):bundle {hier = cfg.top, prefix = "io_dsWrite_s2_", name = "dsWrite_s2"}

local refillWrite = ([[
    | valid
    | crdv
    | bits_set => set
    | bits_way => way
    | bits_data => data
]]):abdl {hier = cfg.top, prefix = "io_refillWrite_", name = "refillWrite"}

local dsRead_s3 = ([[
    | valid
    | set
    | way
    | dest
]]):bundle {hier = cfg.top, prefix = "io_dsRead_s3_", name = "dsRead_s3"}

local dsResp_ds4 = ([[
    | valid
    | data
]]):bundle {hier = cfg.top, prefix = "io_toTempDS_dsResp_ds4_", name = "dsResp_ds4"}

local TXDAT = ("0b0001"):number()
local SourceD = ("0b0010"):number()
local TempDataStorage = ("0b0100"):number()

local dsWrWay_s3 = dut.io_dsWrWay_s3
local rd_crdv = dut.io_dsRead_s3_crdv
local wr_crdv = dut.io_dsWrite_s2_crdv
local ds = dut.u_DataStorage

local test_basic_read_write = env.register_test_case "test_basic_read_write" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            check_task = function ()        
                local read_data = 0xff
        
                env.expect_happen_until(100, function (c)
                    return dsResp_ds4:fire()
                end)
                dsResp_ds4:dump()
                read_data = dsResp_ds4.bits.data:get()[1]
                expect.equal(read_data, 0x00)
                
                env.posedge()
                env.expect_happen_until(100, function (c)
                    return dsResp_ds4:fire()
                end)
                dsResp_ds4:dump()
                read_data = dsResp_ds4.bits.data:get()[1]
                expect.equal(read_data, 0xdead)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return dsResp_ds4:fire()
                end)
            end
        }

        env.negedge()
            -- 
            -- write "0xdead" into set=0x01, way=0x01
            -- 
            dsWrite_s2.ready:expect(1)
            dsWrite_s2.valid:set(1)
            dsWrite_s2.bits.set:set(2)
            dsWrite_s2.bits.data:set(0xdead, true)

            -- 
            -- [1] read from set=0x01, way=0x01
            -- 
            dsRead_s3.valid:set(1)
            dsRead_s3.bits.set:set(2)
            dsRead_s3.bits.way:set(1)
            dsRead_s3.bits.dest:set(SourceD)

        env.negedge()
            -- 
            -- write way is provided in Stage 3
            -- 
            dsWrWay_s3:set(1)
            dsWrite_s2.valid:set(0)
            
            dsRead_s3.valid:set(0)
        
        env.negedge()
            dsWrWay_s3:set(0)
            
            -- 
            -- [2] read from set=0x01, way=0x01
            -- 
            dsRead_s3.valid:set(1)
            dsRead_s3.bits.set:set(2)
            dsRead_s3.bits.way:set(1)
            dsRead_s3.bits.dest:set(SourceD)

        env.negedge()
            dsRead_s3.valid:set(0)

        env.posedge(100)
    end
}

local test_credit_transfer = env.register_test_case "test_credit_transfer" {
    function ()
        local sync = ("sync"):ehdl()

        env.dut_reset()
        sync:send()

        verilua "appendTasks" {
            check_rd_crd = function ()
                env.expect_happen_until(20, function (c)
                    return rd_crdv:get() == 1
                end)

                env.posedge()
                env.expect_happen_until(20, function (c)
                    return rd_crdv:get() == 1
                end)

                env.posedge()
                env.expect_not_happen_until(20, function (c)
                    return rd_crdv:get() == 1
                end)
            end,
        }

        env.posedge(100)
    end
}

local test_refill_write = env.register_test_case "test_refill_write" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return refillWrite.crdv:get() == 1
                end)
                refillWrite:dump()
            end,
            check_data_resp = function ()
                env.expect_happen_until(100, function()
                    return  dsResp_ds4.valid:get() == 1 and dsResp_ds4.bits.data:get()[1] == 0xdead
                end)
                dsResp_ds4:dump()
            end
        }

        env.negedge()
            refillWrite.valid:set(1)
            refillWrite.data:set_str("0xdead")
            refillWrite.way:set(("0b0010"):number())
            refillWrite.set:set(0x02)
        env.negedge()
            refillWrite.valid:set(0)
            dsRead_s3.valid:set(1) -- read back
            dsRead_s3.bits.set:set(0x02)
            dsRead_s3.bits.way:set(("0b0010"):number())
            dsRead_s3.bits.dest:set(SourceD)
        env.negedge()
            dsRead_s3.valid:set(0)
            
        env.posedge(100)
    end
}

local test_write_priority = env.register_test_case "test_write_priority" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return refillWrite.crdv:get() == 1
                end)
                refillWrite:dump()
            end
        }

        env.negedge()
            refillWrite.valid:set(1)
            dsWrite_s2.valid:set(1)
        env.negedge()
            ds.fire_refill_ds1:expect(1)
            ds.fire_ds1:expect(0)
            refillWrite.valid:set(0)
            dsWrite_s2.valid:set(0)
        env.negedge(2)
            ds.fire_refill_ds1:expect(0)
            ds.fire_ds1:expect(1)

        env.posedge(100)
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()

        test_basic_read_write()
        test_credit_transfer()
        test_refill_write()
        test_write_priority()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
