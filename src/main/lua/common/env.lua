local sim, dut = sim, dut
local await_posedge = await_posedge
local assert = assert
local print = print

local clock = (cfg.top .. ".clock"):chdl()

local function end_simulation()
    sim.simulator_control(sim.SimCtrl.FINISH)
end

local function posedge(cycles, cycle_action_func)
    if cycles == nil then
        -- await_posedge(dut.clock)
        clock:posedge()
    else
        local func = cycle_action_func or function (cycle) end
        assert(cycles > 0)

        clock:posedge(cycles, func)
        
        -- for i = 1, cycles do
            -- func(i)
            -- await_posedge(dut.clock)
        -- end
    end
end

local function dut_reset()
    clock:posedge()
    dut.reset = 1
    clock:posedge(10)
    dut.reset = 0
end


local function sync_to_cycles(cycles)
    local current_cycles = dut.cycles()
    if not (cycles < current_cycles) then
        dut.clock:posedge_until(cycles - current_cycles, function ()
            return dut.cycles() >= cycles
        end)
    end
end

local function TEST_SUCCESS()
    print(">>>TEST_SUCCESS!<<<")
    end_simulation()
end

return {
    end_simulation = end_simulation,
    posedge = posedge,
    dut_reset = dut_reset,
    sync_to_cycles = sync_to_cycles,
    TEST_SUCCESS = TEST_SUCCESS
}
