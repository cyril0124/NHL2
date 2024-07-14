local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"

local assert = assert
local expect = env.expect
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeC = tl.TLOpcodeC

local L2Channel = utils.enum_define {
    ChannelA = tonumber("0b001"),
    ChannelB = tonumber("0b010"),
    ChannelC = tonumber("0b100"),
}

local taskMSHR_s0 = ([[
    | ready
    | valid
    | owner
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_taskMSHR_s0_", is_decoupled = true, name = "taskMSHR_s0"}

local taskReplay_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskReplay_s1_", is_decoupled = true, name = "taskReplay_s1"}

local taskCMO_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskCMO_s1_", is_decoupled = true, name = "taskCMO_s1"}

local taskSnoop_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_taskSnoop_s1_", is_decoupled = true, name = "taskSnoop_s1"}

local taskSinkC_s1 = ([[
    | ready
    | valid
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top, prefix = "io_taskSinkC_s1_", is_decoupled = true, name = "taskSinkC_s1"}

local taskSinkA_s1 = ([[
    | ready
    | valid
    | set
    | tag
    | opcode
    | channel
]]):bundle {hier = cfg.top, prefix = "io_taskSinkA_s1_", is_decoupled = true, name = "taskSinkA_s1"}

local dirRead_s1 = ([[
    | ready
    | valid
]]):bundle {hier = cfg.top, prefix = "io_dirRead_s1_", is_decoupled = true}

local mpReq_s2 = ([[
    | valid
]]):bundle {hier = cfg.top, prefix = "io_mpReq_s2_", is_decoupled = false}


local normal_tasks = {
    taskReplay_s1,
    taskCMO_s1,
    taskSnoop_s1,
    taskSinkC_s1,
    taskSinkA_s1,
}

local clock = dut.clock:chdl()
local reqArb = dut.u_RequestArbiter

local dataSinkC_s1 = dut.io_dataSinkC_s1

local function set_ready()
    dut.io_dirRead_s1_ready = 1
end

local function reset_ready()
    dut.io_dirRead_s1_ready = 0
end

local test_basic_mshr_req = env.register_test_case "test_basic_mshr_req" {
    function ()
        env.dut_reset()
        set_ready()

        env.posedge()

        expect.equal(taskMSHR_s0.ready:get(), 1)
    
        env.negedge()
            taskMSHR_s0.valid:set(1)
        
        env.negedge()
            taskMSHR_s0.valid:set(0)

        assert(dirRead_s1:fire())
        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 0)
        end

        env.posedge()
            expect.equal(taskMSHR_s0.ready:get(), 0)
    
        env.posedge()
            expect.equal(taskMSHR_s0.ready:get(), 1)
    
        env.posedge(10)
        reset_ready()
    end
}

local test_basic_sink_req = env.register_test_case "test_basic_sink_req" {
    function ()
        env.dut_reset()
        set_ready()

        env.posedge()

        for i, task in ipairs(normal_tasks) do
            task:dump()
            assert(task.ready:get() == 1)
        end

        env.negedge()
            taskSinkA_s1.valid:set(1)

        env.negedge()
            taskSinkA_s1.valid:set(0)
            assert(dirRead_s1:fire())

        env.posedge(10)
        reset_ready()
    end
}

local test_mshr_block_sink_req = env.register_test_case "test_mshr_block_sink_req" {
    function ()
        env.dut_reset()
        set_ready()

        env.posedge()

        for i, task in ipairs(normal_tasks) do
            assert(task.ready:get() == 1)
        end

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            taskMSHR_s0.valid:set(1)
            taskSinkA_s1.valid:set(0)

        env.posedge()
            assert(taskSinkA_s1.ready:get() == 1)

        env.negedge()
            assert(taskMSHR_s0.ready:get() == 0)
            assert(taskSinkA_s1.ready:get() == 0)
            assert(reqArb.isTaskMSHR_s1:get() == 1)
            taskMSHR_s0.valid:set(0)
        
        env.posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            taskSinkA_s1.valid:set(1)

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)

        env.posedge()
            taskSinkA_s1.valid:set(0)

        env.posedge(10)
        reset_ready()
    end
}

local test_sinkC_block_sinkA = env.register_test_case "test_sinkC_block_sinkA" {
    function ()
        env.dut_reset()
        set_ready()
        -- send_wr_crd()

        env.posedge()

        env.negedge()
            assert(taskSinkA_s1.ready:get() == 1)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
            taskSinkC_s1.valid:set(1)
        
        env.posedge()
            assert(taskSinkA_s1.ready:get() == 0)
            assert(taskSinkC_s1.ready:get() == 1)
            assert(taskSnoop_s1.ready:get() == 1)
        
        env.negedge()
            taskSinkC_s1.valid:set(0)
       
        env.posedge()
            

        env.posedge(10)
        reset_ready()
    end
}

local test_basic_release = env.register_test_case "test_basic_release" {
    function ()
        env.dut_reset()
        set_ready()

        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            taskSinkC_s1.valid:set(1)
            taskSinkC_s1.bits.set:set(0x01)
            taskSinkC_s1.bits.tag:set(0x02)
            taskSinkC_s1.bits.opcode:set(TLOpcodeC.Release)
        env.posedge()
            expect.equal(reqArb.io_dirRead_s1_valid:get(), 1)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
            taskSinkC_s1.valid:set(0)
        env.negedge()
            expect.equal(taskSinkC_s1.ready:get(), 1)
        
        env.posedge(math.random(3, 10), function (c)
            expect.equal(taskSinkC_s1.ready:get(), 1)
        end)

        env.posedge(10)
        reset_ready()
    end
}

local test_block_sinkA_for_same_addr = env.register_test_case "test_block_sinkA_for_same_addr" {
    function ()
        env.dut_reset()
        set_ready()

        reqArb.mayReadDS_s2:set_force(0)

        -- same address
        env.negedge()
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x01)
            taskSinkA_s1.bits.tag:set(0x02)
            taskSinkA_s1.bits.opcode:set(TLOpcodeA.AcquireBlock)
            taskSinkA_s1.bits.channel:set(L2Channel.ChannelA)
            env.posedge()
                taskSinkA_s1.ready:expect(1)
        env.negedge()
            reqArb.valid_s2:expect(1)
            reqArb.valid_s3:expect(0)
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x01)
            taskSinkA_s1.bits.tag:set(0x02)
            env.posedge()
                taskSinkA_s1.ready:expect(0)
        env.negedge()
            reqArb.valid_s2:expect(0)
            reqArb.valid_s3:expect(1)
            taskSinkA_s1.ready:expect(0)
        env.negedge()
            reqArb.valid_s2:expect(0)
            reqArb.valid_s3:expect(0)
            taskSinkA_s1.ready:expect(1)
        env.negedge()
            reqArb.valid_s2:expect(1)
            reqArb.valid_s3:expect(0)
            taskSinkA_s1.valid:set(0)
        env.negedge()
            reqArb.valid_s2:expect(0)
            reqArb.valid_s3:expect(1)
        env.negedge()
            reqArb.valid_s2:expect(0)
            reqArb.valid_s3:expect(0)

        env.posedge(10)
        
        -- different address
        env.negedge()
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x01)
            taskSinkA_s1.bits.tag:set(0x02)
            taskSinkA_s1.bits.opcode:set(TLOpcodeA.AcquireBlock)
            taskSinkA_s1.bits.channel:set(L2Channel.ChannelA)
            env.posedge()
                taskSinkA_s1.ready:expect(1)
        env.negedge()
            reqArb.valid_s2:expect(1)
            reqArb.valid_s3:expect(0)
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x02)
            taskSinkA_s1.bits.tag:set(0x02)
            env.posedge()
                taskSinkA_s1.ready:expect(1)
        env.negedge()
            taskSinkA_s1.valid:set(0)
        
        env.posedge(10)
        reset_ready()
        reqArb.mayReadDS_s2:set_release()
    end
}

