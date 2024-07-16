local env = require "env"
local tl = require "TileLink"
local TLOpcodeD = tl.TLOpcodeD
local expect = env.expect

local tl_d = ([[
    | valid
    | ready
    | opcode
    | data
    | source
]]):bundle {hier = cfg.top, prefix = "io_d_", name = "tl_d"}

local task_s2 = ([[
    | valid
    | ready
    | opcode
    | source
    | sink
]]):bundle {hier = cfg.top, prefix = "io_task_s2_", name = "task_s2"}

local task_s6s7 = ([[
    | valid
    | ready
    | opcode
    | source
    | sink
]]):bundle {hier = cfg.top, prefix = "io_task_s6s7_", name = "task_s6s7"}


local sourceD = dut.u_SourceD

local function send_task_s2(source, opcode)
    env.negedge()
        expect.equal(task_s2.ready:get(), 1)
        task_s2.valid:set(1)
        task_s2.bits.source:set(source)
        task_s2.bits.opcode:set(opcode)
    env.negedge()
        task_s2.valid:set(0)
end

local test_basic_no_stall = env.register_test_case "test_basic_no_stall" {
    function ()
        tl_d.ready:set(1)

        print "test Grant"
        env.negedge()
            task_s2.ready:expect(1)
            task_s2.valid:set(1)
            task_s2.bits.source:set(10)
            task_s2.bits.opcode:set(TLOpcodeD.Grant)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.Grant)
            tl_d.bits.source:expect(10)
            task_s2.valid:set(0)
            dut.io_data_s2_valid:set(0)

        print "test ReleaseAck"
        env.negedge()
            task_s2.ready:expect(1)
            task_s2.valid:set(1)
            task_s2.bits.source:set(10)
            task_s2.bits.opcode:set(TLOpcodeD.ReleaseAck)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.ReleaseAck)
            tl_d.bits.source:expect(10)
            task_s2.valid:set(0)
            dut.io_data_s2_valid:set(0)
        
        print "test GrantData"
        env.negedge()
            task_s2.ready:expect(1)
            task_s2.valid:set(1)
            task_s2.bits.source:set(11)
            task_s2.bits.opcode:set(TLOpcodeD.GrantData)
            dut.io_data_s2_valid:set(1)
            dut.io_data_s2_bits:set(0x1234, true)
        env.posedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
            sourceD.last:expect(0)
            expect.equal(tl_d.bits.data:get()[1], 0x1234)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
            tl_d.bits.source:expect(11)
            sourceD.last:expect(1)
            expect.equal(tl_d.bits.data:get()[1], 0)
            task_s2.valid:set(0)
            dut.io_data_s2_valid:set(0)
        env.negedge()
            tl_d.valid:expect(0)

        env.negedge(10, function ()
            tl_d.valid:expect(0)
        end)
    end
}

local test_use_skidbuffer = env.register_test_case "test_use_skidbuffer" {
    function ()
        tl_d.ready:set(0)
        env.negedge()

        -- print "test Grant"
        -- env.negedge()
        --     task_s2.ready:expect(1)
        --     task_s2.valid:set(1)
        --     task_s2.bits.source:set(10)
        --     task_s2.bits.opcode:set(TLOpcodeD.Grant)
        -- env.negedge()
        --     tl_d.valid:expect(1)
        --     tl_d.bits.opcode:expect(TLOpcodeD.Grant)
        --     tl_d.bits.source:expect(10)
        --     task_s2.valid:set(0)
        -- env.negedge()
        --     task_s2.ready:expect(0)
        --     tl_d.ready:set(1)
        -- env.negedge()
        --     task_s2.ready:expect(1)
        
        tl_d.ready:set(0)
        env.negedge()
        
        print "test GrantData"
        env.negedge()
            task_s2.ready:expect(1)
            task_s2.valid:set(1)
            task_s2.bits.source:set(11)
            task_s2.bits.opcode:set(TLOpcodeD.GrantData)
            dut.io_data_s2_valid:set(1)
            dut.io_data_s2_bits:set(0x1234, true)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
            tl_d.bits.source:expect(11)
            expect.equal(tl_d.bits.data:get()[1], 0x1234)
            task_s2.valid:set(0)
            dut.io_data_s2_valid:set(0)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.ready:set(1)
        env.posedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
            tl_d.bits.source:expect(11)
            sourceD.last:expect(0)
            expect.equal(tl_d.bits.data:get()[1], 0x1234)
        env.negedge()
            tl_d.valid:expect(1)
            tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
            tl_d.bits.source:expect(11)
            sourceD.last:expect(1)
            expect.equal(tl_d.bits.data:get()[1], 0)
            task_s2.valid:set(0)
        env.negedge()
            tl_d.valid:expect(0)
            task_s2.ready:expect(1)

        env.negedge(10, function ()
            tl_d.valid:expect(0)
        end)
            
        tl_d.ready:set(0)
    end
}

local test_nondata_resp = env.register_test_case "test_nondata_resp" {
    function ()
        env.negedge()
        send_task_s2(11, TLOpcodeD.ReleaseAck)
        env.expect_happen_until(3, function ()
            return tl_d.valid:is(1)
        end)
        tl_d:dump()
    end
}

-- TODO: mixed resp

local test_continuious_grant = env.register_test_case "test_continuious_grant" {
    function ()
        -- TODO:
    end
}

local test_continuious_grantdata = env.register_test_case "test_continuious_grantdata" {
    function ()
        -- TODO:
    end
}

local test_mix_grant_and_grantdata = env.register_test_case "test_mix_grant_and_grantdata" {
    function ()
        -- TODO:
    end
}

verilua "appendTasks" {
    main_task = function ()
        sim.dump_wave()
        env.dut_reset()

        math.randomseed(os.time())

        env.call_with_hook_and_callback(
            function () env.dut_reset()  end,
            function () env.posedge(200) end,
            {
                test_basic_no_stall,
                test_use_skidbuffer,
                test_nondata_resp,
            }
        )

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}