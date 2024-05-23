local utils = require "LuaUtils"
local env = require "env"

local assert = assert
local TEST_SUCCESS = env.TEST_SUCCESS
local posedge = env.posedge
local dut_reset = env.dut_reset

local bridge = dut.u_CHIBridge
local txState = bridge.txState
local rxState = bridge.rxState
local txactivereq = dut.io_out_chiLinkCtrl_txactivereq
local txactiveack = dut.io_out_chiLinkCtrl_txactiveack
local rxactivereq = dut.io_out_chiLinkCtrl_rxactivereq
local rxactiveack = dut.io_out_chiLinkCtrl_rxactiveack

assert(cfg.simulator == "vcs")

local LinkState = utils.enum_define {
    STOP = 0,
    ACITVATE = 2,
    RUN = 3,
    DEACTIVATE = 1,
}

local function test_txstate_switch()
    dut_reset()
    posedge(2)

    assert(txState() == LinkState.ACITVATE) -- ACITVATE
    posedge()

    txactiveack:set(1)
    posedge()

    assert(txState() == LinkState.RUN) -- RUN
    assert(txactivereq() == 1)
    posedge()

    posedge(10, function (cycle)
        assert(txState() == LinkState.RUN) -- keep RUN
        assert(txactivereq() == 1)
        assert(txactiveack() == 1)
    end)

    -- txactivereq:set_force(0)
    -- txactiveack:set_force(0)

    -- TODO: STOP
end

local function test_txstate_after_reset()
    dut_reset()
    txactiveack:set(0)
    posedge(2)

    assert(txState() == LinkState.ACITVATE) -- ACITVATE

    posedge(10, function (cycle)
        assert(txState() == LinkState.ACITVATE, txState()) -- keep ACITVATE
    end)
end

local function test_rxstate_switch()
    dut_reset()
    assert(rxState() == LinkState.STOP)
    posedge(2)

    assert(rxState() == LinkState.STOP)
    assert(rxactiveack() == 0)
    assert(rxactivereq() == 0)

    rxactivereq:set(1)
    posedge()

    assert(rxState() == LinkState.ACITVATE)
    posedge()

    assert(rxState() == LinkState.RUN)

    posedge(10, function (cycle)
        assert(rxState() == LinkState.RUN) -- keep RUN
        assert(rxactiveack() == 1)
        assert(rxactivereq() == 1)
    end)

    rxactivereq:set(0)
    posedge()

    assert(rxState() == LinkState.DEACTIVATE)
    posedge()

    assert(rxState() == LinkState.STOP)

    posedge(10, function (cycle)
        assert(rxState() == LinkState.STOP) -- keep STOP
        assert(rxactiveack() == 0)
        assert(rxactivereq() == 0)
    end)

    
    -- 
    -- restart
    -- 
    rxactivereq:set(1)
    posedge()

    assert(rxState() == LinkState.ACITVATE)
    posedge()

    assert(rxState() == LinkState.RUN)

    posedge(10, function (cycle)
        assert(rxState() == LinkState.RUN) -- keep RUN
        assert(rxactiveack() == 1)
        assert(rxactivereq() == 1)
    end)

    rxactivereq:set(0)

    posedge(10)
    assert(rxState() == LinkState.STOP)
end

verilua "mainTask" { function ()
    sim.dump_wave()
    dut_reset()

    test_txstate_switch()
    test_txstate_after_reset()
    test_rxstate_switch()

    TEST_SUCCESS()
end }


