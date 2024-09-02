local env = require "env"
local tl = require "TileLink"
local utils = require "LuaUtils"

local MixedState = tl.MixedState
local assert = assert
local expect = env.expect

local dir = dut.u_Directory
local resetFinish = dir.io_resetFinish

local dirRead_s1 = ([[
    | valid
    | ready
    | set
    | tag
    | mshrId
    | replTask
]]):bundle {hier = cfg.top, prefix = "io_dirRead_s1_", name = "dirRead_s1"}

local dirResp_s3 = ([[
    | valid
    | wayOH
    | hit
    | meta_state
    | meta_tag
    | meta_aliasOpt
    | meta_fromPrefetch
]]):bundle {hier = cfg.top, prefix = "io_dirResp_s3_", name = "dirResp_s3"}

local dirWrite_s3 = ([[
    | valid
    | set
    | wayOH
    | meta_state
    | meta_tag
    | meta_aliasOpt
    | meta_fromPrefetch
]]):bundle {hier = cfg.top, prefix = "io_dirWrite_s3_", name = "dirWrite_s3"}

local replResp_s3 = ([[
    | valid
    | wayOH
    | mshrId
    | retry
    | meta_state
]]):bundle {hier = cfg.top, prefix = "io_replResp_s3_", name = "replResp_s3"}

local mshr_status = {}
for i = 1, 16 do
    mshr_status[i] = ([[
        | valid
        | set
        | wayOH
        | dirHit
    ]]):bundle {hier = cfg.top, prefix = "io_mshrStatus_" .. (i - 1) .. "_", is_decoupled = false, name = "mshrStatus_" .. (i - 1)}
end

local function dir_write(set, tag, wayOH, state)
    env.negedge()
        dirWrite_s3.valid:set(1)
        dirWrite_s3.bits.wayOH:set(wayOH)
        dirWrite_s3.bits.set:set(set)
        dirWrite_s3.bits.meta_tag:set(tag)
        dirWrite_s3.bits.meta_state:set(state)
    env.negedge()
        dirWrite_s3.valid:set(0)
end

local function dir_read(set, tag, mshr, repl_task)
    local mshr = mshr or 0
    local repl_task = repl_task or 0

    env.negedge()
        dirRead_s1.valid:set(1)
        dirRead_s1.bits.set:set(set)
        dirRead_s1.bits.tag:set(tag)
        dirRead_s1.bits.mshrId:set(mshr)
        dirRead_s1.bits.replTask:set(repl_task)
    env.negedge()
        dirRead_s1.valid:set(0)
end

local function reset_mshr_status()
    for i = 1, 16 do
        mshr_status[i].valid:set(0)
        mshr_status[i].set:set(0x00)
        mshr_status[i].dirHit:set(0)
    end
end

local nr_way = 4


local test_basic_read_write = env.register_test_case "test_basic_read_write" {
    function ()
        env.dut_reset()

        env.posedge()
            expect.equal(dirRead_s1.ready:get(), 0)
            -- expect.equal(dirWrite_s3.ready:get(), 0)
            expect.equal(dirResp_s3.valid:get(), 0)

        resetFinish:posedge()

        print("resetFinish current cycles => " .. dut.cycles())

        env.negedge()
            expect.equal(dirRead_s1.ready:get(), 1)
            -- expect.equal(dirWrite_s3.ready:get(), 1)
            expect.equal(dirResp_s3.valid:get(), 0)

        env.negedge()
            dirWrite_s3.valid:set(1)
            dirWrite_s3.bits.wayOH:set(("0b00000001"):number())
            dirWrite_s3.bits.set:set(0x11)
            dirWrite_s3.bits.meta_tag:set(0x22)
            dirWrite_s3.bits.meta_state:set(MixedState.TC)
            dirWrite_s3.bits.meta_aliasOpt:set(1)
            dirWrite_s3.bits.meta_fromPrefetch:set(1)
            print(dut.cycles(), dirWrite_s3:dump_str())

        env.negedge()
            dirWrite_s3.valid:set(0)

        env.posedge()
            expect.equal(dirRead_s1.ready:get(), 1)
        
        -- env.posedge(10)

        env.negedge()
            expect.equal(dirRead_s1.ready:get(), 1)
            dirRead_s1.valid:set(1)
            dirRead_s1.bits.set:set(0x11)
            dirRead_s1.bits.tag:set(0x22)
            print(dut.cycles(), dirRead_s1:dump_str())
        
        env.negedge()
            dirRead_s1.valid:set(0)
        
        env.posedge(2)
            assert(dirResp_s3:fire())
            expect.equal(dirResp_s3.bits.wayOH:get(), 0x01)
            expect.equal(dirResp_s3.bits.hit:get(), 1)
            expect.equal(dirResp_s3.bits.meta_state:get(), MixedState.TC)
            expect.equal(dirResp_s3.bits.meta_tag:get(), 0x22)
            expect.equal(dirResp_s3.bits.meta_aliasOpt:get(), 1)
            expect.equal(dirResp_s3.bits.meta_fromPrefetch:get(), 1)
            print(dut.cycles(), dirResp_s3:dump_str())

        local cycles = dut.cycles:chdl()
        print(cycles:dump_str())

    end
}

