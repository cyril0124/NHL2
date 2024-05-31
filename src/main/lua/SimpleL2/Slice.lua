local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local verilua = verilua
local assert = assert
local expect = env.expect

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA

local resetFinish = (cfg.top .. ".u_Slice.dir.io_resetFinish"):chdl()
local reset_ehdl = ("reset"):ehdl()
local send_ehdl = ("send"):ehdl()

local tl_a = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_a_"}

tl_a.acquire_block = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.AcquireBlock)
    this.bits.address:set(addr, true)
    this.bits.param:set(param)
    this.bits.source:set(source or 0)
    this.bits.size:set(5) -- 2^5 == 32
end

local mpReq_s2 = ([[
    | valid
    | opcode
]]):bundle {hier = cfg.top .. ".u_Slice.reqArb", is_decoupled = true, prefix = "io_mpReq_s2_"}


local mp = dut.u_Slice.mainPipe



verilua "mainTask" { function ()
    sim.dump_wave()
    env.dut_reset()

    env.posedge(1000)

    env.TEST_SUCCESS()
end }


verilua "appendTasks" {
    reset_task = function ()
        env.dut_reset()
        env.posedge()

        resetFinish:posedge()
        
        reset_ehdl:send()
    end,

    send_task = function ()
        reset_ehdl:wait()

        env.negedge()
            tl_a:acquire_block(0x100, TLParam.NtoT, 8)
        
        env.negedge()
            tl_a.valid:set(0)

        send_ehdl:send()
    end,

    check_task = function()
        send_ehdl:wait()

        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
        
        env.posedge()
            expect.equal(mp.valid_s3:get(), 1)
            expect.equal(mp.task_s3_opcode:get(), TLOpcodeA.AcquireBlock)
            expect.equal(mp.task_s3_param:get(), TLParam.NtoT)
            expect.equal(mp.task_s3_source:get(), 8)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_mshrAlloc_s3_ready:get(), 0)

        env.posedge()
            expect.equal(mp.valid_s4:get(), 1)
    end
}
