local env = require "env"
local utils = require "LuaUtils"
local tl = require "TileLink"
local TLOpcodeC = tl.TLOpcodeC
local expect = env.expect

local function to_address(set, tag)
    local offset_bits = 6
    local set_bits = dut.io_task_bits_set.get_width()
    local tag_bits = dut.io_task_bits_tag.get_width()

    return utils.bitpat_to_hexstr({
        {   -- set field
            s = offset_bits,   
            e = offset_bits + set_bits - 1,
            v = set
        },
        {   -- tag field
            s = offset_bits + set_bits, 
            e = offset_bits + set_bits + tag_bits - 1, 
            v = tag
        }
    }, 64):number()
end

local tl_c = ([[
    | valid
    | ready
    | size
    | address
    | data
    | opcode
    | param
    | source
]]):bundle{hier = cfg.top, prefix = "io_c_", name = "tl_c"}

local task = ([[
    | valid
    | ready
    | source
    | set
    | tag
]]):bundle{hier = cfg.top, prefix = "io_task_", name = "task"}

local resp = ([[
    | valid
    | set
    | tag
    | opcode
    | param
    | last
    | dataId
]]):bundle{hier = cfg.top, prefix = "io_resp_", name = "resp"}

local tempDS = ([[
    | valid
    | wrMaskOH
    | beatData
    | dataId
]]):bundle{hier = cfg.top, prefix = "io_toTempDS_dataWr_", name = "tempDS"}

local sinkC = dut.u_SinkC

local function send_crd()
    env.negedge()
        dut.io_toDS_dsWrite_s2_crdv:set(1)
    env.negedge()
        dut.io_toDS_dsWrite_s2_crdv:set(0)
    env.negedge()
end


local test_simple_releasedata = env.register_test_case "test_simple_releasedata" {
    function ()
        env.dut_reset()

        send_crd()
        dut.io_task_ready:set(1)

        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(100, function()
                    return task:fire() and task.bits.source:get() == 8 and task.bits.set:get() == 0x10, task.bits.tag:get() == 0x20
                end)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return task.valid:get() == 1
                end)
            end,

            check_resp = function ()
                env.expect_not_happen_until(100, function ()
                    return resp:fire()
                end)
            end,
        }

        env.negedge()
            sinkC.first:expect(1)
            tl_c.bits.address:set(to_address(0x10, 0x20), true)
            tl_c.bits.opcode:set(TLOpcodeC.ReleaseData)
            tl_c.bits.data:set_str("0xdead")
            tl_c.bits.source:set(8)
            tl_c.bits.size:set(6) -- 2^6 = 64 Bytes
            tl_c.valid:set(1)
        env.negedge()
            sinkC.first:expect(0)
            tl_c.ready:expect(1)
            tl_c.bits.data:set_str("0xbeef")
        env.negedge()
            tl_c.ready:expect(0) -- credit has been consumed
            tl_c.valid:set(0)

        env.posedge(500)
    end
}

local test_simple_release = env.register_test_case "test_simple_release" {
    function ()
        env.dut_reset()

        dut.io_task_ready:set(1)

        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(100, function()
                    return task:fire() and task.bits.source:get() == 8 and task.bits.set:get() == 0x10, task.bits.tag:get() == 0x20
                end)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return task.valid:get() == 1
                end)
            end,

            check_resp = function ()
                env.expect_not_happen_until(100, function ()
                    return resp:fire()
                end)
            end,
        }

        env.negedge()
            sinkC.first:expect(1)
            tl_c.bits.address:set(to_address(0x10, 0x20), true)
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(8)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            sinkC.first:expect(1)
            tl_c.ready:expect(1)
            tl_c.valid:set(0)

        env.posedge(500)
    end
}

local test_simple_probeackdata = env.register_test_case "test_simple_probeackdata" {
    function ()
       env.dut_reset()

       dut.io_task_ready:set(1)
       dut.io_toTempDS_dataWr_ready:set(1)

       verilua "appendTasks" {
            check_resp = function ()
                env.expect_happen_until(100, function()
                    return resp:fire() and resp.bits.last:get() == 0
                end)
                
                env.negedge()
                env.expect_happen_until(100, function()
                    return resp:fire() and resp.bits.last:get() == 1
                end)
            end,
            check_task = function ()
                env.expect_not_happen_until(100, function ()
                    return task:fire()
                end)
            end,
            check_tempDS = function ()
                env.expect_happen_until(100, function ()
                    return tempDS:fire() and tempDS.bits.wrMaskOH:get() == ("0b01"):number() and tempDS.bits.beatData:get()[1] == 0xdead
                end)

                env.negedge()
                env.expect_happen_until(100, function ()
                    return tempDS:fire() and tempDS.bits.wrMaskOH:get() == ("0b10"):number() and tempDS.bits.beatData:get()[1] == 0xbeef
                end)
            end
       }
        
       env.negedge()
            sinkC.first:expect(1)
            tl_c.bits.address:set(to_address(0x10, 0x20), true)
            tl_c.bits.opcode:set(TLOpcodeC.ProbeAckData)
            tl_c.bits.data:set_str("0xdead")
            tl_c.bits.source:set(8)
            tl_c.bits.size:set(6) -- 2^6 = 64 Bytes
            tl_c.valid:set(1)
        env.negedge()
            sinkC.first:expect(0)
            tl_c.ready:expect(1)
            tl_c.bits.data:set_str("0xbeef")
        env.negedge()
            tl_c.valid:set(0)
       
       env.posedge(200)
    end
}