local test_miss_use_inv_way = env.register_test_case "test_miss_use_inv_way" {
   function ()
        env.dut_reset()
        resetFinish:posedge()

        for i = 1, nr_way do
            local set = 0x00
            local tag = 0x01 + i
            local expect_wayOH = utils.uint_to_onehot(i - 1)

            env.negedge(); dir_read(set, tag)
            
            env.expect_happen_until(10, function ()
                return dirResp_s3:fire()
            end)
            dirResp_s3:dump()
            dirResp_s3.bits.hit:expect(0)
            -- dirResp_s3.bits.wayOH:expect(expect_wayOH)
            dirResp_s3.bits.meta_state:expect(MixedState.I)

            env.negedge(); dir_write(set, tag, expect_wayOH, MixedState.TC)
            env.negedge(10)
        end

        env.posedge(100)
   end 
}

local test_miss_use_inv_way_updateReplacer = env.register_test_case "test_miss_use_inv_way_updateReplacer" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- loop 1(i = 1~4): miss and use inv_way
        -- loop 2(i = 5~8): hit  and updateReplacer
        for i = 1, nr_way*2 do
            local set = 0x00
            local tag = 0x01 + i%4
            local expect_wayOH = utils.uint_to_onehot((i-1)%4)

            env.negedge(); dir_read(set, tag)
            dir.io_dirRead_s1_bits_updateReplacer:set(1)
            
            env.expect_happen_until(10, function ()
                return dirResp_s3:fire()
            end)
            dirResp_s3:dump()
            if (i<=4) then
                dirResp_s3.bits.hit:expect(0)
                dirResp_s3.bits.meta_state:expect(MixedState.I)
            else
                dirResp_s3.bits.hit:expect(1)
            end

            env.negedge(); dir_write(set, tag, expect_wayOH, MixedState.TC)
            env.negedge(10)
        end
        
        env.negedge(30)



        function bitNot( input )
            if (input == 0) then
                output = 1
            else
                output = 0
            end
            return output
        end
        
        --      plru_0
        --   /          \
        -- plru_1[1]  plru_1[2]
        
        plru_0 = 0
        plru_1 = {0,0}
        
        GetVictimBool = 1
        GetVictimWay  = 0
        UpdateBool    = 1
        UpdateWay     = 0
        BitSaveStruct = 0

        -- no retry, use PLRU Replacer
        for i=1,nr_way do
            local mshrId = math.random(0,15)
            env.negedge(); dir_read(0x00, 0x05+(i-1), mshrId, 1)
            dir.io_dirRead_s1_bits_updateReplacer:set(1)
            env.expect_happen_until(10, function ()
                return dirResp_s3:fire()
            end)
            dirResp_s3:dump()
            replResp_s3:dump()

            GetVictimBool = 1
            if GetVictimBool == 1 then
                GetVictimWay = plru_0*2 + plru_1[plru_0+1]
                print("GetVictimWay:",GetVictimWay)
                plru_1[plru_0+1] = bitNot(plru_1[plru_0+1])
                plru_0           = bitNot(plru_0)
            elseif UpdateBool == 1 then
                -- plru_1[UpdateWay//2+1] = bitNot(UpdateWay%2)
                -- plru_0                 = bitNot(UpdateWay//2)
                plru_1[math.floor(UpdateWay/2)+1] = bitNot(UpdateWay%2)
                plru_0                            = bitNot(math.floor(UpdateWay/2))
                print("plru_0:",plru_0)
                print("plru_1:",plru_1[1],plru_1[2])
                BitSaveStruct = plru_0*4 + plru_1[2]*2 + plru_1[1]
                -- BitSaveStruct = (plru_0<<2) + (plru_1[2]<<1) + (plru_1[1])
                print("BitSaveStruct:",BitSaveStruct)
            end

            replResp_s3.valid:expect(1)
            replResp_s3.bits.mshrId:expect(mshrId)
            replResp_s3.bits.retry:expect(0)
            replResp_s3.bits.wayOH:expect(utils.uint_to_onehot(GetVictimWay))
        end

        env.negedge(30)

        -- no retry, PLRU Replacer victimway no free
        for i=1,1 do

            GetVictimBool = 1
            if GetVictimBool == 1 then
                GetVictimWay = plru_0*2 + plru_1[plru_0+1]
                print("GetVictimWay:",GetVictimWay)
                plru_1[plru_0+1] = bitNot(plru_1[plru_0+1])
                plru_0           = bitNot(plru_0)
            elseif UpdateBool == 1 then
                -- plru_1[UpdateWay//2+1] = bitNot(UpdateWay%2)
                -- plru_0                 = bitNot(UpdateWay//2)
                plru_1[math.floor(UpdateWay/2)+1] = bitNot(UpdateWay%2)
                plru_0                            = bitNot(math.floor(UpdateWay/2))
                print("plru_0:",plru_0)
                print("plru_1:",plru_1[1],plru_1[2])
                BitSaveStruct = plru_0*4 + plru_1[2]*2 + plru_1[1]
                -- BitSaveStruct = (plru_0<<2) + (plru_1[2]<<1) + (plru_1[1])
                print("BitSaveStruct:",BitSaveStruct)
            end

            for i = 1, 1 do
                mshr_status[i].valid:set(1)
                mshr_status[i].set:set(0x00)
                mshr_status[i].dirHit:set(1)
                mshr_status[i].wayOH:set(utils.uint_to_onehot(GetVictimWay))
            end

            local mshrId = math.random(0,15)
            env.negedge(); 
            dir_read(0x00, 0x10+(i-1), mshrId, 1)
            dir.io_dirRead_s1_bits_updateReplacer:set(1)
            env.expect_happen_until(10, function ()
                return dirResp_s3:fire()
            end)
            dirResp_s3:dump()
            replResp_s3:dump()

            GetVictimBool = 1
            if GetVictimBool == 1 then
                GetVictimWay = plru_0*2 + plru_1[plru_0+1]
                print("GetVictimWay:",GetVictimWay)
                plru_1[plru_0+1] = bitNot(plru_1[plru_0+1])
                plru_0           = bitNot(plru_0)
            elseif UpdateBool == 1 then
                -- plru_1[UpdateWay//2+1] = bitNot(UpdateWay%2)
                -- plru_0                 = bitNot(UpdateWay//2)
                plru_1[math.floor(UpdateWay/2)+1] = bitNot(UpdateWay%2)
                plru_0                            = bitNot(math.floor(UpdateWay/2))
                print("plru_0:",plru_0)
                print("plru_1:",plru_1[1],plru_1[2])
                BitSaveStruct = plru_0*4 + plru_1[2]*2 + plru_1[1]
                -- BitSaveStruct = (plru_0<<2) + (plru_1[2]<<1) + (plru_1[1])
                print("BitSaveStruct:",BitSaveStruct)
            end

            replResp_s3.valid:expect(1)
            replResp_s3.bits.mshrId:expect(mshrId)
            replResp_s3.bits.retry:expect(0)
            -- replResp_s3.bits.wayOH:expect(utils.uint_to_onehot(GetVictimWay))
        end

        env.posedge(100)
    end 
 }

local test_miss_filter_occupied_way = env.register_test_case "test_miss_filter_occupied_way" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        for i = 1, nr_way - 1 do
            mshr_status[i].valid:set(1)
            mshr_status[i].set:set(0x00)
            mshr_status[i].dirHit:set(1)
            mshr_status[i].wayOH:set(utils.uint_to_onehot(i - 1))

            env.negedge(); dir_read(0x00, 0x01)

            env.expect_happen_until(10, function ()
                return dirResp_s3:fire()
            end)
            dirResp_s3:dump()
            dirResp_s3.bits.hit:expect(0)
            assert(dirResp_s3.bits.wayOH:is_not(utils.uint_to_onehot(i - 1)))
            -- dirResp_s3.bits.wayOH:expect(utils.uint_to_onehot(i)) 
            dirResp_s3.bits.meta_state:expect(MixedState.I)
        end

        reset_mshr_status()

        env.dut_reset()
        resetFinish:posedge()

        print("")
        print("")

        for i = 1, 3 do
            mshr_status[i].valid:set(1)
            mshr_status[i].set:set(0x00)
            mshr_status[i].dirHit:set(1)
            mshr_status[i].wayOH:set(utils.uint_to_onehot(i - 1))
        end

        env.negedge(); dir_read(0x00, 0x01)

        env.expect_happen_until(10, function ()
            return dirResp_s3:fire()
        end)
        dirResp_s3:dump()
        dirResp_s3.bits.hit:expect(0)
        assert(dirResp_s3.bits.wayOH:is_not(utils.uint_to_onehot(3 - 1)))
        -- dirResp_s3.bits.wayOH:expect(utils.uint_to_onehot(3)) 
        dirResp_s3.bits.meta_state:expect(MixedState.I)

        reset_mshr_status()

        env.dut_reset()
        resetFinish:posedge()

        print("")
        print("")

        for i = 1, 4 do
            mshr_status[i].valid:set(1)
            mshr_status[i].set:set(0x00)
            mshr_status[i].dirHit:set(1)
            mshr_status[i].wayOH:set(utils.uint_to_onehot(i - 1))
        end

        env.negedge(); dir_read(0x00, 0x01)

        env.expect_happen_until(10, function ()
            return dirResp_s3:fire()
        end)
        dirResp_s3:dump()
        dirResp_s3.bits.hit:expect(0)
        assert(dirResp_s3.bits.wayOH:is_not(utils.uint_to_onehot(3 - 1)))
        -- dirResp_s3.bits.wayOH:expect(utils.uint_to_onehot(3)) 
        dirResp_s3.bits.meta_state:expect(MixedState.I)

        reset_mshr_status()

        env.posedge(100)
    end
}

local test_repl_resp = env.register_test_case "test_repl_resp" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        reset_mshr_status()

        -- no retry
        env.negedge(); dir_read(0x00, 0x01, 10, 1)
        env.expect_happen_until(10, function ()
            return dirResp_s3:fire()
        end)
        dirResp_s3:dump()
        replResp_s3:dump()
        replResp_s3.valid:expect(1)
        replResp_s3.bits.mshrId:expect(10)
        replResp_s3.bits.retry:expect(0)
        replResp_s3.bits.wayOH:expect(utils.uint_to_onehot(0))

        -- has retry
        for i = 1, nr_way do
            mshr_status[i].valid:set(1)
            mshr_status[i].set:set(0x00)
            mshr_status[i].dirHit:set(1)
            mshr_status[i].wayOH:set(utils.uint_to_onehot(i - 1))
        end
        env.negedge(); dir_read(0x00, 0x01, 11, 1)
        env.expect_happen_until(10, function ()
            return dirResp_s3:fire()
        end)
        dirResp_s3:dump()
        replResp_s3:dump()
        replResp_s3.valid:expect(1)
        replResp_s3.bits.mshrId:expect(11)
        replResp_s3.bits.retry:expect(1)
        -- replResp_s3.bits.wayOH:expect(utils.uint_to_onehot(0)) wayOH is not valid

        reset_mshr_status()

        env.posedge(100)
    end
}

local test_continuous_read_write = env.register_test_case "test_continuous_read_write" {
    function ()
        env.dut_reset()
        resetFinish:posedge()
        
        env.negedge()
            dirWrite_s3.valid:set(1)
            dirWrite_s3.bits.set:set(0x10)
            dirWrite_s3.bits.meta_tag:set(0x21)
            dirWrite_s3.bits.meta_state:set(MixedState.TC)
            dirWrite_s3.bits.wayOH:set(0x01)
        env.negedge()
            dirWrite_s3.valid:set(0)
            dirRead_s1.valid:set(1)
            dirRead_s1.bits.set:set(0x10)
            dirRead_s1.bits.tag:set(0x21)
            dirRead_s1.bits.replTask:set(0)
        env.negedge()
            dirRead_s1.valid:set(0)
        env.negedge()
            dirResp_s3:dump()
            dirResp_s3.valid:expect(1)
            dirResp_s3.bits.meta_tag:expect(0x21)
            dirResp_s3.bits.meta_state:expect(MixedState.TC)

        env.posedge(10)
    end
}

-- TODO: local test_repl_policy

verilua "mainTask" {
    function ()
        sim.dump_wave()
        env.dut_reset()

        math.randomseed(os.time())
        
        test_basic_read_write()
        test_miss_use_inv_way()
        test_miss_filter_occupied_way()
        test_repl_resp()
        test_continuous_read_write()

        test_miss_use_inv_way_updateReplacer()

        env.posedge(100)        
        env.TEST_SUCCESS()
    end
}