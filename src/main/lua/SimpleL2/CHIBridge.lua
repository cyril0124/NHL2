local utils = require "LuaUtils"
local env = require "env"

local assert = assert
local expect = env.expect

local bridge = dut.u_CHIBridge
local txState = bridge.txState
local rxState = bridge.rxState
local txactivereq = dut.io_out_chiLinkCtrl_txactivereq
local txactiveack = dut.io_out_chiLinkCtrl_txactiveack
local rxactivereq = dut.io_out_chiLinkCtrl_rxactivereq
local rxactiveack = dut.io_out_chiLinkCtrl_rxactiveack

assert(cfg.simulator == "vcs" or cfg.simulator == "iverilog")

local LinkState = utils.enum_define {
    STOP = 0,
    ACITVATE = 2,
    RUN = 3,
    DEACTIVATE = 1,
}

local test_txstate_switch = env.register_test_case "test_txstate_switch" {
    function ()
        env.dut_reset()

        env.posedge(3)
            txState:expect(LinkState.ACITVATE)
        
        env.posedge()
            txState:expect(LinkState.ACITVATE)
            txactiveack:set(1)
        
        env.posedge(2)
            txState:expect(LinkState.RUN)
            txactivereq:expect(1)
        
        env.posedge()

        env.posedge(10, function (cycle)
            txState:expect(LinkState.RUN) -- keep RUN
            txactivereq:expect(1)
            txactiveack:expect(1)
        end)

        -- txactivereq:set_force(0)
        -- txactiveack:set_force(0)

        -- TODO: STOP
    end
}

local test_txstate_after_reset = env.register_test_case "test_txstate_after_reset" {
    function ()
        env.dut_reset()
        txactiveack:set(0)
        env.posedge(3)

        txState:expect(LinkState.ACITVATE)

        env.posedge(10, function (cycle)
            txState:expect(LinkState.ACITVATE) -- keep ACITVATE
        end)
    end
}

local test_rxstate_switch = env.register_test_case "test_rxstate_switch" {
    function ()
        env.dut_reset()
            rxState:expect(LinkState.STOP)
        
        env.posedge(3)
            rxState:expect(LinkState.STOP)
            rxactiveack:expect(0)
            rxactivereq:expect(0)

            rxactivereq:set(1)
        
        env.posedge(2)
            rxState:expect(LinkState.ACITVATE)
        
        env.posedge()
            rxState:expect(LinkState.RUN)

        env.posedge(10, function (cycle)
            assert(rxState() == LinkState.RUN) -- keep RUN
            assert(rxactiveack() == 1)
            assert(rxactivereq() == 1)
        end)

        rxactivereq:set(0)

        env.posedge(2)
            rxState:expect(LinkState.DEACTIVATE)
        
        env.posedge()
            rxState:expect(LinkState.STOP)

        env.posedge(10, function (cycle)
            rxState:expect(LinkState.STOP) -- keep STOP
            rxactiveack:expect(0)
            rxactivereq:expect(0)
        end)

        
        -- 
        -- restart
        -- 
        rxactivereq:set(1)
        env.posedge(2)
            rxState:expect(LinkState.ACITVATE)

        env.posedge()
            rxState:expect(LinkState.RUN)

        env.posedge(10, function (cycle)
            rxState:expect(LinkState.RUN) -- keep RUN
            rxactiveack:expect(1)
            rxactivereq:expect(1)
        end)

        rxactivereq:set(0)

        env.posedge(10)
            rxState:expect(LinkState.STOP)
    end
}

verilua "mainTask" { function ()
    sim.dump_wave()
    env.dut_reset()

    test_txstate_switch()
    test_txstate_after_reset()
    test_rxstate_switch()

    env.TEST_SUCCESS()
end }


