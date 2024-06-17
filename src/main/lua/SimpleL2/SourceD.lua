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

local task = ([[
    | valid
    | ready
    | opcode
    | source
]]):bundle {hier = cfg.top, prefix = "io_task_", name = "task"}

local data = ([[
    | valid
    | data
    | last
]]):bundle {hier = cfg.top, prefix = "io_data_", name = "data"}

local tempDataRead = ([[
    | valid
    | ready
    | dataId
]]):bundle {hier = cfg.top, prefix = "io_tempDataRead_", name = "data", is_decoupled = false}



local sourceD = dut.u_SourceD
local taskQueue = dut.u_SourceD.taskQueue

local function send_task(source, opcode)
    env.negedge()
        expect.equal(task.ready:get(), 1)
        task.valid:set(1)
        task.bits.source:set(source)
        task.bits.opcode:set(opcode)
    env.negedge()
        task.valid:set(0)
end

local function send_data(data_0_str, data_1_str)
    assert(type(data_0_str) == "string")
    assert(type(data_1_str) == "string")
    
    env.negedge()
        data.valid:set(1)
        data.bits.data:set_str(data_0_str)
    env.negedge()
        data.bits.data:set_str(data_1_str)
        data.bits.last:set(1)
    env.negedge()
        data.bits.last:set(0)
        data.valid:set(0)
end

local test_grant_no_stall = env.register_test_case "test_grant_no_stall" {
    function ()
        env.dut_reset()

        tl_d.ready:set(1)

        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(10, function(c)
                    return tl_d:fire() and tl_d.bits.source:get() == 1
                end)
                
                env.posedge()
                env.expect_not_happen_until(10, function(c)
                    return tl_d:fire()
                end)
            end
        }
        
        send_task(1, TLOpcodeD.Grant)

        env.posedge(100)
    end
}

local test_grant_simple_stall = env.register_test_case "test_grant_stall" {
    function ()
        env.dut_reset()

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_task = function()
                env.expect_happen_until(20, function(c)
                   return tl_d:fire() and tl_d.bits.source:get() == 1
                end)

                env.posedge()
                env.expect_not_happen_until(20, function(c)
                    return tl_d:fire()
                end)
            end
        }

        send_task(1, TLOpcodeD.Grant)

        env.negedge(math.random(3, 10))
        tl_d.ready:set(1)

        env.posedge(100)
    end
}

local test_grant_consecutive_stall = env.register_test_case "test_grantdata_continuous_stall_2" {
    function ()
        env.dut_reset()

        tl_d.ready:set(0)

        local sync_ehdl = ("sync"):ehdl()
        verilua "appendTasks" {
            check_task = function()
               env.expect_happen_until(20, function(c)
                   return tl_d:fire() and tl_d.bits.source:get() == 1
               end)

                env.expect_happen_until(20, function(c)
                    return tl_d:fire() and tl_d.bits.source:get() == 2
                end)

                env.posedge()
                env.expect_not_happen_until(20, function(c)
                    return tl_d:fire()
                end)

                sync_ehdl:wait()
                for i = 1, 4 do
                    env.expect_happen_until(20, function(c)
                        return tl_d:fire() and tl_d.bits.source:get() == i
                    end)
                    env.posedge()
                end
            end
        }

        send_task(1, TLOpcodeD.Grant)
        send_task(2, TLOpcodeD.Grant)

        env.negedge(math.random(3, 10))
        tl_d.ready:set(1)

        env.posedge(100)
        
        sync_ehdl:send()
        tl_d.ready:set(0)
        for i = 1, 4 do
            send_task(i, TLOpcodeD.Grant)
        end
        expect.equal(taskQueue.io_enq_ready:get(), 0) -- taskQueue is full

        for i = 1, 100 do
            env.negedge()
            tl_d.ready:set(math.random(0, 1))
        end

        env.posedge(100)
    end
}

