local env = require "env"
local expect = env.expect

local dsWrite_s2 = ([[
    | valid
    | set
    | way
    | data
]]):bundle {hier = cfg.top, prefix = "io_dsWrite_s2_", name = "dsWrite_s2"}

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

local test_basic_read_write = env.register_test_case "test_basic_read_write" {
    function ()
        env.dut_reset()

        verilua "appendTasks" {
            check_task = function ()        
                local read_data = 0xff
        
                env.expect_happen_until(100, function (c)
                    return dsResp_ds4:fire()
                end)
                read_data = dsResp_ds4.bits.data:get()[1]
                expect.equal(read_data, 0x00)
                
                env.posedge()
        
                env.expect_happen_until(100, function (c)
                    return dsResp_ds4:fire()
                end)
                read_data = dsResp_ds4.bits.data:get()[1]
                expect.equal(read_data, 0xdead)
            end
        }

        env.negedge()
            -- 
            -- write "0xdead" into set=0x01, way=0x01
            -- 
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

        env.posedge(10)
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

            check_wr_crd = function ()
                sync:wait()
                env.expect_happen_until(30, function ()
                    return wr_crdv:get() == 1
                end)

                env.posedge()
                env.expect_not_happen_until(20, function ()
                    return wr_crdv:get() == 1
                end)
            end
        }

        env.posedge(100)
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()

        test_basic_read_write()
        test_credit_transfer()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}
