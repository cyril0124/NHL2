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


local wrWay_s3 = dut.io_wrWay_s3


verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()

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
            dsRead_s3.bits.dest:set(1) -- 1: SourceD

        env.negedge()
            -- 
            -- write way is provided in Stage 3
            -- 
            wrWay_s3:set(1)
            dsWrite_s2.valid:set(0)
            
            dsRead_s3.valid:set(0)
        
        env.negedge()
            wrWay_s3:set(0)
            
            -- 
            -- [2] read from set=0x01, way=0x01
            -- 
            dsRead_s3.valid:set(1)
            dsRead_s3.bits.set:set(2)
            dsRead_s3.bits.way:set(1)
            dsRead_s3.bits.dest:set(1) -- 1: SourceD

        env.negedge()
            dsRead_s3.valid:set(0)

        env.posedge(10)

        env.posedge(100)

        env.TEST_SUCCESS()
    end
}


verilua "appendTasks" {
    check_task = function ()
        dut.reset:negedge()

        local ok = false
        local read_data = 0xff

        ok = dut.clock:posedge_until(100, function (c)
            local condition_meet = dsResp_ds4:fire()
            if condition_meet then
                read_data = dsResp_ds4.bits.data:get()[1]
                return true 
            else
                return false
            end
        end)
        assert(ok)
        expect.equal(read_data, 0x00)
        
        env.posedge()

        ok = dut.clock:posedge_until(100, function (c)
            local condition_meet = dsResp_ds4:fire()
            if condition_meet then
                read_data = dsResp_ds4.bits.data:get()[1]
                return true 
            else
                return false
            end
        end)
        assert(ok)
        expect.equal(read_data, 0xdead)
    end
}