local test_ds_block_sinkA = env.register_test_case "test_ds_block_sinkA" {
    function ()
        env.dut_reset()
        set_ready()

        env.negedge()
            reqArb.mayReadDS_s2:expect(0)
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x01)
            taskSinkA_s1.bits.tag:set(0x02)
            taskSinkA_s1.bits.opcode:set(TLOpcodeA.AcquireBlock)
            taskSinkA_s1.bits.channel:set(L2Channel.ChannelA)
            env.posedge()
                taskSinkA_s1.ready:expect(1)
        env.negedge()
            reqArb.valid_s2:expect(1)
            reqArb.valid_s3:expect(0)
            reqArb.mayReadDS_s2:expect(1)
            taskSinkA_s1.valid:set(1)
            taskSinkA_s1.bits.set:set(0x02)
            taskSinkA_s1.bits.tag:set(0x02)
            env.posedge()
                reqArb.mayReadDS_s2:expect(1)
                reqArb.blockA_addrConflict:expect(0)
                taskSinkA_s1.ready:expect(0)
        env.negedge()
            taskSinkA_s1.ready:expect(1)
            taskSinkA_s1.valid:set(0)

        env.posedge(10)
        reset_ready()
    end
}

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()
        
        dut.io_resetFinish:set(1)

        test_basic_mshr_req()
        test_basic_sink_req()
        test_mshr_block_sink_req()
        test_sinkC_block_sinkA()
        test_basic_release()
        test_block_sinkA_for_same_addr()
        test_ds_block_sinkA()

        env.TEST_SUCCESS()
    end
}

