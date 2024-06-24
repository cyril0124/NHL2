local env = require "env"
local expect = env.expect

local test_wrdata_no_stall = env.register_test_case "test_wrdata_no_stall" {
    function ()
        env.dut_reset()

        dut.io_toTempDS_dataWr_ready:set(1)
        dut.io_toTempDS_dataId:set(10)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(10, function ()
                    return dut.io_toTempDS_dataWr_valid:get() == 1 and 
                            dut.io_toTempDS_dataWr_bits_dataId:get() == 10 and
                            dut.io_toTempDS_dataWr_bits_wrMaskOH:get() == 0x01 and
                            dut.io_toTempDS_dataWr_bits_beatData:get() == 0xdead
                end)

                env.expect_happen_until(10, function ()
                    return dut.io_toTempDS_dataWr_valid:get() == 1 and 
                            dut.io_toTempDS_dataWr_bits_dataId:get() == 10 and
                            dut.io_toTempDS_dataWr_bits_wrMaskOH:get() == 0x02 and
                            dut.io_toTempDS_dataWr_bits_beatData:get() == 0xbeef
                end)

                env.posedge()
                env.expect_not_happen_until(10, function ()
                    return dut.io_toTempDS_dataWr_valid:get() == 1
                end)
            end
        }

        env.negedge()
            dut.io_rxdat_bits_dataID:set(("0b00"):number())
            dut.io_rxdat_bits_data:set_str("0xdead")
            dut.io_rxdat_valid:set(1)
        env.negedge()
            dut.io_rxdat_bits_data:set_str("0xbeef")
            dut.io_rxdat_bits_dataID:set(("0b10"):number())
        env.negedge()
            dut.io_rxdat_valid:set(0)
        
        env.posedge(100)
    end
}

local test_wrdata_stall = env.register_test_case "test_wrdata_stall" {
    function ()
        env.dut_reset()

        dut.io_toTempDS_dataWr_ready:set(0)
        dut.io_toTempDS_dataId:set(10)

        env.negedge()
            dut.io_rxdat_bits_dataID:set(("0b00"):number())
            dut.io_rxdat_bits_data:set_str("0xdead")
            dut.io_rxdat_valid:set(1)
        env.negedge(10, function ()
            dut.io_rxdat_ready:expect(0)
        end)
        
        env.posedge(100)
    end
}

local test_keep_dataid = env.register_test_case "test_keep_dataid" {
    function ()
        env.dut_reset()

        dut.io_toTempDS_dataWr_ready:set(1)
        dut.io_toTempDS_dataId:set(10)

        env.negedge()
            dut.io_rxdat_bits_dataID:set(("0b00"):number())
            dut.io_rxdat_bits_data:set_str("0xdead")
            dut.io_rxdat_valid:set(1)
        env.negedge()
            dut.io_rxdat_bits_dataID:set(("0b10"):number())
            dut.io_toTempDS_dataWr_ready:set(0)
            dut.io_toTempDS_dataWr_bits_dataId:expect(10)
        
        env.negedge(10, function()
            dut.io_toTempDS_dataWr_bits_dataId:expect(10)
        end)
        
        env.posedge(100)
    end
}

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_wrdata_no_stall()
        test_wrdata_stall()
        test_keep_dataid()

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}