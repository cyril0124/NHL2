local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local assert = assert

local TLChannel = tl.TLChannel
local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeC = tl.TLOpcodeC
local MixedState = tl.MixedState

local mpReq_s2 = ([[
    | valid
    | set
    | channel
    | opcode
    | param
]]):bundle {hier = cfg.top, prefix = "io_mpReq_s2_", is_decoupled = true}

local dirResp_s3 = ([[
    | valid
    | meta_state
    | meta_clientsOH
    | hit
]]):bundle {hier = cfg.top, prefix = "io_dirResp_s3_", is_decoupled = true}

local mshrAlloc_s3 = ([[
    | valid 
    | ready
]]):bundle {hier = cfg.top, prefix = "io_mshrAlloc_s3_", is_decoupled = true}

local replay_s4 = ([[
    | valid 
]]):bundle {hier = cfg.top, prefix = "io_replay_s4_", is_decoupled = true}

local mp = dut.u_MainPipe


local test_basic_acquire = env.register_test_case "test_basic_acquire" {
    function (case)
        print("case is ", case)

        env.dut_reset()
        env.posedge()

        env.mux_case(case) {
            hit = function()
                mshrAlloc_s3.ready:set(1)
            end,

            hit_alloc_mshr = function()
                mshrAlloc_s3.ready:set(1)
            end,

            miss = function()
                mshrAlloc_s3.ready:set(1)
            end,
            
            miss_replay = function()
                mshrAlloc_s3.ready:set(0)
            end
        }
        
        env.negedge()
            env.mux_case(case, true) {
                hit_alloc_mshr = function()
                    mpReq_s2.bits.param:set(TLParam.BtoT)
                end
            }
            mpReq_s2.bits.opcode:set(TLOpcodeA.AcquireBlock)
            mpReq_s2.bits.channel:set(TLChannel.ChannelA)
            mpReq_s2.valid:set(1)

        env.posedge()
            env.mux_case(case) {
                hit = function()
                    dirResp_s3.bits.hit:set(1)
                    dirResp_s3.bits.meta_state:set(MixedState.TC)
                end,

                hit_alloc_mshr = function()
                    dirResp_s3.bits.hit:set(1)
                    dirResp_s3.bits.meta_state:set(MixedState.BC)
                    dirResp_s3.bits.meta_clientsOH:set(("0b01"):number())
                end,

                miss = function()
                    dirResp_s3.bits.hit:set(0)
                    dirResp_s3.bits.meta_state:set(MixedState.I)
                end,

                miss_replay = function()
                    dirResp_s3.bits.hit:set(0)
                    dirResp_s3.bits.meta_state:set(MixedState.I)
                end
            }
            dirResp_s3.valid:set(1)

        env.negedge()
            mpReq_s2.valid:set(0)

        env.posedge()
            env.mux_case(case) {
                hit = function()
                    assert(mshrAlloc_s3.valid:get() == 0)
                end,

                hit_alloc_mshr = function()
                    assert(mshrAlloc_s3.valid:get() == 1)
                end,

                miss = function()
                    assert(mshrAlloc_s3.valid:get() == 1)
                end,

                miss_replay = function()
                    assert(mshrAlloc_s3.valid:get() == 1)
                end,
            }

        env.negedge()
            dirResp_s3.valid:set(0)

        env.posedge()
            env.mux_case(case, true) {
                hit_alloc_mshr = function()
                    assert(mshrAlloc_s3.valid:get() == 0)
                    assert(replay_s4.valid:get() == 0)    
                end,

                miss_replay = function()
                    assert(mshrAlloc_s3.valid:get() == 0)
                    assert(replay_s4.valid:get() == 1)
                end
            }
        
        env.negedge()

        env.posedge()
            env.mux_case(case, true) {
                miss_replay = function()
                    assert(replay_s4.valid:get() == 0)
                end
            }

        env.posedge(10)
        mshrAlloc_s3.ready:set(0)
    end
}

local test_basic_release = env.register_test_case "test_basic_release" {
    function (case)
        print("case is ", case)

        env.dut_reset()
        env.posedge()

        local ready_or_not = math.random(0, 1) == 1
        if ready_or_not == 1 then
            mshrAlloc_s3.ready:set(1)
        else
            mshrAlloc_s3.ready:set(0) 
        end

        env.negedge()
            mpReq_s2.bits.opcode:set(TLOpcodeC.ReleaseData)
            mpReq_s2.bits.param:set(TLParam.TtoN)
            mpReq_s2.bits.channel:set(TLChannel.ChannelC)
            mpReq_s2.valid:set(1)
        
        env.posedge()
            dirResp_s3.bits.hit:set(1)
            dirResp_s3.bits.meta_state:set(MixedState.TTC)
            dirResp_s3.valid:set(1)

        env.negedge()
            mpReq_s2.valid:set(0)

        env.posedge()
            assert(mshrAlloc_s3.valid:get() == 0)

        env.negedge()
            dirResp_s3.valid:set(0)

        env.posedge()
            assert(mshrAlloc_s3.valid:get() == 0)
            assert(replay_s4.valid:get() == 0)


        env.posedge(10)
        mshrAlloc_s3.ready:set(0)
    end
}


verilua "mainTask" { 
    function ()
        sim.dump_wave()
        env.dut_reset()

        dut.io_sourceD_s6s7_ready:set(1)
        dut.io_replay_s4_ready:set(1)

        test_basic_acquire("hit")
        test_basic_acquire("miss")
        test_basic_acquire("miss_replay")
        test_basic_acquire("hit_alloc_mshr")

        for i = 1, 5 do
            test_basic_release("normal")
        end

        env.TEST_SUCCESS()
    end 
}