local test_simple_probeack = env.register_test_case "test_simple_probeack" {
    function ()
       env.dut_reset()

       dut.io_task_ready:set(1)
       dut.io_toTempDS_dataWr_ready:set(1)

       verilua "appendTasks" {
            check_resp = function ()
                env.expect_happen_until(100, function()
                    return resp:fire() and resp.bits.last:get() == 1
                end)
            end,
            check_task = function ()
                env.expect_not_happen_until(100, function ()
                    return task:fire()
                end)
            end,
            check_tempDS = function ()
                env.expect_not_happen_until(100, function ()
                    return tempDS:fire()
                end)
            end
        }

        env.negedge()
            sinkC.first:expect(1)
            tl_c.bits.address:set(to_address(0x10, 0x20), true)
            tl_c.bits.opcode:set(TLOpcodeC.ProbeAck)
            tl_c.bits.source:set(8)
            tl_c.bits.size:set(6) -- 2^6 = 64 Bytes
            tl_c.valid:set(1)
        env.negedge()
            sinkC.first:expect(1)
            tl_c.ready:expect(1)
            tl_c.valid:set(0)

        env.posedge(200)
    end
}

local test_stalled_release_releasedata = env.register_test_case "test_stalled_release_releasedata" {
    function ()
        env.dut_reset()

        dut.io_task_ready:set(0)
        dut.io_toTempDS_dataWr_ready:set(0)

        env.posedge()

        env.negedge()
            tl_c.bits.opcode:set(TLOpcodeC.ReleaseData)
            tl_c.bits.size:set(6)
            tl_c.valid:set(1)
        env.negedge()

        verilua "appendTasks" {
            check_no_fire = function ()
                env.expect_not_happen_until(100, function ()
                    tl_c.ready:expect(0)
                    return tl_c:fire()
                end)
            end,
            check_no_task = function ()
                env.expect_not_happen_until(100, function ()
                    return task:fire()
                end)
            end
        }

        env.posedge(50)

        env.negedge()
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.valid:set(1)

        env.posedge(100)
    end
}

local test_stalled_probeack_probeackdata = env.register_test_case "test_stalled_probeack_probeackdata" {
    function ()
        env.dut_reset()

        dut.io_task_ready:set(0)
        dut.io_toTempDS_dataWr_ready:set(0)

        env.posedge()

        env.negedge()
            tl_c.bits.opcode:set(TLOpcodeC.ProbeAckData)
            tl_c.bits.size:set(6)
            tl_c.valid:set(1)
        env.negedge()

        verilua "appendTasks" {
            check_no_fire = function ()
                env.expect_not_happen_until(100, function ()
                    tl_c.ready:expect(0)
                    return tl_c:fire()
                end)
            end,
            check_no_task = function ()
                env.expect_not_happen_until(100, function ()
                    return task:fire()
                end)
            end,
            check_no_resp = function ()
                env.expect_not_happen_until(100, function ()
                    return resp:fire()
                end)
            end
        }

        env.posedge(200)

        verilua "appendTasks" {
            check_fire_1 = function ()
                env.expect_happen_until(100, function ()
                    return tl_c:fire()
                end)
            end,
            check_no_task_1 = function ()
                env.expect_not_happen_until(100, function ()
                    return task:fire()
                end)
            end,
            check_resp_1 = function ()
                env.expect_happen_until(100, function ()
                    return resp:fire() and resp.bits.last:get() == 1
                end)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return resp:fire()
                end)
            end
        }

        -- probeack cannot be stalled
        env.negedge()
            tl_c.bits.opcode:set(TLOpcodeC.ProbeAck)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)

        env.posedge(100)
    end
}

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_simple_releasedata()
        test_simple_release()
        test_simple_probeackdata()
        test_simple_probeack()

        test_stalled_release_releasedata()
        test_stalled_probeack_probeackdata()

        env.TEST_SUCCESS()
    end
}