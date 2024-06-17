local sim, dut = sim, dut
local await_posedge = await_posedge
local setmetatable = setmetatable
local setfenv = setfenv
local assert = assert
local print = print
local type = type
local pcall = pcall
local format = string.format

local clock = (cfg.top .. ".clock"):chdl()

local function end_simulation()
    sim.simulator_control(sim.SimCtrl.FINISH)
end

local function posedge(cycles, cycle_action_func)
    if cycles == nil then
        clock:posedge()
    else
        local func = cycle_action_func or function (cycle) end
        assert(cycles > 0)
        clock:posedge(cycles, func)
    end
end

local function negedge(cycles, cycle_action_func)
    if cycles == nil then
        clock:negedge()
    else
        local func = cycle_action_func or function (cycle) end
        assert(cycles > 0)
        clock:negedge(cycles, func)
    end
end

local function dut_reset()
    -- clock:posedge()
    dut.reset = 1
    clock:negedge(10)
    dut.reset = 0
    -- clock:negedge()
end


local function sync_to_cycles(cycles)
    local current_cycles = dut.cycles()
    if not (cycles < current_cycles) then
        dut.clock:posedge_until(cycles - current_cycles, function ()
            return dut.cycles() >= cycles
        end)
    end
end

local function send_pulse(signal)
    signal:set(1)
    posedge()
    signal:set(0)
    posedge()
end

local function cycles()
    return dut.cycles:get()
end


local function TEST_SUCCESS()
    print(">>>TEST_SUCCESS!<<<")
    end_simulation()
end


-- 
-- Test case manage
-- 
local test_count = 0
local last_test_name = "Unknown"
local is_start_test = false
local function __start_case_test__(name)
    assert(is_start_test == false, "[start_case_test] last test => " .. last_test_name .. " is not finished!")

    print(format([[
-----------------------------------------------------------------
| [%d] start test case ==> %s
-----------------------------------------------------------------]], test_count, name))
    last_test_name = name
    is_start_test = true
end

local function __end_case_test__(name)
    assert(is_start_test == true, "[end_case_test] no test has been started!")

    print(format([[
-----------------------------------------------------------------
| [%d] end test case ==> %s
-----------------------------------------------------------------
]], test_count, name))
    is_start_test = false
    test_count = test_count + 1
end

local function register_test_case(case_name)
    assert(type(case_name) == "string")

    return function(func_table)
        assert(type(func_table) == "table")
        assert(#func_table == 1)
        assert(type(func_table[1]) == "function")
        local func = func_table[1]

        local old_print = print
        local new_env = {
            print = function(...)
                print("|", ...)
            end
        }

        setmetatable(new_env, { __index = _G })
        setfenv(func, new_env)

        return function (...)
            __start_case_test__(case_name)
            func(...)    
            __end_case_test__(case_name)
        end
        
    end
end


local function mux_case(input_key, mismatch_bypass)
    local mismatch_bypass = mismatch_bypass or false
    assert(input_key ~= nil)

    return function (case_table)
        assert(type(case_table) == "table")

        local case_count = 0
        for _, _ in pairs(case_table) do
            case_count = case_count + 1
        end

        local allow_less_than_two_case = mismatch_bypass or case_table.default ~= nil
        assert(not(case_count <= 2 and not allow_less_than_two_case), "at least two cases  case_count => " .. case_count)

        for key, func in pairs(case_table) do
            if key == input_key then
                func()
                goto out
            end
        end

        if case_table.default ~= nil then
            case_table.default()
            goto out
        end

        if not mismatch_bypass then
            assert(false, "input_key does not match any cases! ==> input_key: " .. input_key)
        end

        ::out::
    end
end

local function will_failed(func, ...)
    local ok = pcall(func, ...)
    assert(not ok)
end

local function expect_happen_until(limit_cycles, func)
    assert(type(limit_cycles) == "number")
    assert(type(func) == "function")
    local ok = dut.clock:posedge_until(limit_cycles, func)
    assert(ok)
end

local function expect_not_happen_until(limit_cycles, func)
    assert(type(limit_cycles) == "number")
    assert(type(func) == "function")
    local ok = dut.clock:posedge_until(limit_cycles, func)
    assert(not ok)
end

local lester = require "lester"
local expect = lester.expect

return {
    end_simulation          = end_simulation,
    posedge                 = posedge,
    negedge                 = negedge,
    dut_reset               = dut_reset,
    sync_to_cycles          = sync_to_cycles,
    send_pulse              = send_pulse,
    cycles                  = cycles,
    TEST_SUCCESS            = TEST_SUCCESS,
    register_test_case      = register_test_case,
    mux_case                = mux_case,
    will_failed             = will_failed,
    expect                  = expect,
    expect_happen_until     = expect_happen_until,
    expect_not_happen_until = expect_not_happen_until,
}
