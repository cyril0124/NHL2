local utils = require "LuaUtils"
local env = require "env"
local verilua = verilua
local assert = assert
local expect = env.expect

local test_basic_alloc = env.register_test_case "test_basic_alloc" {
    function ()
        env.dut_reset()

        dut.io_alloc_valid:set(0)
        dut.io_free_valid:set(0)

        env.negedge()
            dut.io_alloc_valid:set(1)
            env.posedge()
            dut.io_alloc_idOut:expect(16)
            dut.io_full:expect(0)
            dut.io_freeCnt:expect(4)
        env.negedge()
            dut.io_alloc_valid:set(0)
            dut.io_freeCnt:expect(3)

        env.negedge()
            dut.io_free_valid:set(1)
            dut.io_free_idIn:set(16)
            env.posedge()
            dut.u_IDPool.allocatedList_0:dump()
            dut.io_full:expect(0)
        env.negedge()
            dut.io_free_valid:set(0)
            dut.io_freeCnt:expect(4)

        env.posedge(5)
    end
}

local test_alloc_until_full = env.register_test_case "test_alloc_until_full" {
    function ()
        env.dut_reset()

        local function list_allocated()
            for i = 0, 3 do
                dut.u_IDPool["allocatedList_" .. i]:dump()
            end
            print()
        end

        local i = 1
        local ids = {16, 17, 18, 19}
        
        dut.io_alloc_valid:set(0)
        dut.io_free_valid:set(0)

        dut.io_freeCnt:expect(4)
        dut.io_full:expect(0)

        repeat
            print("start alloc id: ", ids[i])
            env.negedge()
                dut.io_alloc_valid:set(1)
                dut.io_alloc_idOut:expect(ids[i])
            env.negedge()
                dut.io_alloc_valid:set(0)

            list_allocated()
            print("get alloc id: ", ids[i])
            i = i + 1
        until dut.io_freeCnt:is(0)

        dut.io_full:expect(1)
        
        print("--------------------")

        i = i - 1
        repeat
            print("start free id: ", ids[i])
            env.negedge()
                dut.io_free_valid:set(1)
            env.negedge()
                dut.io_free_idIn:set(ids[i])
                dut.io_free_valid:set(0)
            dut.io_freeCnt:dump()
            i = i - 1
        until dut.io_freeCnt:is(4)

        env.posedge(5)
    end
}


verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_basic_alloc()
        test_alloc_until_full()

        env.posedge(5)
        env.TEST_SUCCESS()
    end
}