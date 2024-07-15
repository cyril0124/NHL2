local utils = require "LuaUtils"
local env = require "env"
local assert = assert
local expect = env.expect

local L2Channel = utils.enum_define {
    ChannelA = tonumber("0b001"),
    ChannelB = tonumber("0b010"),
    ChannelC = tonumber("0b100"),
}

local replay = ([[
    | valid
    | task_set
    | task_tag
    | task_opcode
]]):bundle{hier = cfg.top, prefix = "io_replay_s4_"}

local req = ([[
    | valid
    | ready
    | set
    | tag
    | opcode
]]):bundle{hier = cfg.top, prefix = "io_req_s1_"}

local freeCnt = dut.u_ReplayStation.io_freeCnt:chdl()
local replayStation = dut.u_ReplayStation

local nrReplayEntry = 4

local test_basic_replay = env.register_test_case "test_basic_replay" {
    function ()
        env.dut_reset()

        freeCnt:expect(nrReplayEntry)

        for i = 1, nrReplayEntry do
            print("alloc replay entry " .. i)
            env.negedge()
                replay.valid:set(1)
                replay.bits.task_set:set(i)
                replay.bits.task_tag:set(0x02)
                replay.bits.task_opcode:set(1)
            env.negedge()
                freeCnt:expect(nrReplayEntry - i)
                replayStation["entries_" .. (i - 1) .. "_valid"]:expect(1)
                replayStation["entries_" .. (i - 1) .. "_bits_set"]:expect(i)
                replayStation["entries_" .. (i - 1) .. "_bits_tag"]:expect(0x02)
                replayStation["entries_" .. (i - 1) .. "_bits_subEntries_0_valid"]:expect(1)
                replayStation["entries_" .. (i - 1) .. "_bits_subEntries_1_valid"]:expect(0)
                replayStation["entries_" .. (i - 1) .. "_bits_subEntries_2_valid"]:expect(0)
                replay.valid:set(0)
        end

        env.negedge()
            replay.valid:set(1)
            replay.bits.task_set:set(0x01)
            replay.bits.task_tag:set(0x02)
            replay.bits.task_opcode:set(2)
        env.negedge()
            replayStation["entries_" .. 0 .. "_bits_subEntries_0_valid"]:expect(1)
            replayStation["entries_" .. 0 .. "_bits_subEntries_1_valid"]:expect(1)
            replayStation["entries_" .. 0 .. "_bits_subEntries_2_valid"]:expect(0)
            replay.valid:set(0)

        env.negedge()
            replay.valid:set(1)
            replay.bits.task_set:set(0x01)
            replay.bits.task_tag:set(0x02)
            replay.bits.task_opcode:set(3)
        env.negedge()
            replayStation["entries_" .. 0 .. "_bits_subEntries_0_valid"]:expect(1)
            replayStation["entries_" .. 0 .. "_bits_subEntries_1_valid"]:expect(1)
            replayStation["entries_" .. 0 .. "_bits_subEntries_2_valid"]:expect(1)
            replay.valid:set(0)

        env.posedge(10)

        env.negedge()
            req.valid:expect(1)
            req.ready:set(1)
        env.negedge()
            req:dump()

        repeat
            env.negedge()
            req:dump()
            freeCnt:dump()
        until not req.valid:is(1)
       
        env.posedge(100)
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()

        test_basic_replay()

        env.TEST_SUCCESS()
    end
}