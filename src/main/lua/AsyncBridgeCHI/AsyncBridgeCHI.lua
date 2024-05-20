local env = require "env"
local fun = require "fun"

local assert = assert
local TEST_SUCCESS = env.TEST_SUCCESS
local posedge = env.posedge
local dut_reset = env.dut_reset

local case_name = os.getenv "CASE_NAME"

local bridge = dut.u_AsyncBridgeCHI_TB

local enq_txreq_flitv = bridge.io_chi_enq_txreq_flitv
local enq_txreq_flit = bridge.io_chi_enq_txreq_flit
local enq_rxdat_lcrdv = bridge.io_chi_enq_rxdat_lcrdv
local enq_reset_finish = bridge.io_resetFinish_enq

local deq_txreq_flitv = bridge.io_chi_deq_txreq_flitv
local deq_txreq_flit = bridge.io_chi_deq_txreq_flit
local deq_rxdat_lcrdv = bridge.io_chi_deq_rxdat_lcrdv
local deq_reset_finish = bridge.io_resetFinish_deq

local enq_clock = bridge.bridge.enq_clock
local deq_clock = bridge.bridge.deq_clock

local slow_reset_finish
do
    if case_name == "slow_to_fast" then
        slow_reset_finish = enq_reset_finish
    elseif case_name == "fast_to_slow" then
        slow_reset_finish = deq_reset_finish
    elseif case_name == "same_clock" then
        slow_reset_finish = enq_reset_finish -- or deq_reset_finish
    else
        assert(false, "Unkonwn case name => " .. case_name)
    end
end

assert(cfg.simulator == "vcs")

local function enq_send_data(data_signal, valid_signal, data)
    valid_signal:set(1)
    data_signal:set(data)
    enq_clock:posedge()
    data_signal:set(0)
    valid_signal:set(0)
end

local function enq_send_lcrdv(lcrdv_signal)
    lcrdv_signal:set(1)
    enq_clock:posedge()
    lcrdv_signal:set(0)
end

local MAX_DATAS = 4
local data_sequence = {}
for i = 1, MAX_DATAS do
    table.insert(data_sequence, math.random(0, 1000))
end

verilua "mainTask" { function ()
    sim.dump_wave()

    dut_reset()

    dut.deq_reset = 1
    posedge(5)
    dut.deq_reset = 0

    enq_clock:posedge(5000)


    TEST_SUCCESS()
end }

verilua "appendTasks" {
    send_data = function ()
        slow_reset_finish:posedge()

        -- sync to current clock domain
        enq_clock:posedge(5)

        -- send data
        for index, value in ipairs(data_sequence) do
            enq_send_data(enq_txreq_flit, enq_txreq_flitv, value)
        end
    end,

    check_data = function ()
        slow_reset_finish:posedge()

        -- sync to current clock domain
        deq_clock:posedge(5)

        local data_count = 0
        local ok = deq_clock:posedge_until(1000, function ()
            if deq_txreq_flitv() == 1 then
                assert(deq_txreq_flit() == data_sequence[data_count + 1])
                data_count = data_count + 1
            end
            return data_count >= #data_sequence
        end)

        assert(ok)
    end,

    send_lcrdv = function ()
        slow_reset_finish:posedge()

        -- sync to current clock domain
        enq_clock:posedge(5)

        -- send lcrdv
        for i = 1, 4 do
            enq_send_lcrdv(enq_rxdat_lcrdv)
        end

    end,

    check_lcrdv = function ()
        slow_reset_finish:posedge()

        -- sync to current clock domain
        deq_clock:posedge(5)

        local lcrdv_count = 0
        local ok = deq_clock:posedge_until(1000, function ()
            if deq_rxdat_lcrdv() == 1 then
                lcrdv_count = lcrdv_count + 1
            end
            return lcrdv_count >= 4
        end)

        assert(ok, lcrdv_count)
    end
}


