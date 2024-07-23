local utils = require "LuaUtils"
local env = require "env"
local verilua = verilua
local assert = assert
local expect = env.expect

local sinke = dut.u_SinkE

local alloc = ([[
    | valid
    | sink
    | mshrTask
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_allocGrantMap_", name = "allocGrantMap"}

local status = {}
for i = 0, 15 do
    table.insert(status, i, ([[
        | valid
        | set
        | tag
    ]]):bundle{hier = cfg.top, prefix = "io_grantMapStatus_" .. i .. "_", name = "grantMapStatus_" .. i, is_decoupled = false})
end

local test_basic_alloc_grant_map = env.register_test_case "test_basic_alloc_grant_map" {
    function ()
        env.dut_reset()

        sinke.insertOH:expect(1)

        -- normal
        env.negedge()
            alloc.valid:set(1)
            alloc.bits.sink:set(10)
            alloc.bits.set:set(0x10)
            alloc.bits.tag:set(0x20)
            alloc.bits.mshrTask:set(0)
            sinke.grantMap_0_mshrTask:expect(0)
        env.negedge()
            status[0].valid:expect(1)
            status[0].set:expect(0x10)
            status[0].tag:expect(0x20)
            sinke.grantMap_0_mshrTask:expect(0)
            for i = 1, 15 do status[i].valid:expect(0) end
            sinke.insertOH:expect(0x02)
            alloc.valid:set(0)
        env.negedge(10, function ()
            status[0].valid:expect(1)
        end)

        env.negedge()
            dut.io_e_valid:set(1)
            dut.io_e_bits_sink:set(10)
            env.posedge()
            dut.io_resp_valid:expect(0)
            dut.io_sinkIdFree_valid:expect(1)
            dut.io_sinkIdFree_idIn:expect(10)
        env.negedge()
            status[0].valid:expect(0)
            sinke.insertOH:expect(0x01)
            dut.io_e_valid:set(0)
        env.negedge()
            dut.io_sinkIdFree_valid:expect(0)


        -- mshr
        env.negedge()
            alloc.valid:set(1)
            alloc.bits.sink:set(2)
            alloc.bits.set:set(0x11)
            alloc.bits.tag:set(0x21)
            alloc.bits.mshrTask:set(1)
            sinke.grantMap_0_mshrTask:expect(0)
        env.negedge()
            status[0].valid:expect(1)
            sinke.grantMap_0_mshrTask:expect(1)
            sinke.insertOH:expect(0x02)
            alloc.valid:set(0)
        
        env.negedge()
            dut.io_e_valid:set(1)
            dut.io_e_bits_sink:set(2)
            sinke.grantMap_0_mshrTask:expect(1)
            env.posedge()
            dut.io_resp_valid:expect(1)
            dut.io_resp_bits_sink:expect(2)
            dut.io_sinkIdFree_valid:expect(0)
        env.negedge()
            status[0].valid:expect(0)
            sinke.grantMap_0_mshrTask:expect(1)
            sinke.insertOH:expect(0x01)
            dut.io_e_valid:set(0)
            dut.io_sinkIdFree_valid:expect(0)


        env.posedge(5)
    end
}

local test_alloc_grant_map_until_full = env.register_test_case "test_alloc_grant_map_until_full" {
    function ()
        env.dut_reset()

        local i = 0
        repeat
            print(i)
            env.negedge()
                alloc.valid:set(1)
                alloc.bits.sink:set(10 + i)
                alloc.bits.set:set(0x10 + i)
                alloc.bits.tag:set(0x20 + i)
                alloc.bits.mshrTask:set(math.random(0, 1))
            env.negedge()
                status[i].valid:expect(1)
                status[i].set:expect(0x10 + i)
                status[i].tag:expect(0x20 + i)
                for j = i + 1, 15 do status[j].valid:expect(0) end
                if i == 15 then
                    sinke.grantMapFull:expect(1)
                else
                    sinke.insertOH:expect(utils.uint_to_onehot(i + 1))
                end
                alloc.valid:set(0)
            i = i + 1
        until status[15].valid:is(1)

        env.posedge(5)
    end
}


verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_basic_alloc_grant_map()
        test_alloc_grant_map_until_full()

        env.posedge(5)
        env.TEST_SUCCESS()
    end
}