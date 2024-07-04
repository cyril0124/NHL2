local utils = require "LuaUtils"
local env = require "env"
local verilua = verilua
local assert = assert
local expect = env.expect

local inputs = {
    (" valid | ready | data"):bundle {hier = cfg.top, prefix = "io_in_0_", is_decoupled = true, name = "io_in_0"},
    (" valid | ready | data"):bundle {hier = cfg.top, prefix = "io_in_1_", is_decoupled = true, name = "io_in_1"},
    (" valid | ready | data"):bundle {hier = cfg.top, prefix = "io_in_2_", is_decoupled = true, name = "io_in_2"},
    (" valid | ready | data"):bundle {hier = cfg.top, prefix = "io_in_3_", is_decoupled = true, name = "io_in_3"},
}

local output = ([[
    | valid | ready | data
]]):bundle {hier = cfg.top, prefix = "io_out_", is_decoupled = true, name = "io_out"}

local test_basic_input = env.register_test_case "test_basic_input" {
    function ()
        env.dut_reset()

        -- output not ready
        for i = 1, 4 do
                local data = math.random(0, 255)
                inputs[i].valid:set(1)
                inputs[i].bits.data:set(data)
            env.negedge() -- update circuit
                output.valid:expect(1)
                output.bits.data:expect(data)
                inputs[i].valid:set(0)
                inputs[i].ready:expect(0)
                dut.io_chosen:expect(i - 1)
        end

        -- output is ready
        output.ready:set(1)
        for i = 1, 4 do
                local data = math.random(0, 255)
                inputs[i].valid:set(1)
                inputs[i].bits.data:set(data)
            env.negedge() -- update circuit
                output.valid:expect(1)
                output.bits.data:expect(data)
                assert(inputs[i]:fire())
                inputs[i].valid:set(0)
                dut.io_chosen:expect(i - 1)
        end

        env.posedge(100)
    end
}

local test_arb_inputs = env.register_test_case "test_arb_inputs" {
    function ()
        env.dut_reset()

        -- full inputs
        do
            for i = 1, 4 do
                inputs[i].valid:set(1)
                inputs[i].bits.data:set(i)
            end

            local counts = {0, 0, 0, 0}
            env.posedge(100, function (c)
                counts[dut.io_chosen:get() + 1] = counts[dut.io_chosen:get() + 1] + 1
                inputs[dut.io_chosen:get() + 1].bits.data:expect(dut.io_chosen:get() + 1)
            end)
            pp(counts)
            assert(counts[1] >= 10)
            assert(counts[2] >= 10)
            assert(counts[3] >= 10)
            assert(counts[4] >= 10)
        end

        for i = 1, 4 do
            inputs[i].valid:set(0)
        end
        env.negedge(10)

        -- partial inputs
        do
            local nr_input = math.random(1, 4)
            print("nr_input: " .. nr_input)
            local already_input = {}
            repeat
                local idx = math.random(1, 4)
                local input_or_not = math.random(0, 1) == 1
                if input_or_not and already_input[idx] == nil then 
                    inputs[idx].valid:set(1)
                    inputs[idx].bits.data:set(idx)
                    table.insert(already_input, idx - 1)
                end
            until #already_input == nr_input
            pp(already_input)

            local counts = {0, 0, 0, 0}
            env.posedge(100, function (c)
                counts[dut.io_chosen:get() + 1] = counts[dut.io_chosen:get() + 1] + 1
                inputs[dut.io_chosen:get() + 1].bits.data:expect(dut.io_chosen:get() + 1)
            end)
            pp(counts)
            for _, v in pairs(already_input) do
                assert(counts[v + 1] >= 10)
            end
        end

        for i = 1, 4 do
            inputs[i].valid:set(0)
        end
        
        env.posedge(100)
    end
}

verilua "appendTasks" {
    function ()
        sim.dump_wave()
        env.dut_reset()

        math.randomseed(os.time())
        
        test_basic_input()

        for i = 1, 10 do
            test_arb_inputs()
        end
        
        env.posedge(100)
        env.TEST_SUCCESS()
    end
}