local test_grantdata_no_stall = env.register_test_case "test_grantdata_no_stall" {
    function ()
        local source = 4
        env.dut_reset()

        tl_d.ready:set(1)

        verilua "appendTasks" {
            check_task = function ()
                local ok = false
                -- check task enq
                env.expect_happen_until(10, function(c)
                    return task:fire() and taskQueue.io_enq_valid:get() == 1 and taskQueue.io_enq_ready:get() == 1
                end)

                env.expect_happen_until(20, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == source and tl_d.bits.data:get()[1] == 0x100
                end)

                env.expect_happen_until(20, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == source and tl_d.bits.data:get()[1] == 0x200
                end)

                env.expect_happen_until(5, function (c)
                    return taskQueue.io_deq_ready:get() == 1 and taskQueue.io_deq_valid:get() == 1  
                end)
            end
        }

        send_task(source, TLOpcodeD.GrantData)

        env.posedge(math.random(1, 10))

        send_data("0x100", "0x200")

        env.posedge(100)
    end
}

local test_grantdata_simple_stall = env.register_test_case "test_grantdata_simple_stall" {
    function ()
        local source = 4
        env.dut_reset()

        tl_d.ready:set(0)
        
        verilua "appendTasks" {
            check_task = function()
                local ok = false
                -- data will put into tmpDataBuffer
                env.expect_happen_until(300, function (c)
                    return sourceD.tmpDataBuffer:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000100"
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == source and tl_d.bits.data:get()[1] == 0x100
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == source and tl_d.bits.data:get()[1] == 0x200
                end)

                env.expect_happen_until(5, function (c)
                    return taskQueue.io_deq_ready:get() == 1 and taskQueue.io_deq_valid:get() == 1  
                end)
            end,

            check_fsm = function ()
                local Normal = 0
                local Stall = 1

                env.expect_happen_until(300, function (c)
                    return sourceD.outState:get() == Stall
                end)

                env.expect_happen_until(300, function (c)
                    return sourceD.outState:get() == Normal
                end)
            end,
        }

        send_task(source, TLOpcodeD.GrantData)

        env.posedge(math.random(3, 100))
        
        send_data("0x100", "0x200")

        env.posedge(math.random(1, 100))
        env.negedge()
            tl_d.ready:set(1)

        env.posedge(100)
    end
}


local test_grantdata_continuous_stall_2 = env.register_test_case "test_grantdata_continuous_stall_2" {
    function ()
        env.dut_reset()
        
        tl_d.ready:set(0)
        tempDataRead.ready:set(1)

        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(300, function (c)
                    return tl_d:fire()
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == 4 and tl_d.bits.data:get()[1] == 0x300
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == 4 and tl_d.bits.data:get()[1] == 0x400
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == 5 and tl_d.bits.data:get()[1] == 0x500
                end)

                env.expect_happen_until(300, function (c)
                    return tl_d:fire() and tl_d.bits.source:get() == 5 and tl_d.bits.data:get()[1] == 0x600
                end)
            end,

            check_task_1 = function ()
                env.expect_happen_until(400, function (c)
                    return tempDataRead.valid:get() == 1 and tempDataRead.ready:get() == 1 and tempDataRead.dataId:get() == 2
                end)
            end
        }

        send_task(4, TLOpcodeD.GrantData)
        send_task(5, TLOpcodeD.GrantData)
        expect.equal(taskQueue.io_count:get(), 2)

        env.posedge(math.random(3, 10))

        send_data("0x300", "0x400")

        env.posedge(math.random(3, 10))

        sourceD.io_dataId:set(2)
        send_data("0x500", "0x600")

        env.posedge(math.random(1, 10))
        
        env.negedge()
            tl_d.ready:set(1)

        env.negedge(math.random(3, 10))
            tl_d.ready:set(0)
            sourceD.io_tempDataResp_bits:set_str("0x00000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000500")
            sourceD.io_tempDataResp_valid:set(1)
        env.negedge()
            sourceD.io_tempDataResp_valid:set(0)

        env.negedge(math.random(3, 10))
            tl_d.ready:set(1)

        env.posedge(500)
    end
}


verilua "appendTasks" {
    main_task = function ()
        sim.dump_wave()
        env.dut_reset()

        math.randomseed(os.time())

        env.posedge()

        test_grant_no_stall()
        test_grant_simple_stall()
        test_grant_consecutive_stall()
        test_grantdata_no_stall()
        test_grantdata_simple_stall()
        test_grantdata_continuous_stall_2()

        env.posedge(100)

        env.TEST_SUCCESS()
    end,

    check_task = function ()
        
    end,
}