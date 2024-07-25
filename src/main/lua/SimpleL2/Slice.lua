local utils = require "LuaUtils"
local env = require "env"
local tl = require "TileLink"
local chi = require "CHI"
local verilua = verilua
local assert = assert
local expect = env.expect

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeB = tl.TLOpcodeB
local TLOpcodeC = tl.TLOpcodeC
local TLOpcodeD = tl.TLOpcodeD
local MixedState = tl.MixedState

local OpcodeDAT = chi.OpcodeDAT
local OpcodeREQ = chi.OpcodeREQ
local OpcodeRSP = chi.OpcodeRSP
local OpcodeSNP = chi.OpcodeSNP
local CHIResp = chi.CHIResp

local resetFinish = (cfg.top .. ".u_Slice.dir.io_resetFinish"):chdl()

local tl_a = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
    | user_alias
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_a_", name = "tl_a"}

local tl_b = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
    | data
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_b_", name = "tl_b"}

local tl_c = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | source
    | address
    | data
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_c_", name = "tl_c"}

local tl_d = ([[
    | valid
    | ready
    | data
    | sink
    | source
    | param
    | opcode
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_d_", name = "tl_d"}

local tl_e = ([[
    | valid
    | ready
    | sink
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "io_tl_e_", name = "tl_e"}

tl_a.acquire_block_1 = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.AcquireBlock)
    this.bits.address:set(addr, true)
    this.bits.param:set(param)
    this.bits.source:set(source or 0)
    this.bits.size:set(6) -- 2^6 == 64
end

tl_a.acquire_block = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    env.negedge()
        this.ready:expect(1)
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.AcquireBlock)
        this.bits.address:set(addr, true)
        this.bits.param:set(param)
        this.bits.source:set(source or 0)
        this.bits.size:set(6) -- 2^6 == 64
    env.negedge()
        this.valid:set(0)
    env.negedge()
end

tl_a.acquire_perm_1 = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.AcquirePerm)
    this.bits.address:set(addr, true)
    this.bits.param:set(param)
    this.bits.source:set(source or 0)
    this.bits.size:set(6) -- 2^6 == 64
end

tl_a.acquire_perm = function (this, addr, param, source)
    assert(addr ~= nil)
    assert(param ~= nil)

    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.AcquirePerm)
        this.bits.address:set(addr, true)
        this.bits.param:set(param)
        this.bits.source:set(source or 0)
        this.bits.size:set(6) -- 2^6 == 64
    env.negedge()
        this.valid:set(0)
end

tl_a.get_1 = function (this, addr, source)
    assert(addr ~= nil)

    this.valid:set(1)
    this.bits.opcode:set(TLOpcodeA.Get)
    this.bits.address:set(addr, true)
    this.bits.param:set(0)
    this.bits.source:set(source or 0)
    this.bits.size:set(6) -- 2^6 == 64
end

tl_a.get = function (this, addr, source)
    assert(addr ~= nil)

    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.Get)
        this.bits.address:set(addr, true)
        this.bits.param:set(0)
        this.bits.source:set(source or 0)
        this.bits.size:set(6) -- 2^6 == 64
    env.negedge()
        this.valid:set(0)
    env.negedge()
end

tl_c.release_data = function (this, addr, param, source, data_str_0, data_str_1)
    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeC.ReleaseData)
        this.bits.param:set(param)
        this.bits.size:set(6)
        this.bits.source:set(source)
        this.bits.address:set(addr, true)
        this.bits.data:set_str(data_str_0)
    env.negedge()
        this.bits.data:set_str(data_str_1)
    env.negedge()
        this.valid:set(0)
    env.negedge()
end

tl_c.release = function (this, addr, param, source)
    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeC.Release)
        this.bits.param:set(param)
        this.bits.size:set(6)
        this.bits.source:set(source)
        this.bits.address:set(addr, true)
    env.negedge()
        this.valid:set(0)
    env.negedge()
end

tl_c.probeack_data = function (this, addr, param, data_str_0, data_str_1, source)
    env.negedge()
        this.ready:expect(1)
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeC.ProbeAckData)
        this.bits.param:set(param)
        this.bits.source:set(source)
        this.bits.size:set(6)
        this.bits.address:set(addr, true)
        this.bits.data:set_str(data_str_0)
    env.negedge()
        this.ready:expect(1)
        this.bits.data:set_str(data_str_1)
    env.negedge()
        this.valid:set(0)
    env.negedge()
end

tl_c.probeack = function (this, addr, param, source)
    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeC.ProbeAck)
        this.bits.param:set(param)
        this.bits.source:set(source)
        this.bits.size:set(5)
        this.bits.address:set(addr, true)
    env.negedge()
        this.valid:set(0)
    env.negedge()
end


tl_e.grantack = function (this, sink)
    env.negedge()
        this.valid:set(1)
        this.bits.sink:set(sink)
    env.negedge()
        this.valid:set(0)
end

local mpReq_s2 = ([[
    | valid
    | opcode
    | set
    | tag
]]):bundle {hier = cfg.top .. ".u_Slice.reqArb", is_decoupled = true, prefix = "io_mpReq_s2_", name = "mpReq_s2"}

local chi_txreq = ([[
    | valid
    | ready
    | addr
    | opcode
    | txnID
    | addr
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txreq_", name = "chi_txreq"}

local chi_txdat = ([[
    | valid
    | ready
    | opcode
    | resp
    | respErr
    | txnID
    | dbID
    | dataID
    | data
    | be
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txdat_", name = "chi_txdat"}

local chi_txrsp = ([[
    | valid
    | ready
    | opcode
    | resp
    | txnID
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txrsp_", name = "chi_txrsp"}

local chi_rxrsp = ([[
    | valid
    | ready
    | opcode
    | txnID
    | dbID
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxrsp_", name = "chi_rxrsp"}

chi_rxrsp.comp = function (this, txn_id, db_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.Comp)
        chi_rxrsp.bits.dbID:set(db_id)
        chi_rxrsp.valid:set(1)
    env.negedge()
        chi_rxrsp.valid:set(0)
end

chi_rxrsp.comp_dbidresp = function (this, txn_id, db_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.CompDBIDResp)
        chi_rxrsp.bits.dbID:set(db_id)
        chi_rxrsp.valid:set(1)
    env.negedge()
        chi_rxrsp.valid:set(0)
end

local chi_rxdat = ([[
    | valid
    | ready
    | opcode
    | dataID
    | resp
    | data
    | txnID
    | dbID
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxdat_", name = "rxdat"}

chi_rxdat.compdat = function (this, txn_id, data_str_0, data_str_1, dbID, resp)
    local dbID = dbID or 0
    local resp = resp or CHIResp.I
    env.negedge()
        chi_rxdat.bits.txnID:set(txn_id)
        chi_rxdat.bits.dataID:set(0)
        chi_rxdat.bits.opcode:set(OpcodeDAT.CompData)
        chi_rxdat.bits.data:set_str(data_str_0)
        chi_rxdat.bits.dbID:set(dbID)
        chi_rxdat.bits.resp:set(resp)
        chi_rxdat.valid:set(1)
    env.negedge()
        chi_rxdat.bits.data:set_str(data_str_1)
        chi_rxdat.bits.dataID:set(2) -- last data beat
    env.negedge()
        chi_rxdat.valid:set(0)
end

local chi_rxsnp = ([[
    | valid
    | ready
    | addr
    | opcode
    | txnID
    | retToSrc
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxsnp_", name = "chi_rxsnp"}

chi_rxsnp.snpshared = function (this, addr, txn_id, ret2src)
    local addr = bit.rshift(addr, 3) -- Addr in CHI SNP channel has 3 fewer bits than full address
    env.negedge()
        chi_rxsnp.bits.txnID:set(txn_id)
        chi_rxsnp.bits.addr:set(addr, true)
        chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpShared)
        chi_rxsnp.bits.retToSrc:set(ret2src)
        chi_rxsnp.valid:set(1)
    env.posedge()
        chi_rxsnp.ready:expect(1)
    env.negedge()
        chi_rxsnp.valid:set(0)
end

chi_rxsnp.snpunique = function (this, addr, txn_id, ret2src)
    local addr = bit.rshift(addr, 3) -- Addr in CHI SNP channel has 3 fewer bits than full address
    env.negedge()
        chi_rxsnp.ready:expect(1)
        chi_rxsnp.bits.txnID:set(txn_id)
        chi_rxsnp.bits.addr:set(addr, true)
        chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpUnique)
        chi_rxsnp.bits.retToSrc:set(ret2src)
        chi_rxsnp.valid:set(1)
    env.negedge()
        chi_rxsnp.valid:set(0)
end

local mp_dirResp = ([[
    | valid
    | bits_hit => hit
    | bits_wayOH => way
    | {p}_state => state
    | {p}_clientsOH => clientsOH
]]):abdl {hier = cfg.top .. ".u_Slice.mainPipe", is_decoupled = true, prefix = "io_dirResp_s3_", name = "mp_dirResp", p = "bits_meta"}

local TXDAT = ("0b0001"):number()
local SourceD = ("0b0010"):number()
local TempDataStorage = ("0b0100"):number()

local slice = dut.u_Slice
local mp = slice.mainPipe
local ms = slice.missHandler
local dir = slice.dir
local ds = slice.ds
local tempDS = slice.tempDS
local sourceD = slice.sourceD
local txrsp = slice.txrsp
local txdat = slice.txdat
local sinkC = slice.sinkC
local reqArb = slice.reqArb
local rxdat = slice.rxdat

local mshrs = {}
for i = 0, 15 do
    mshrs[i] = ms["mshrs_" .. i]
end

local mpReq_s2 = ([[
    | valid
    | opcode
    | channel
    | source
    | set
    | tag
    | snpHitWriteBack
]]):bundle {hier = mp:name(), prefix = "io_mpReq_s2_", name = "mpReq_s2"}

local function write_dir(set, wayOH, tag, state, clientsOH)
    assert(type(set) == "number")
    assert(type(wayOH) == "number" or type(wayOH) == "cdata")
    assert(type(tag) == "number")
    assert(type(state) == "number")

    local clientsOH = clientsOH or ("0b00"):number()
    
    env.negedge()
        dir:force_all()
            dir.io_dirWrite_s3_valid:set(1)
            dir.io_dirWrite_s3_bits_set:set(set)
            dir.io_dirWrite_s3_bits_wayOH:set(wayOH)
            dir.io_dirWrite_s3_bits_meta_tag:set(tag)
            dir.io_dirWrite_s3_bits_meta_state:set(state)
            dir.io_dirWrite_s3_bits_meta_aliasOpt:set(0)
            dir.io_dirWrite_s3_bits_meta_clientsOH:set(clientsOH)
    
    env.negedge()
        dir:release_all()
    
    env.posedge()
end

local function write_ds(set, wayOH, data_str)
    assert(type(set) == "number")
    assert(type(wayOH) == "number" or type(wayOH) == "cdata")
    assert(type(data_str) == "string")

    env.negedge()
        ds:force_all()
            ds.io_dsWrite_s2_valid:set(1)
            ds.io_dsWrite_s2_bits_set:set(set)
            ds.io_dsWrite_s2_bits_data:set_str(data_str)

    env.negedge()
        ds:release_all()
        ds:force_all()
            ds.io_fromMainPipe_dsWrWayOH_s3_valid:set(1)
            ds.io_fromMainPipe_dsWrWayOH_s3_bits:set(wayOH)
    
    env.negedge()
        ds.io_fromMainPipe_dsWrWayOH_s3_valid:set(0)
        ds:release_all()
    
    env.posedge()
end

local function write_sinkC_respDestMap(mshrId, set, tag, wayOH, isTempDS, isDS)
    env.negedge()
        dut:force_all()
        sinkC.io_respDest_s4_valid:set(1)
        sinkC.io_respDest_s4_bits_mshrId:set(mshrId)
        sinkC.io_respDest_s4_bits_set:set(set)
        sinkC.io_respDest_s4_bits_tag:set(tag)
        sinkC.io_respDest_s4_bits_wayOH:set(wayOH)
        sinkC.io_respDest_s4_bits_isTempDS:set(isTempDS)
        sinkC.io_respDest_s4_bits_isDS:set(isDS)
    env.negedge()
        sinkC.io_respDest_s4_valid:set(0)
        dut:release_all()
end

local function to_address(set, tag)
    local offset_bits = 6
    local set_bits = mp.task_s3_set.get_width()
    local tag_bits = mp.task_s3_tag.get_width()

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

local test_replay_valid = env.register_test_case "test_replay_valid" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        slice.io_tl_d_ready:set(1)
        ms.io_mshrAlloc_s3_ready:set_force(0)

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 8) -- set = 0x00, tag = 0x04
        env.posedge()
            tl_a.ready:expect(1)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()
        
        env.posedge()
            expect.equal(mp.valid_s3:get(), 1)
            expect.equal(mp.task_s3_opcode:get(), TLOpcodeA.AcquireBlock)
            expect.equal(mp.task_s3_param:get(), TLParam.NtoT)
            expect.equal(mp.task_s3_source:get(), 8)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_mshrAlloc_s3_ready:get(), 0)

        env.posedge()
            expect.equal(mp.valid_replay_s4:get(), 1) -- replay valid

        ms.io_mshrAlloc_s3_ready:set_release()
        env.posedge(100)
    end
}

local test_load_to_use = env.register_test_case "test_load_to_use" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        slice.io_tl_d_ready:set(1)

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 8)
        env.negedge()
            tl_a.valid:set(0)

        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()

        env.posedge()
            expect.equal(mp.valid_s3:get(), 1)
            expect.equal(mp.task_s3_opcode:get(), TLOpcodeA.AcquireBlock)
            expect.equal(mp.task_s3_param:get(), TLParam.NtoT)
            expect.equal(mp.task_s3_source:get(), 8)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)
            expect.equal(ds.io_fromMainPipe_dsRead_s3_valid:get(), 1)

        env.posedge()
            -- expect.equal(mp.valid_s4:get(), 1) -- resp valid
            -- expect.equal(ds.ren_s4:get(), 1)
        
        env.posedge()
        --    expect.equal(ds.ren_s5:get(), 1)

        env.posedge()
            sourceD.io_d_ready:expect(1)
            sourceD.io_d_valid:expect(1)
            sourceD.io_d_bits_data:dump()
            sourceD.io_d_bits_opcode:expect(TLOpcodeD.GrantData)
            sourceD.io_d_bits_data:expect(0xdead)

        env.posedge()
            sourceD.io_d_ready:expect(1)
            sourceD.io_d_valid:expect(1)
            sourceD.io_d_bits_data:dump()
            sourceD.io_d_bits_opcode:expect(TLOpcodeD.GrantData)
            sourceD.io_d_bits_data:expect(0xbeef)

        env.posedge()
        env.posedge(10, function ()
            tl_d:dump()
            sourceD.io_d_valid:expect(0)
        end)
        
        env.posedge()
    end
}

local test_load_to_use_latency = env.register_test_case "test_load_to_use_latency" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        tl_d.ready:set(1)

        local start_cycle = 0
        local end_cycle = 0

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 8)
        
        start_cycle = env.cycles()

        env.negedge()
            tl_a.valid:set(0)

        local ok = dut.clock:posedge_until(100, function (c)
            return tl_d:fire()
        end)
        assert(ok)
        
        end_cycle = env.cycles()
        print("load_to_use latency => " .. (end_cycle - start_cycle + 1) .. " cycles")
    end
}

local test_load_to_use_stall_simple = env.register_test_case "test_load_to_use_stall_simple" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        tl_d.ready:set(0)

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 8)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge()
            expect.equal(mpReq_s2.valid:get(), 1)
            mpReq_s2:dump()

        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
                end)
                tl_d:dump()

                env.posedge()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                end)
                tl_d:dump()

                print("data check ok!")
            end
        }

        env.negedge(math.random(3, 20))
            tl_d.ready:set(1)
            
        env.posedge(100)
    end
}

local test_load_to_use_stall_complex = env.register_test_case "test_load_to_use_stall_complex" {
    function ()
        env.dut_reset()
        resetFinish:posedge()
        
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        write_dir(0x00, ("0b0001"):number(), 0x05, MixedState.TC)    
        write_ds(0x00, ("0b0001"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        tl_d.ready:set(0)

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 8)
        env.negedge(2)
            tl_a:acquire_block_1(0x14000, TLParam.NtoT, 8)
        env.posedge()
            tl_a.ready:expect(1)
        env.negedge()
            tl_a.valid:set(0)
        
        verilua "appendTasks" {
            check_task = function ()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
                end)

                env.posedge()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                end)

                print("data check ok!")
            end
        }

        env.negedge(math.random(20, 40))
            tl_d.ready:set(1)
        
        env.posedge(300)
    end
}

local test_grantdata_continuous_stall_3 = env.register_test_case "test_grantdata_continuous_stall_3" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        -- 0x20000
        write_dir(0x00, ("0b0100"):number(), 0x08, MixedState.TC)    
        write_ds(0x00, ("0b0100"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead1},
            {s = 256, e = 256 + 63, v = 0xbeef1}
        }, 512))

        -- 0x30000
        write_dir(0x00, ("0b1000"):number(), 0x0C, MixedState.TC)    
        write_ds(0x00, ("0b1000"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead2},
            {s = 256, e = 256 + 63, v = 0xbeef2}
        }, 512))

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_task = function()
                for i = 1, 3 do
                    env.expect_happen_until(10, function (c)
                        return mpReq_s2.valid:get() == 1
                    end)
                    print("get mpRe_s2 at", dut.cycles:get(), "set:", mpReq_s2.bits.set:get_str(HexStr), "tag:", mpReq_s2.bits.tag:get_str(HexStr))
                    env.negedge()
                end
            end,

            check_task_2 = function ()
                local ok = false
                local datas_0 = {0xdead, 0xdead1, 0xdead2}
                local datas_1 = {0xbeef, 0xbeef1, 0xbeef2}
                for i = 1, 3 do
                    env.negedge()
                    env.expect_happen_until(100, function (c) return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) end)
                    tl_d:dump()
                    -- tl_d.bits.data:get()[1] == datas_0[i]

                    env.negedge()
                    env.expect_happen_until(100, function (c) return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) end)
                    tl_d:dump()
                    -- tl_d.bits.data:get()[1] == datas_1[i] 
                end

                env.negedge()
                env.expect_not_happen_until(50, function (c)
                    return tl_d:fire() 
                end)
            end,
        }

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 1)
        env.negedge()
            tl_a:acquire_block_1(0x20000, TLParam.NtoT, 2)
        env.negedge()
            tl_a:acquire_block_1(0x30000, TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge(math.random(5, 20))

        -- TODO:
        env.negedge()
        tl_d.ready:set(1)
        -- for i = 1, 20 do
        --     env.negedge()
        --         tl_d.ready:set(math.random(0, 1))
        -- end
        
        env.posedge(200)
    end
}

local test_grantdata_mix_grant = env.register_test_case "test_grantdata_mix_grant" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC)    
        write_ds(0x00, ("0b0010"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead},
            {s = 256, e = 256 + 63, v = 0xbeef}
        }, 512))

        -- 0x20000
        write_dir(0x00, ("0b0100"):number(), 0x08, MixedState.TC)    
        write_ds(0x00, ("0b0100"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead1},
            {s = 256, e = 256 + 63, v = 0xbeef1}
        }, 512))

        -- 0x30000
        write_dir(0x00, ("0b1000"):number(), 0x0C, MixedState.TC)    
        write_ds(0x00, ("0b1000"):number(), utils.bitpat_to_hexstr({
            {s = 0,   e = 63, v = 0xdead2},
            {s = 256, e = 256 + 63, v = 0xbeef2}
        }, 512))

        tl_d.ready:set(0)

        verilua "appendTasks" {
            check_grantdata = function()
                local function check_grantdata(data) 
                    env.expect_happen_until(100, function (c)
                        return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.GrantData and tl_d.bits.data:get()[1] == data
                    end)
                end
                
                check_grantdata(0xdead)
                check_grantdata(0xbeef)

                check_grantdata(0xdead2)
                check_grantdata(0xbeef2)
            end,

            check_grant = function ()
                env.expect_happen_until(100, function (c)
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.Grant
                end)
            end
        }

        env.negedge()
            tl_a:acquire_block_1(0x10000, TLParam.NtoT, 1)
        env.negedge()
            tl_a:acquire_perm_1(0x20000, TLParam.NtoT, 2)
        env.negedge()
            tl_a:acquire_block_1(0x30000, TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)

        env.posedge(math.random(5, 20))

        for i = 1, 20 do
            env.negedge()
                tl_d.ready:set(math.random(0, 1))
        end

        env.posedge(200)
    end
}

local test_release_write = env.register_test_case "test_release_write" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        verilua "appendTasks" {
            check_release_write = function()
                env.expect_happen_until(100, function (c)
                    return ds.io_dsWrite_s2_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000100"
                end)
            end
        }

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, ("0b01"):number())

        env.negedge()
        tl_c:release_data(to_address(0x00, 0x04), TLParam.TtoN, 0, "0x100", "0x200")

        env.posedge(200)
    end
}

local test_release_continuous_write = env.register_test_case "test_release_continuous_write" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_d.ready:set(1)

        verilua "appendTasks" {
            check_task = function()
                env.expect_happen_until(100, function (c)
                    return ds.io_dsWrite_s2_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000100"
                end)

                env.expect_happen_until(100, function (c)
                    return ds.io_dsWrite_s2_bits_data:get_str(HexStr) == "00000000000000000000000000000000000000000000000000000000000005000000000000000000000000000000000000000000000000000000000000000400"
                end)
            end
        }

        -- 0x10000
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, ("0b01"):number())
        write_sinkC_respDestMap(0, 0x00, 0x04, 0x01, 1, 0)

        tl_c:release_data(to_address(0x00, 0x04), TLParam.TtoN, 0, "0x100", "0x200")
        tl_c:release_data(to_address(0x00, 0x04), TLParam.TtoN, 0, "0x400", "0x500")

        env.posedge(100)
    end
}

local test_sinkA_hit = env.register_test_case "test_sinkA_hit" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        reqArb.blockA_s1:set_force(0)
        dut.io_tl_d_ready = 1

        local sync = ("sync"):ehdl()
        
        verilua "appendTasks" {
            check_dir_write = function ()

                -- [1] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_state:get(), MixedState.TTC)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_clientsOH:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_tag:get(), 4)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)

                -- [2] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_state:get(), MixedState.TTC)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_clientsOH:get(), 2)
                    expect.equal(mp.io_dirWrite_s3_bits_meta_tag:get(), 4)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 0)

                -- [3] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [4] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    -- 
                    -- CacheAlias will hit and with different alias field between 
                    -- task_s3_alias and dirResp_s3_alias 
                    -- 
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.cacheAlias_s3:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [5] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

                -- [6] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.needProbeOnHit_a_s3:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)
                env.dut_reset() -- prevent tempDS entry leak assert

                -- [7] stage 2 & stage 3
                sync:wait()
                env.posedge(2)
                    expect.equal(mp.io_dirResp_s3_bits_hit:get(), 1)
                    expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
                    expect.equal(mp.needProbeOnHit_a_s3:get(), 1)
                    expect.equal(mp.io_dirWrite_s3_valid:get(), 0)
                env.dut_reset() -- prevent tempDS entry leak assert
            end
        }

        -- 
        -- [1] test AcquirePerm.NtoT hit on Tip Clean
        -- 
        print "[1] test AcquirePerm.NtoT hit on Tip Clean"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:acquire_perm_1(to_address(0, 4), TLParam.NtoT, 1)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(math.random(5, 10))

        -- 
        -- [2] test AcquireBlock.NtoT hit on Tip Clean
        -- 
        print "[2] test AcquireBlock.NtoT hit on Tip Clean"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:acquire_block_1(to_address(0, 4), TLParam.NtoT, 28) -- source = 28 ==> clientsOH = "0b10"
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(math.random(5, 10))

        -- 
        -- [3] test Get hit on Tip Clean
        -- 
        print "[3] test Get hit on Tip Clean"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 0)
        env.negedge()
            tl_a:get_1(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        -- 
        -- [4] test AcquireBlock hit on Tip Clean but met CacheAlias
        -- 
        print "[4] test AcquireBlock hit on Tip Clean but met CacheAlias"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TC, 2)
        env.negedge()
            tl_a:acquire_block_1(to_address(0, 4), TLParam.NtoT, 28)
            tl_a.bits.user_alias:set(1)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)

        -- 
        -- [5] test AcquireBlock.NtoT hit and need read downward
        --
        print "[5] test AcquireBlock.NtoT hit and need read downward"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.BC, 0)
        env.negedge()
            tl_a:acquire_block_1(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        env.posedge(20)
        
        -- 
        -- [6] test AcquireBlock.NtoT hit and need probe upward
        --
        print "[6] test AcquireBlock.NtoT hit and need probe upward"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, 1)
        env.negedge()
            tl_a:acquire_block_1(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        resetFinish:posedge()

        env.posedge(200)

        -- 
        -- [7] test Get hit and need probe upward
        --
        print "[7] test Get hit and need probe upward"
        write_dir(0x00, ("0b0010"):number(), 0x04, MixedState.TTC, 1)
        env.negedge()
            tl_a:get_1(to_address(0, 4), 28)
        env.negedge()
            tl_a.valid:set(0)
        sync:send()

        reqArb.blockA_s1:set_release()
        env.posedge(100)
    end
}

local test_sinkA_miss = env.register_test_case "test_sinkA_miss" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 
        -- [1] test AcquireBlock miss
        -- 
        env.negedge()
            tl_a:acquire_block_1(to_address(0, 4), TLParam.NtoT, 28)
        env.negedge()
            tl_a.valid:set(0)
        env.posedge(2)
            expect.equal(mp.io_dirResp_s3_bits_hit:get(), 0)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_dirWrite_s3_valid:get(), 0)

        -- 
        -- [2] test Get miss
        -- 
        env.negedge()
            tl_a:get_1(to_address(0, 4), 28)
        env.negedge()
            tl_a.valid:set(0)
        env.posedge(2)
            expect.equal(mp.io_dirResp_s3_bits_hit:get(), 0)
            expect.equal(mp.io_mshrAlloc_s3_valid:get(), 1)
            expect.equal(mp.io_dirWrite_s3_valid:get(), 0)
        
        env.posedge(100)
    end
}

local test_release_hit = env.register_test_case "test_release_hit" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        -- 
        -- [1] test ReleaseData.TtoN hit
        -- 
        verilua "appendTasks" {
            check_dir_resp = function ()
                env.expect_happen_until(100, function ()
                    return mp.io_dirResp_s3_valid:get() == 1
                end)
                mp.io_dirResp_s3_valid:expect(1)
                mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
                mp.io_dirResp_s3_bits_hit:expect(1)
                mp.isRelease_s3:expect(1)
                mp.valid_s3:expect(1)
                mp.io_dirWrite_s3_valid:expect(1)
                mp.io_dirWrite_s3_bits_meta_tag:expect(0)
                mp.io_dirWrite_s3_bits_meta_clientsOH:expect(0x00)
                mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TD)
            end
        }

        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TTC, 1)
        tl_c:release_data(0x00, TLParam.TtoN, 1, "0xdead", "0xbeef")
        env.posedge(20)

        -- 
        -- [2] test Release.TtoB hit on TC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TC, ("0b11"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.TtoB)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x3)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b11"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TC)

        -- 
        -- [3] test Release.TtoB hit on TTC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.TTC, ("0b01"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.TtoB)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b01"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.TC)

        -- 
        -- [4] test Release BtoN hit on BBC
        -- 
        write_dir(0x00, ("0b0010"):number(), 0x00, MixedState.BC, ("0b01"):number())
        env.negedge()
            tl_c.bits.address:set_str("0x00")
            tl_c.bits.opcode:set(TLOpcodeC.Release)
            tl_c.bits.source:set(1)
            tl_c.bits.param:set(TLParam.BtoN)
            tl_c.bits.size:set(5)
            tl_c.valid:set(1)
        env.negedge()
            tl_c.valid:set(0)
        env.negedge()
        env.posedge()
            mp.io_dirResp_s3_valid:expect(1)
            mp.io_dirResp_s3_bits_meta_clientsOH:expect(0x01)
            mp.io_dirResp_s3_bits_hit:expect(1)
            mp.isRelease_s3:expect(1)
            mp.valid_s3:expect(1)
            mp.io_dirWrite_s3_valid:expect(1)
            mp.io_dirWrite_s3_bits_meta_tag:expect(0)
            mp.io_dirWrite_s3_bits_meta_clientsOH:expect(("0b00"):number())
            mp.io_dirWrite_s3_bits_meta_state:expect(MixedState.BC)

        env.posedge(100)
    end
}

local test_sinkA_miss = env.register_test_case "test_sinkA_miss" {
    function ()
        local sync = ("sync"):ehdl()
        
        env.dut_reset()
        resetFinish:posedge()

        dut.io_tl_d_ready:set(0)
        dut.io_chi_txreq_ready:set(1)
        dut.io_chi_txrsp_ready:set(1)

        env.negedge()
            tl_a:acquire_block_1(to_address(0x10, 0x20), TLParam.NtoT, 3)
        env.negedge()
            tl_a.valid:set(0)
        
        env.posedge(2)
            mp.io_dirResp_s3_bits_hit:expect(0)
        
        env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
        mshrs[0].io_status_valid:expect(1)
        mshrs[0].io_status_set:expect(0x10)
        mshrs[0].io_status_reqTag:expect(0x20)
        mshrs[0].state_w_compdat:expect(0)
        for i = 1, 15 do
            mshrs[i].io_status_valid:expect(0)
        end

        verilua "appendTasks" {
            check_mshr_signals = function ()
                env.expect_happen_until(50, function() return mshrs[0].state_w_compdat:is(1) end)
                env.expect_happen_until(50, function() return mshrs[0].state_s_compack:is(1) end)
                env.expect_happen_until(50, function() return mshrs[0].willFree:is(1) end)
            end,

            check_mainpipe = function ()
                env.expect_happen_until(50, function ()
                    return mp.io_dirWrite_s3_valid:get() == 1 and 
                            mp.io_dirWrite_s3_bits_meta_tag:get() == 0x20 and 
                            mp.io_dirWrite_s3_bits_meta_clientsOH:get() == 0x01 and 
                            mp.io_dirWrite_s3_bits_meta_state:get() == MixedState.TTC
                end)
            end,

            check_channel = function ()
                -- 
                -- check grant data
                -- 
                env.expect_happen_until(50, function () return tl_d:fire() end)
                tl_d:dump()
                tl_d.bits.source:expect(3)
                tl_d.bits.sink:expect(0)
                tl_d.bits.opcode:expect(TLOpcodeD.GrantData)
                tl_d.bits.param:expect(TLParam.toT)
                expect.equal(tl_d.bits.data:get()[1], 0xdead)

                env.negedge()
                env.expect_happen_until(50, function () return tl_d:fire() end)
                tl_d:dump()
                expect.equal(tl_d.bits.data:get()[1], 0xbeef)

                local sink = tl_d.bits.sink:get()

                -- 
                -- send grant ack
                -- 
                tl_e:grantack(sink)

                env.expect_not_happen_until(30, function ()
                    return tl_d:fire()
                end)

                mshrs[0].io_status_valid:expect(0)

                sync:send()
            end
        }

        -- send data to l2cache
        chi_rxdat:compdat(0, "0xdead", "0xbeef", CHIResp.UC)
        
        env.negedge(math.random(10, 20))
            dut.io_tl_d_ready:set(1)

        sync:wait()

        -- read back data from DataStorage
        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_fromMainPipe_dsRead_s3_valid:set(1)
            ds.io_fromMainPipe_dsRead_s3_bits_dest:set(SourceD)
            ds.io_fromMainPipe_dsRead_s3_bits_set:set(0x10)
            ds.io_fromMainPipe_dsRead_s3_bits_wayOH:set(0x01)
        env.negedge() -- s4
            dut:release_all()
        env.negedge() -- s5
        env.negedge() -- s6
            ds.io_toSourceD_dsResp_s6s7_valid:expect(1)
            expect.equal(ds.io_toSourceD_dsResp_s6s7_bits_data:get(), 0xdead)
            sourceD.io_data_s6s7_valid:set_force(0)
        env.negedge(10)
            sourceD.io_data_s6s7_valid:set_release()

        env.dut_reset()
        env.negedge(100)
    end
}

local test_acquire_and_release = env.register_test_case "test_acquire_and_release" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_d.ready:set(1)
        chi_txrsp.ready:set(1)
        chi_txreq.ready:set(1)

        -- 
        -- send acquire block
        -- 
        verilua "appendTasks" {
            check_mp = function ()
                env.expect_happen_until(100, function ()
                    return mp_dirResp.valid:get() == 1
                end)
                mp_dirResp:dump()
                mp_dirResp.hit:expect(0)
                mp_dirResp.state:expect(MixedState.I)

                env.expect_happen_until(100, function ()
                    return mp.io_dirWrite_s3_valid:get() == 1 and 
                            mp.io_dirWrite_s3_bits_meta_tag:get() == 0x20 and 
                            mp.io_dirWrite_s3_bits_meta_state:get() == MixedState.TTC and
                            mp.io_dirWrite_s3_bits_meta_clientsOH:get() == 0x01
                end)
            end,

            check_d_resp = function ()
                env.expect_happen_until(100, function ()
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead and tl_d.bits.param:get() == TLParam.toT
                end)
                tl_d:dump()

                env.expect_happen_until(100, function ()
                    return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef and tl_d.bits.param:get() == TLParam.toT
                end)
                tl_d:dump()
            end,
        }
        
        tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, 3)
        
        env.negedge(math.random(10, 20))
        chi_rxdat:compdat(0, "0xdead", "0xbeef", CHIResp.UC)

        env.negedge(math.random(10, 20))
        mshrs[0].io_status_valid:expect(1)
        tl_e:grantack(0)

        env.negedge(math.random(10, 20))
        mshrs[0].io_status_valid:expect(0)


        -- 
        -- read back data from DataStorage
        -- 
        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return ds.io_toTempDS_write_s5_bits_data:get() == 0xdead
                end)
            end
        }

        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_fromMainPipe_dsRead_s3_valid:set(1)
            ds.io_fromMainPipe_dsRead_s3_bits_dest:set(SourceD)
            ds.io_fromMainPipe_dsRead_s3_bits_set:set(0x10)
            ds.io_fromMainPipe_dsRead_s3_bits_wayOH:set(0x01)
        env.negedge()
            dut:release_all()
            sourceD.io_data_s6s7_valid:set_force(0)
            ds.ren_s6:set_force(0)
        env.negedge(10)
            sourceD.io_data_s6s7_valid:set_release()
            ds.ren_s6:set_release()


        -- 
        -- send release data
        -- 
        verilua "appendTasks" {
            check_c_resp = function ()
                env.expect_happen_until(100, function ()
                    return tl_d:fire() and tl_d.bits.opcode:get() == TLOpcodeD.ReleaseAck and tl_d.bits.source:get() == 11
                end)

                env.posedge()
                env.expect_not_happen_until(100, function ()
                    return tl_d:fire()
                end)
            end
        }
        env.negedge()
        tl_c:release_data(to_address(0x10, 0x20), TLParam.TtoN, 11, "0x100", "0x200")

        env.negedge(20)

        -- 
        -- read back data from DataStorage
        -- 
        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return ds.io_toTempDS_write_s5_bits_data:get() == 0x100
                end)
                ds.io_toTempDS_write_s5_bits_data:dump()
            end
        }

        env.negedge(math.random(1, 10))
            dut:force_all()
            ds.io_fromMainPipe_dsRead_s3_valid:set(1)
            ds.io_fromMainPipe_dsRead_s3_bits_dest:set(SourceD)
            ds.io_fromMainPipe_dsRead_s3_bits_set:set(0x10)
            ds.io_fromMainPipe_dsRead_s3_bits_wayOH:set(0x01)
        env.negedge()
            dut:release_all()
            sourceD.io_data_s6s7_valid:set_force(0)
            ds.ren_s6:set_force(0)
        env.negedge(10)
            sourceD.io_data_s6s7_valid:set_release()
            ds.ren_s6:set_release()

        env.posedge(200)
    end
}

local test_probe_toN = env.register_test_case "test_probe_toN" {
    function ()
        env.dut_reset()
        resetFinish:posedge()
        
        tl_b.ready:set(1)
        tl_d.ready:set(1)
        chi_txrsp.ready:set(1)
        chi_txreq.ready:set(1)

        do
            print "core 1 acquire"
            local source = 28 -- core 1 source
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, source) -- core 1 acquire, source = 3
            
            -- wait txreq
            env.expect_happen_until(20, function ()
                return chi_txreq.valid:get() == 1 and chi_txreq.bits.opcode:get() == OpcodeREQ.ReadUnique
            end)
            chi_txreq:dump()

            env.negedge(5)
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5

            env.expect_happen_until(20, function ()
                return chi_txrsp.valid:get() == 1
            end)
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5
            chi_txrsp:dump()

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xdead
            end)

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xbeef
            end)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)
        end

        do
            print "core 0 acquire"
            local source = 1 -- core 0 source
            
            env.negedge(10)
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, source) -- core 0 acquire
            
            env.expect_happen_until(20, function ()
                return mp_dirResp.valid:get() == 1 and mp_dirResp.hit:get() == 1 and mp_dirResp.state:get() == MixedState.TTC
            end)
            mp_dirResp:dump()
            mp.needProbeOnHit_a_s3:expect(1)
            env.negedge()

            -- wait Probe.toN
            env.expect_happen_until(20, function ()
                return tl_b:fire() and tl_b.bits.address:is(to_address(0x10, 0x20)) and tl_b.bits.opcode:is(TLOpcodeB.Probe) and tl_b.bits.param:is(TLParam.toN)
            end)
            tl_b:dump()

            -- send ProbeAck.TtoN(data is not modified in core 1)
            env.negedge(5)
            tl_c:probeack(to_address(0x10, 0x20), TLParam.TtoN, 28)

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xdead
            end)

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xbeef
            end)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)

            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)

        end

        env.negedge(200)

        do
            print "core 1 acquire back"
            local source = 28 -- core 1 source
            
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, source) -- core 1 acquire

            env.expect_happen_until(20, function ()
                return mp_dirResp.valid:get() == 1 and mp_dirResp.hit:get() == 1 and mp_dirResp.state:get() == MixedState.TTC
            end)
            mp_dirResp:dump()
            mp.needProbeOnHit_a_s3:expect(1)

            -- wait Probe.toN
            env.expect_happen_until(20, function ()
                return tl_b:fire() and tl_b.bits.address:is(to_address(0x10, 0x20)) and tl_b.bits.opcode:is(TLOpcodeB.Probe) and tl_b.bits.param:is(TLParam.toN)
            end)
            tl_b:dump()

            -- send ProbeAckData.TtoN(data is modified in core 0)
            env.negedge(5)
            tl_c:probeack_data(to_address(0x10, 0x20), TLParam.TtoN, "0xabab", "0xefef", 0)

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xabab
            end)
            tl_d:dump()

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xefef
            end)
            tl_d:dump()
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)

            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)

        end

        -- env.dut_reset()
        env.posedge(200)
    end
}

local test_acquire_perm_and_probeack_data = env.register_test_case "test_acquire_perm_and_probeack_data" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1)
        tl_d.ready:set(1)
        chi_txrsp.ready:set(1)
        chi_txreq.ready:set(1)
        -- TODO: AcquirePerm trig Probe and the ProbeAckData response will be saved in DataStorage.

        do
            local source = 1 -- core 0
            tl_a:acquire_perm(to_address(0x10, 0x20), TLParam.NtoT, source)
            
            -- wait txreq
            env.expect_happen_until(20, function ()
                return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique)
            end)
            chi_txreq:dump()

            env.negedge(5)
            chi_rxdat:compdat(0, "0x1234", "0x4567", 5, CHIResp.UC) -- dbID = 5

            env.expect_happen_until(20, function ()
                return chi_txrsp:fire()
            end)
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5
            chi_txrsp:dump()

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(20, function ()
                        return mp.valid_s3:is(1) and mp.task_s3_isMshrTask:is(1) and
                                mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01) and
                                mp.io_dirWrite_s3_bits_meta_tag:is(0x20) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC)
                    end)
                end,

                function ()
                    env.expect_happen_until(20, function ()
                        return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.opcode:is(TLOpcodeD.Grant)
                    end)
                    tl_d:dump()
                    local sink = tl_d.bits.sink:get()
        
                    tl_e:grantack(sink)
                end
            }

            env.negedge(200)
        end

        env.negedge(200)

        do
            local source = 28 -- core 1
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, source)

            -- wait Probe.toN
            env.expect_happen_until(20, function ()
                return tl_b:fire() and tl_b.bits.address:is(to_address(0x10, 0x20)) and tl_b.bits.opcode:is(TLOpcodeB.Probe) and tl_b.bits.param:is(TLParam.toN)
            end)
            tl_b:dump()

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(20, function ()
                        return sinkC.io_toTempDS_write_valid:is(1)
                    end)
                end,
                function ()
                    env.expect_happen_until(10, function ()
                        return sinkC.io_dsWrite_s2_valid:is(1) -- ProbeAckData is written into DataStorage
                    end)
                end,
            }
            
            -- send ProbeAckData.TtoN(data is modified in core 0)
            env.negedge(5)
            tl_c:probeack_data(to_address(0x10, 0x20), TLParam.TtoN, "0xabab", "0xefef", 0)

            -- TODO: add noData state for Directory Entry
            -- verilua "appendTasks" {
            --     function ()
            --         env.expect_not_happen_until(100, function ()
            --             return tempDS.io_toDS_dsWrite_valid:is(1) -- TempDataStorage is not expected to wrtite data into DataStorage
            --         end)
            --     end
            -- }

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xabab
            end)
            tl_d:dump()

            env.expect_happen_until(20, function ()
                return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xefef
            end)
            tl_d:dump()
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)

            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        env.posedge(200)
    end
}

local test_probe_toB = env.register_test_case "test_probe_toB" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        local finish = false

        verilua "appendTasks" {
            monitor_dirResp = function ()
                repeat
                    if mp_dirResp.valid:is(1) then
                        mp_dirResp:dump()
                    end
                    env.posedge()
                until finish
            end
        }

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1)

        do
            print "core 1 AcquireBlock.NtoT"
            local source = 28 -- core 1 source
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, source) -- core 1 acquire, source = 28
            env.posedge()
                chi_txreq.valid:posedge(); env.negedge()
                chi_txreq.bits.opcode:expect(OpcodeREQ.ReadUnique)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                chi_txrsp.valid:posedge(); env.negedge()
                chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

            env.expect_happen_until(20, function () return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xdead end)
            env.expect_happen_until(20, function () return tl_d:fire() and tl_d.bits.source:is(source) and tl_d.bits.data:get()[1] == 0xbeef end)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)
        end

        env.posedge(200)

        -- resp with ProbeAckData.TtoB
        do
            print "core 0 AcquireBlock.NtoB"
            local source = 3 -- core 0 source
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoB, source) -- core 0 acquire, source = 3
            env.posedge()
                tl_b.valid:posedge(); env.negedge()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe); tl_b.bits.param:expect(TLParam.toB); tl_b.bits.address:expect(to_address(0x10, 0x20))
            env.posedge(5)
                tl_c:probeack_data(to_address(0x10, 0x20), TLParam.TtoB, "0xabab", "0xefef", 28)

            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(100, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(("0b11"):number()) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TD)
                    end)
                end
            }

            env.posedge()
                tl_d.valid:posedge(); env.negedge()
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xabab)
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xefef)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        env.posedge(200)

        do
            print "core 1 AcquireBlock.BtoT"
            local source = 28 -- core 1 source
            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.BtoT, source)
            env.posedge()
                tl_b.valid:posedge(); env.negedge()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe); tl_b.bits.param:expect(TLParam.toN); tl_b.bits.address:expect(to_address(0x10, 0x20))
            env.posedge(5)
                tl_c:probeack(to_address(0x10, 0x20), TLParam.BtoN, 3)

            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(100, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(("0b10"):number()) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTD)
                    end)
                end
            }

            env.posedge()
                tl_d.valid:posedge(); env.negedge()
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xabab)
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xefef)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        env.posedge(200)
        finish = true
    end
}

local test_get_miss = env.register_test_case "test_get_miss" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1)

        -- hit need read downward
        do
            local source = 33 -- core 1 ICache
            env.negedge()
                tl_a:get(to_address(0x10, 0x20), source)

            env.posedge()
                chi_txreq.valid:posedge(); env.negedge()
                chi_txreq.bits.opcode:expect(OpcodeREQ.ReadNotSharedDirty)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                chi_txrsp.valid:posedge(); env.negedge()
                chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(100, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(("0b00"):number()) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TC)
                    end)
                    mp.io_dirWrite_s3_bits_meta_state:dump()
                end
            }

            env.posedge()
                tl_d.valid:posedge(); env.negedge()
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xbeef)

            env.negedge()
            verilua "appendTasks" {
                check_no_tl_d = function ()
                    env.expect_not_happen_until(100, function ()
                        return tl_d:fire()
                    end)
                end,
            }
            
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end
        
        env.posedge(200)
    end
}

local test_get_hit = env.register_test_case "test_get_hit" {
    function (case)
        env.dut_reset()
        resetFinish:posedge()

        print("case => " .. case)

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1)

        -- hit need read downward
        do
            local source = 33 -- core 0 ICache
            env.negedge()
                tl_a:get(to_address(0x10, 0x20), source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() end)
                chi_txreq.bits.opcode:expect(OpcodeREQ.ReadNotSharedDirty)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5
            env.posedge()
                env.expect_happen_until(10, function () return tl_d:fire() end); tl_d:dump()
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xbeef); tl_d:dump()
        end

        -- normal hit
        do
            verilua "appendTasks" {
                hit_latency = function ()
                    local s, e = 0, 0
                    tl_a.valid:posedge()
                    s = env.cycles()
                    
                    tl_d.valid:posedge()
                    e = env.cycles()
                    
                    print("Get hit latency => " .. (e -s))
                end
            }

            local source = 3 -- core 0 DCache
            local sink = nil
            env.negedge(10)
                tl_a:get(to_address(0x10, 0x20), source)
            env.negedge()
                env.expect_happen_until(10, function () return tl_d:fire() end); tl_d:dump()
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
                sink = tl_d.bits.sink:get()
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xbeef); tl_d:dump()
            env.negedge()
                tl_e:grantack(sink)
        end

        env.posedge(100)

        -- acquire hit
        do
            verilua "appendTasks" {
                hit_latency = function ()
                    local s, e = 0, 0
                    env.expect_happen_until(10, function () return tl_a:fire() end)
                    s = env.cycles()
                    
                    env.expect_happen_until(10, function () return tl_d:fire() end)
                    e = env.cycles()
                    
                    print("AcquireBlock hit latency => " .. (e -s))
                end,
                check_no_alloc_mshr = function()
                    env.expect_not_happen_until(10, function ()
                        return mp.io_mshrAlloc_s3_valid:is(1)
                    end)
                end
            }

            local source = 28 -- core 1 DCache
            local sink = nil
            local address = to_address(0x10, 0x20)
            env.negedge()
                tl_a:acquire_block(address, TLParam.NtoT, source)
            env.posedge()
                env.expect_happen_until(10, function() return tl_d:fire() end)
                tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
                sink = tl_d.bits.sink:get()
            env.negedge()
                expect.equal(tl_d.bits.data:get()[1], 0xbeef)
            env.negedge()
                tl_e:grantack(sink)
            env.negedge(20)
        end

        -- hit need probe
        do
            local source = 48 -- core 1 ICache
            local address = to_address(0x10, 0x20)
            print(("address: 0x%x"):format(address))
            env.negedge()
                tl_a:get(address, source)
            env.negedge()
                env.expect_happen_until(10, function () return tl_b:fire() end)
                tl_b.bits.opcode:expect(TLOpcodeB.Probe); tl_b.bits.param:expect(TLParam.toB); tl_b.bits.address:expect(address); tl_b.bits.source:expect(16) -- source = 16 ==> Core 1
            env.posedge(5)
        
            env.mux_case(case) {
                probeack_data = function()
                    tl_c:probeack_data(address, TLParam.TtoB, "0xabab", "0xefef", 28) -- TODO: ProbeAck.TtoB
                end,
                probeack = function()
                    tl_c:probeack(address, TLParam.TtoN, 28)
                end
            }
            
            env.mux_case(case) {
                probeack_data = function()
                    verilua "appendTasks" {
                        check_wb_dir = function ()
                            env.expect_happen_until(100, function ()
                                return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(("0b10"):number()) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TD)
                            end)
                        end
                    }
                    
                    env.posedge()
                        tl_d.valid:posedge(); env.negedge()
                        tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xabab)
                    env.negedge()
                        expect.equal(tl_d.bits.data:get()[1], 0xefef)
                end,

                probeack = function()
                    verilua "appendTasks" {
                        check_wb_dir = function ()
                            env.expect_happen_until(100, function ()
                                return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(("0b10"):number()) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TC)
                            end)
                            mp.io_dirWrite_s3_bits_meta_state:dump()
                        end
                    }

                    env.posedge()
                        tl_d.valid:posedge(); env.negedge()
                        tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
                    env.negedge()
                        expect.equal(tl_d.bits.data:get()[1], 0xbeef)
                end
            }
        end

        env.posedge(300)
    end
}

local test_miss_need_evict = env.register_test_case "test_miss_need_evict" {
    function ()
        -- TODO: Get?

        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1)

        local clientsOH = ("0b00"):number()
        env.negedge()
            write_dir(0x00, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return mp.io_replResp_s3_valid:is(1)
                end)
            end,
            function ()
                env.expect_happen_until(100, function ()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict)
                end)
            end
        }

        local source = 4
        env.negedge()
            tl_a:acquire_block(to_address(0x00, 0x05), TLParam.NtoT, source)
        env.posedge()
            chi_txreq.valid:posedge(); env.negedge()
            chi_txreq.bits.opcode:expect(OpcodeREQ.ReadUnique)
        env.posedge()
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
        env.posedge()
            chi_txrsp.valid:posedge(); env.negedge()
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(10, function()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) and chi_txreq.bits.txnID:is(0)
                end)
                chi_txreq:dump()

                env.negedge()
                    chi_rxrsp:comp(0, 5)
            end
        }
        
        env.posedge()
            tl_d.valid:posedge(); env.negedge()
            tl_d:dump()
            tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
        env.negedge()
            tl_d:dump()
            expect.equal(tl_d.bits.data:get()[1], 0xbeef)

        local sink = tl_d.bits.sink:get()
        env.negedge()
            tl_e:grantack(sink)

        env.negedge(10)
        mshrs[0].io_status_valid:expect(0)

        tl_d.ready:set(0)

        env.posedge(200)
    end
}

local test_miss_need_evict_and_probe = env.register_test_case "test_miss_need_evict_and_probe" {
    function (case)
        -- TODO: Get?
        print("case is " .. case)

        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local clientsOH = ("0b01"):number()
        env.negedge()
            write_dir(0x00, utils.uint_to_onehot(0), 0x01, MixedState.TTC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(1), 0x02, MixedState.TTC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(2), 0x03, MixedState.TTC, clientsOH)
            write_dir(0x00, utils.uint_to_onehot(3), 0x04, MixedState.TTC, clientsOH)

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return mp.io_replResp_s3_valid:is(1)
                end)
            end
        }

        local source = 4
        env.negedge()
            tl_a:acquire_block(to_address(0x00, 0x05), TLParam.NtoT, source)
        env.posedge()
            chi_txreq.valid:posedge(); env.negedge()
            chi_txreq.bits.opcode:expect(OpcodeREQ.ReadUnique)
        env.posedge()
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
        env.posedge()
            chi_txrsp.valid:posedge(); env.negedge()
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

        env.expect_happen_until(10, function ()
            return tl_b:fire()
        end)
        tl_b:dump()
        local probe_address = tl_b.bits.address:get()

        env.mux_case(case) {
            probeack_data = function()
                tl_c:probeack_data(probe_address, TLParam.TtoN, "0xabab", "0xefef", 0) -- core 0 sourceId = 0

                env.expect_happen_until(10, function ()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull)
                end)
                chi_txreq:dump()

                local txn_id = chi_txreq.bits.txnID:get()
                local dbid = 5

                env.negedge()
                    chi_rxrsp:comp_dbidresp(txn_id, dbid)

                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
                        end)
                            tl_d.bits.source:expect(source)
                        
                        env.negedge()
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                        end)
                            tl_d.bits.source:expect(source)
                
                        local sink = tl_d.bits.sink:get()
                        env.negedge()
                            tl_e:grantack(sink)
                    end,

                    check_wb_dir = function ()
                        env.expect_happen_until(20, function ()
                            return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC)
                        end)
                    end
                }
                
                env.expect_happen_until(10, function ()
                    return chi_txdat:fire()
                end)
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0xabab)
                    chi_txdat.bits.dataID:expect(0x00)
                    chi_txdat.bits.txnID:expect(dbid)
                env.negedge()
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0xefef)
                    chi_txdat.bits.dataID:expect(0x02)
                    chi_txdat.bits.txnID:expect(dbid)
            end,

            probeack = function ()
                tl_c:probeack(probe_address, TLParam.TtoN, 0) -- core 0 sourceId = 0

                verilua "appendTasks" {
                    function ()
                        env.posedge()
                            tl_d.valid:posedge(); env.negedge()
                            tl_d:dump()
                            tl_d.bits.source:expect(source); expect.equal(tl_d.bits.data:get()[1], 0xdead)
                        env.negedge()
                            tl_d:dump()
                            expect.equal(tl_d.bits.data:get()[1], 0xbeef)
                
                        local sink = tl_d.bits.sink:get()
                        env.negedge()
                            tl_e:grantack(sink)
                    end,

                    check_wb_dir = function ()
                        env.expect_happen_until(20, function ()
                            return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC)
                        end)
                    end
                }

                env.expect_happen_until(100, function()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) and chi_txreq.bits.txnID:is(0)
                end)
                    chi_txreq:dump()
                env.negedge()
                    chi_rxrsp:comp(0, 5)
            end
        }
        
        env.negedge(20)
        mshrs[0].io_status_valid:expect(0)

        env.posedge(200)
    end
}

local test_miss_need_writebackfull = env.register_test_case "test_miss_need_writebackfull" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local clientsOH = ("0b00"):number()
        env.negedge()
            write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TD, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TD, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TD, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TD, clientsOH)

        local source = 4
        env.negedge()
            tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoB, source)
        env.posedge()
            chi_txreq.valid:posedge(); env.negedge()
            chi_txreq.bits.opcode:expect(OpcodeREQ.ReadNotSharedDirty)
        env.posedge()
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
        env.posedge()
            chi_txrsp.valid:posedge(); env.negedge()
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

        verilua "appendTasks" {
            function ()
                env.expect_happen_until(100, function ()
                    return mp.io_replResp_s3_valid:is(1)
                end)
            end,
            function ()
                env.expect_happen_until(100, function ()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull)
                end)
                chi_txreq:dump()

                local txn_id = chi_txreq.bits.txnID:get()
                local dbid = 5

                env.negedge()
                    chi_rxrsp:comp_dbidresp(txn_id, dbid)

                env.expect_happen_until(10, function ()
                    return chi_txdat:fire()
                end)
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0)
                    chi_txdat.bits.dataID:expect(0x00)
                    chi_txdat.bits.txnID:expect(dbid)
                env.negedge()
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0)
                    chi_txdat.bits.dataID:expect(0x02)
                    chi_txdat.bits.txnID:expect(dbid)
            end
        }
        
        env.expect_happen_until(30, function ()
            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xdead
        end)
            tl_d.bits.source:expect(source)

        env.negedge()
        env.expect_happen_until(30, function ()
            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
        end)
            tl_d.bits.source:expect(source)

        local sink = tl_d.bits.sink:get()
        env.negedge()
            tl_e:grantack(sink)
        
        env.negedge(10)
        mshrs[0].io_status_valid:expect(0)

        env.posedge(200)
    end
}

local test_miss_need_writebackfull_and_probe = env.register_test_case "test_miss_need_writebackfull_and_probe" {
    function (case)
        print("case is " .. case)

        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local clientsOH = ("0b01"):number()
        env.negedge()
            write_dir(0x02, utils.uint_to_onehot(0), 0x01, MixedState.TTD, clientsOH)
            write_dir(0x02, utils.uint_to_onehot(1), 0x02, MixedState.TTD, clientsOH)
            write_dir(0x02, utils.uint_to_onehot(2), 0x03, MixedState.TTD, clientsOH)
            write_dir(0x02, utils.uint_to_onehot(3), 0x04, MixedState.TTD, clientsOH)

        local source = 4
        env.negedge()
            tl_a:acquire_block(to_address(0x02, 0x05), TLParam.NtoB, source)
        env.posedge()
            chi_txreq.valid:posedge(); env.negedge()
            chi_txreq.bits.opcode:expect(OpcodeREQ.ReadNotSharedDirty)
        env.posedge()
            chi_rxdat:compdat(0, "0xabcd", "0xbeef", 5, CHIResp.UC) -- dbID = 5
        env.posedge()
            chi_txrsp.valid:posedge(); env.negedge()
            chi_txrsp.bits.txnID:expect(5) -- dbID = txnID = 5

        env.expect_happen_until(10, function ()
            return tl_b:fire()
        end)
        tl_b:dump()
        tl_b.bits.param:expect(TLParam.toN)
        local probe_address = tl_b.bits.address:get()

        env.mux_case(case) {
            probeack_data = function()
                tl_c:probeack_data(probe_address, TLParam.TtoN, "0xcdcd", "0xefef", 0) -- core 0 sourceId = 0

                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xabcd
                        end)
                            tl_d.bits.source:expect(source)
                        
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                        end)
                            tl_d.bits.source:expect(source)
                
                        local sink = tl_d.bits.sink:get()
                        env.negedge()
                            tl_e:grantack(sink)
                    end,

                    check_wb_dir = function ()
                        env.expect_happen_until(20, function ()
                            return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC)
                        end)
                    end
                }

                env.expect_happen_until(10, function ()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull)
                end)
                chi_txreq:dump()

                local txn_id = chi_txreq.bits.txnID:get()
                local dbid = 5

                env.negedge()
                    chi_rxrsp:comp_dbidresp(txn_id, dbid)

                env.expect_happen_until(10, function ()
                    return chi_txdat:fire()
                end)
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0xcdcd)
                    chi_txdat.bits.dataID:expect(0x00)
                    chi_txdat.bits.txnID:expect(dbid)
                env.negedge()
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0xefef)
                    chi_txdat.bits.dataID:expect(0x02)
                    chi_txdat.bits.txnID:expect(dbid)
            end,

            probeack = function()
                tl_c:probeack(probe_address, TLParam.TtoN, 0) -- core 0 sourceId = 0

                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xabcd
                        end)
                            tl_d.bits.source:expect(source)
                        
                        env.expect_happen_until(20, function ()
                            return tl_d:fire() and tl_d.bits.data:get()[1] == 0xbeef
                        end)
                            tl_d.bits.source:expect(source)
                
                        local sink = tl_d.bits.sink:get()
                        env.negedge()
                            tl_e:grantack(sink)
                    end,

                    check_wb_dir = function ()
                        env.expect_happen_until(20, function ()
                            return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC)
                        end)
                    end
                }

                env.expect_happen_until(10, function ()
                    return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull)
                end)
                chi_txreq:dump()

                local txn_id = chi_txreq.bits.txnID:get()
                local dbid = 5

                env.negedge()
                    chi_rxrsp:comp_dbidresp(txn_id, dbid)

                env.expect_happen_until(10, function ()
                    return chi_txdat:fire()
                end)
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0)
                    chi_txdat.bits.dataID:expect(0x00)
                    chi_txdat.bits.txnID:expect(dbid)
                env.negedge()
                    chi_txdat:dump()
                    expect.equal(chi_txdat.bits.data:get()[1], 0)
                    chi_txdat.bits.dataID:expect(0x02)
                    chi_txdat.bits.txnID:expect(dbid)
            end
        }

        env.negedge(10)
        mshrs[0].io_status_valid:expect(0)

        env.posedge(100)
    end
}

local test_snoop_shared = env.register_test_case "test_snoop_shared" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local clientsOH = ("0b00"):number()
        env.negedge()
            write_dir(0x04, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
            write_dir(0x04, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
            write_dir(0x04, utils.uint_to_onehot(2), 0x03, MixedState.BC, ("0b00"):number())
            write_dir(0x04, utils.uint_to_onehot(3), 0x04, MixedState.TD, ("0b00"):number())
        
        env.negedge()
            write_ds(0x04, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                {s = 0,   e = 63, v = 0xdead1},
                {s = 256, e = 256 + 63, v = 0xbeef1}
            }, 512))
            write_ds(0x04, utils.uint_to_onehot(1), utils.bitpat_to_hexstr({
                {s = 0,   e = 63, v = 0xdead2},
                {s = 256, e = 256 + 63, v = 0xbeef2}
            }, 512))
            write_ds(0x04, utils.uint_to_onehot(3), utils.bitpat_to_hexstr({
                {s = 0,   e = 63, v = 0xdead3},
                {s = 256, e = 256 + 63, v = 0xbeef3}
            }, 512))



        -- 
        -- SnpShared to TC => BC
        -- 
        do
            chi_rxsnp:snpshared(to_address(0x04, 0x01), 3, 0) -- ret2src == false
            verilua "appendTasks" {
                check_txrsp = function ()
                    env.expect_happen_until(10, function() return chi_txrsp:fire() end)
                    chi_txrsp:dump()
                    chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                    chi_txrsp.bits.resp:expect(CHIResp.SC)

                    env.negedge()
                    env.expect_not_happen_until(10, function() return chi_txrsp:fire() end)
                end,
                check_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC)
                    end)
                end
            }
        end

        env.negedge(10)

        do
            chi_rxsnp:snpshared(to_address(0x04, 0x02), 3, 1) -- ret2src == true(need resp data)
            verilua "appendTasks" {
                check_txrsp = function ()
                    env.expect_happen_until(10, function() return chi_txdat:fire() end)
                        chi_txdat:dump()
                        chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                        chi_txdat.bits.resp:expect(CHIResp.SC)
                        expect.equal(chi_txdat.bits.data:get()[1], 0xdead2)
                    env.negedge()
                        chi_txdat:dump()
                        chi_txdat.valid:expect(1)
                        expect.equal(chi_txdat.bits.data:get()[1], 0xbeef2)

                    env.negedge(2)
                    env.expect_not_happen_until(10, function() return chi_txdat:fire() end)
                end,
                check_dir_resp = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirResp_s3_valid:is(1) and mp.io_dirResp_s3_bits_hit:is(1)
                    end)
                    mp.io_dirResp_s3_bits_wayOH:dump()
                end,
                check_dir_wb = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC)
                    end)
                end
            }
        end

        env.negedge(10)
        
        -- 
        -- SnpShared to BC => BC
        -- 
        do
            chi_rxsnp:snpshared(to_address(0x04, 0x01), 3, 0) -- ret2src == false
            verilua "appendTasks" {
                check_txrsp = function ()
                    env.expect_happen_until(10, function() return chi_txrsp:fire() end)
                    chi_txrsp:dump()
                    chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                    chi_txrsp.bits.resp:expect(CHIResp.SC)

                    env.negedge()
                    env.expect_not_happen_until(10, function() return chi_txrsp:fire() end)
                end,
                check_dir_resp = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirResp_s3_valid:is(1) and mp.io_dirResp_s3_bits_hit:is(1) and mp.io_dirResp_s3_bits_meta_state:is(MixedState.BC)
                    end)
                end,
                check_dir_wb = function ()
                    env.expect_not_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) -- It is unnecessary to update the dir. 
                    end)
                end
            }
            env.negedge(20)
        end

        do
            chi_rxsnp:snpshared(to_address(0x04, 0x02), 3, 1) -- ret2src == true(need resp data)
            verilua "appendTasks" {
                check_txrsp = function ()
                    env.expect_happen_until(10, function() return chi_txdat:fire() end)
                        chi_txdat:dump()
                        chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                        chi_txdat.bits.resp:expect(CHIResp.SC)
                        expect.equal(chi_txdat.bits.data:get()[1], 0xdead2)
                    env.negedge()
                        chi_txdat:dump()
                        chi_txdat.valid:expect(1)
                        expect.equal(chi_txdat.bits.data:get()[1], 0xbeef2)

                    env.negedge(2)
                    env.expect_not_happen_until(10, function() return chi_txdat:fire() end)
                end,
                check_dir_resp = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirResp_s3_valid:is(1) and mp.io_dirResp_s3_bits_hit:is(1)
                    end)
                    mp.io_dirResp_s3_bits_wayOH:dump()
                end,
                check_dir_wb = function ()
                    env.expect_not_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) -- It is unnecessary to update the dir.
                    end)
                end
            }
            env.negedge(20)
        end

        -- 
        -- SnpShared to I => I
        -- 
        do
            chi_rxsnp:snpshared(to_address(0x05, 0x01), 3, 0) -- ret2src == false
            env.expect_happen_until(10, function() return chi_txrsp:fire() end)
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.resp:expect(CHIResp.I)
            
            env.negedge()
            chi_rxsnp:snpshared(to_address(0x05, 0x01), 3, 1) -- ret2src == true(need resp data)
            env.expect_happen_until(10, function() return chi_txrsp:fire() end)
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        
        -- 
        -- SnpShared to BBC => BBC
        -- 
        do
            chi_rxsnp:snpshared(to_address(0x04, 0x03), 3, 0) -- ret2src == false
            env.expect_happen_until(10, function() return chi_txrsp:fire() end)
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.resp:expect(CHIResp.SC)

            chi_rxsnp:snpshared(to_address(0x04, 0x03), 3, 1) -- ret2src == true(need resp data)
            env.expect_happen_until(10, function() return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC)
                expect.equal(chi_txdat.bits.data:get()[1], 0)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                chi_txdat.bits.dbID:expect(3)
                expect.equal(chi_txdat.bits.data:get()[1], 0)
        end

        
        -- 
        -- SnpShared to TD => BC (PassDirty)
        -- 
        do
            chi_rxsnp:snpshared(to_address(0x04, 0x04), 3, 0) -- ret2src == false
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC)
                    end)
                end
            }
            env.expect_happen_until(10, function() return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdead3)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0xbeef3)
        end

        do
            env.negedge(10)
                write_dir(0x04, utils.uint_to_onehot(3), 0x04, MixedState.TD, ("0b00"):number())

            chi_rxsnp:snpshared(to_address(0x04, 0x04), 3, 1) -- ret2src == true(need resp data)
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC)
                    end)
                end
            }
            env.expect_happen_until(10, function() return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdead3)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0xbeef3)
        end
        
        -- 
        -- SnpShared to TTC => BBC (need alloc mshr)
        -- 
        env.negedge(10)
            write_dir(0x06, utils.uint_to_onehot(0), 0x00, MixedState.TTC, ("0b01"):number())
            write_dir(0x06, utils.uint_to_onehot(1), 0x01, MixedState.TTC, ("0b01"):number())
            write_dir(0x06, utils.uint_to_onehot(2), 0x02, MixedState.TTC, ("0b01"):number())
            write_dir(0x06, utils.uint_to_onehot(3), 0x03, MixedState.TTC, ("0b01"):number())
        env.negedge()
            write_ds(0x06, utils.uint_to_onehot(1), utils.bitpat_to_hexstr({
                {s = 0,   e = 63, v = 0xdead4},
                {s = 256, e = 256 + 63, v = 0xbeef4}
            }, 512))

        do
            -- ret2src == false, probeack
            env.negedge()
            chi_rxsnp:snpshared(to_address(0x06, 0x00), 3, 0) -- ret2src == false
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.address:expect(to_address(0x06, 0x00))
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toB)
            env.negedge()
                tl_c:probeack(to_address(0x06, 0x00), TLParam.TtoB, 0) -- probeack
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01)
                    end)
                end
            }
            env.expect_happen_until(20, function ()
                return chi_txrsp:fire()
            end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.resp:expect(CHIResp.SC)
                chi_txrsp.bits.txnID:expect(3)
        end
        
        do
            -- ret2src == false, probeack_data
            env.negedge(10)
            chi_rxsnp:snpshared(to_address(0x06, 0x02), 3, 0) -- ret2src == false
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.address:expect(to_address(0x06, 0x02))
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toB)
            env.negedge()
                tl_c:probeack_data(to_address(0x06, 0x02), TLParam.TtoB, "0xcdcd", "0xefef", 0) -- probeack_data, core 0 sourceId = 0
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01)
                    end)
                end
            }
            env.expect_happen_until(10, function() return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xcdcd)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0xefef)
        end
    
        do
            -- ret2src == true, probeack
            env.negedge(20)
            chi_rxsnp:snpshared(to_address(0x06, 0x01), 3, 1) -- ret2src == true(need resp data)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.address:expect(to_address(0x06, 0x01))
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toB)
            env.negedge()
                tl_c:probeack(to_address(0x06, 0x01), TLParam.TtoB, 0) -- probeack
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01)
                    end)
                end
            }
            env.expect_happen_until(20, function ()
                return chi_txdat:fire()
            end)
            chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdead4)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0xbeef4)
        end

        do
            -- ret2src == true, probeack_data
            env.negedge(20)
            chi_rxsnp:snpshared(to_address(0x06, 0x03), 3, 1) -- ret2src == true(need resp data)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.address:expect(to_address(0x06, 0x03))
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toB)
            env.negedge(2)
                tl_c:probeack_data(to_address(0x06, 0x03), TLParam.TtoB, "0xcdcd1", "0xefef1", 0) -- probeack_data, core 0 sourceId = 0
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01)
                    end)
                end
            }
            env.expect_happen_until(20, function ()
                return chi_txdat:fire()
            end)
            chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xcdcd1)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0xefef1)
        end
            
        -- 
        -- SnpShared to TTD => BBC (need alloc mshr)
        -- 
        env.negedge(10)
            write_dir(0x07, utils.uint_to_onehot(0), 0x00, MixedState.TTD, ("0b01"):number())

        do
            -- ret2src == false
            local address = to_address(0x07, 0x00)

            env.negedge(10)
            chi_rxsnp:snpshared(address, 3, 0) -- ret2src == false
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.address:expect(address)
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toB)
            env.negedge(2)
                tl_c:probeack_data(address, TLParam.TtoB, "0x1cdcd", "0x1efef", 0) -- probeack_data, core 0 sourceId = 0
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01)
                    end)
                end
            }
            env.expect_happen_until(10, function() return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0x1cdcd)
            env.negedge()
                chi_txdat:dump()
                chi_txdat.valid:expect(1)
                expect.equal(chi_txdat.bits.data:get()[1], 0x1efef)
        end

        -- 
        -- TODO: SnpShared to BD => ???
        -- 


        env.posedge(100)
    end
}

local test_snoop_unique = env.register_test_case "test_snoop_unique" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        -- 
        -- SnpUnique to TC => I
        -- 
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TC, ("0b00"):number())

            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TC, ("0b00"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0x1dead},
                    {s = 256, e = 256 + 63, v = 0x1beef}
                }, 512))
            
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.dbID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I)
                expect.equal(chi_txdat.bits.data:get()[1], 0x1dead)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0x1beef)
        end

        -- 
        -- SnpUnique to TD => I
        -- 
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TD, ("0b00"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xdead},
                    {s = 256, e = 256 + 63, v = 0xbeef}
                }, 512))
                
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.dbID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdead)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbeef)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TD, ("0b00"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xdead},
                    {s = 256, e = 256 + 63, v = 0xbeef}
                }, 512))
                
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.dbID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdead)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbeef)
        end

        -- 
        -- SnpUnique to I => I
        -- 
        do      
            local address = to_address(0x09, 0x01)
            local txn_id = 3
            local ret2src = 0
            env.negedge(10)
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_not_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        do      
            local address = to_address(0x09, 0x01)
            local txn_id = 3
            local ret2src = 1
            env.negedge(10)
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_not_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        -- 
        -- SnpUnique to BC => I
        -- 
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.BC, ("0b00"):number())

            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.BC, ("0b00"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xdea1d},
                    {s = 256, e = 256 + 63, v = 0xbee1f}
                }, 512))
            
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(10, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.dbID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdea1d)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbee1f)
        end
        
        -- 
        -- TODO: SnpUnique to BD => I
        -- 

        -- 
        -- SnpUnique to BBC => I (need alloc mshr)
        --
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.BC, ("0b10"):number())

            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.BtoN, 16) -- probeack_data, core 1 sourceId = 16
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.BC, ("0b11"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xdea2d},
                    {s = 256, e = 256 + 63, v = 0xbee2f}
                }, 512))
            
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.BtoN, 16) -- probeack, core 1 sourceId = 16
            env.negedge()
                tl_c:probeack(address, TLParam.BtoN, 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I)
                expect.equal(chi_txdat.bits.data:get()[1], 0xdea2d)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbee2f)
        end

        -- 
        -- SnpUnique to TTC => I (need alloc mshr)
        -- 
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TTC, ("0b01"):number())

            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.TtoN, 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txrsp:fire() end)
                chi_txrsp:dump()
                chi_txrsp.bits.opcode:expect(OpcodeRSP.SnpResp)
                chi_txrsp.bits.txnID:expect(txn_id)
                chi_txrsp.bits.resp:expect(CHIResp.I)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TTC, ("0b01"):number())

            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack_data(address, TLParam.TtoN, "0xabcd", "0xffef", 0) -- probeack_data, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xabcd)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xffef)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x00, MixedState.TTC, ("0b01"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0x1abcd},
                    {s = 256, e = 256 + 63, v = 0x1ffef}
                }, 512))
            
            local address = to_address(0x09, 0x00)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.TtoN, 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I)
                expect.equal(chi_txdat.bits.data:get()[1], 0x1abcd)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0x1ffef)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(1), 0x01, MixedState.TTC, ("0b01"):number())

            local address = to_address(0x09, 0x01)
            local txn_id = 3
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge(2)
                tl_c:probeack_data(address, TLParam.TtoN, "0xab1cd", "0xff1ef", 0) -- probeack_data, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xab1cd)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xff1ef)
        end

        -- 
        -- SnpUnique to TTD => I (need alloc mshr)
        -- 
        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x02, MixedState.TTD, ("0b01"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xab2cd},
                    {s = 256, e = 256 + 63, v = 0xff2ef}
                }, 512))
            
            local address = to_address(0x09, 0x02)
            local txn_id = 4
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.TtoN, 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xab2cd)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xff2ef)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x03, MixedState.TTD, ("0b01"):number())
            
            local address = to_address(0x09, 0x03)
            local txn_id = 4
            local ret2src = 0
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge(2)
                tl_c:probeack_data(address, TLParam.TtoN, "0xaaaa", "0xbbbb", 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xaaaa)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbbbb)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x02, MixedState.TTD, ("0b01"):number())
            env.negedge()
                write_ds(0x09, utils.uint_to_onehot(0), utils.bitpat_to_hexstr({
                    {s = 0,   e = 63, v = 0xab3cd},
                    {s = 256, e = 256 + 63, v = 0xff3ef}
                }, 512))
            
            local address = to_address(0x09, 0x02)
            local txn_id = 4
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge()
                tl_c:probeack(address, TLParam.TtoN, 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xab3cd)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xff3ef)
        end

        do
            env.negedge(10)
                write_dir(0x09, utils.uint_to_onehot(0), 0x03, MixedState.TTD, ("0b01"):number())
            
            local address = to_address(0x09, 0x03)
            local txn_id = 4
            local ret2src = 1
            env.negedge()
            chi_rxsnp:snpunique(address, txn_id, ret2src)
            env.expect_happen_until(10, function ()
                return tl_b:fire()
            end)
                tl_b:dump()
                tl_b.bits.opcode:expect(TLOpcodeB.Probe)
                tl_b.bits.param:expect(TLParam.toN)
                tl_b.bits.address:expect(address)
            env.negedge(2)
                tl_c:probeack_data(address, TLParam.TtoN, "0xaaaa1", "0xbbbb1", 0) -- probeack, core 0 sourceId = 0
            
            verilua "appendTasks" {
                check_wb_dir = function ()
                    env.expect_happen_until(20, function ()
                        return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x00)
                    end)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() end)
                chi_txdat:dump()
                chi_txdat.bits.opcode:expect(OpcodeDAT.SnpRespData)
                chi_txdat.bits.txnID:expect(txn_id)
                chi_txdat.bits.resp:expect(CHIResp.I_PD)
                expect.equal(chi_txdat.bits.data:get()[1], 0xaaaa1)
            env.negedge()
                expect.equal(chi_txdat.bits.data:get()[1], 0xbbbb1)
        end

        env.posedge(100)
    end
}

local test_stage2_mshr_retry = env.register_test_case "test_stage2_mshr_retry" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        do
            print "stage2 grant retry"
            
            tl_d.ready:set(0)
            sourceD.io_task_s2_ready:set_force(0); sourceD.io_data_s2_ready:set_force(0); sourceD._skidBuffer_io_enq_ready:set_force(0); sourceD.skidBuffer.io_enq_ready_0:set_force(0)
            chi_txreq.ready:set(1); chi_txrsp.ready:set(1)

            tl_a:acquire_block(to_address(0x10, 0x20), TLParam.NtoT, 3)
            env.expect_happen_until(10, function () return chi_txreq.valid:is(1) and chi_txreq.ready:is(1) end)
            chi_rxdat:compdat(0, "0xdead", "0xbeef", CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp.valid:is(1) and chi_txrsp.ready:is(1) end)

            for i = 1, 20 do
                env.expect_happen_until(10, function () return mp.io_retryTasks_stage2_valid:is(1) and mp.io_retryTasks_stage2_bits_grant_s2:is(1) and mp.io_retryTasks_stage2_bits_isRetry_s2:is(1) end)
                print(env.cycles() .. " do grant_s2 retry " .. i)
                env.posedge()
            end

            tl_d.ready:set(1)
            sourceD.io_task_s2_ready:set_release(); sourceD.io_data_s2_ready:set_release(); sourceD._skidBuffer_io_enq_ready:set_release(); sourceD.skidBuffer.io_enq_ready_0:set_release()
            env.posedge()
            env.expect_not_happen_until(10, function () return mp.io_retryTasks_stage2_valid:is(1) and mp.io_retryTasks_stage2_bits_grant_s2:is(1) and mp.io_retryTasks_stage2_bits_isRetry_s2:is(1) end)
            
            env.posedge(100)
            tl_e:grantack(0)
            env.posedge(100)
        end

        do
            print "stage2 accessack retry"
            
            tl_d.ready:set(0)
            sourceD.io_task_s2_ready:set_force(0); sourceD.io_data_s2_ready:set_force(0) 
            chi_txreq.ready:set(1); chi_txrsp.ready:set(1)

            tl_a:get(to_address(0x11, 0x20), 3)
            env.expect_happen_until(10, function () return chi_txreq.valid:is(1) and chi_txreq.ready:is(1) end)
            chi_rxdat:compdat(0, "0xdead", "0xbeef", CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp.valid:is(1) and chi_txrsp.ready:is(1) end)

            for i = 1, 20 do
                env.expect_happen_until(10, function () return mp.io_retryTasks_stage2_valid:is(1) and mp.io_retryTasks_stage2_bits_accessack_s2:is(1) and mp.io_retryTasks_stage2_bits_isRetry_s2:is(1) end)
                print(env.cycles() .. " do accessack_s2 retry " .. i)
                env.posedge()
            end

            tl_d.ready:set(1)
            sourceD.io_task_s2_ready:set_release(); sourceD.io_data_s2_ready:set_release()
            env.posedge()
            env.expect_not_happen_until(10, function () return mp.io_retryTasks_stage2_valid:is(1) and mp.io_retryTasks_stage2_bits_grant_s2:is(1) and mp.io_retryTasks_stage2_bits_isRetry_s2:is(1) end)
            
            env.posedge(100)
        end

        env.posedge(100)
    end
}

local test_stage4_mshr_retry = env.register_test_case "test_stage4_mshr_retry" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        do
            tl_d.ready:set(0)
            txrsp.io_mpTask_s4_ready:set_force(0)
            tl_b.ready:set(1); chi_txreq.ready:set(1); chi_txrsp.ready:set(1)
            
            env.negedge()
            write_dir(0x01, ("0b0001"):number(), 0x04, MixedState.TTC, ("0b01"):number())
            env.negedge()
            chi_rxsnp:snpunique(to_address(0x01, 0x04), 0, 0)

            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) end)
            tl_c:probeack(to_address(0x01, 0x04), TLParam.TtoN, 0)

            for i = 1, 20 do
                env.expect_happen_until(10, function () return mp.io_retryTasks_stage4_valid:is(1) and mp.io_retryTasks_stage4_bits_snpresp_s4:is(1) and mp.io_retryTasks_stage4_bits_isRetry_s4:is(1) end)
                print(env.cycles() .. " do snpresp_s4 retry " .. i)
                env.posedge()
            end

            txrsp.io_mpTask_s4_ready:set_release()
            env.posedge()
            env.expect_not_happen_until(10, function () return mp.io_retryTasks_stage4_valid:is(1) and mp.io_retryTasks_stage4_bits_snpresp_s4:is(1) and mp.io_retryTasks_stage4_bits_isRetry_s4:is(1) end)
        end

        env.posedge(100)
    end
}

local test_replresp_retry = env.register_test_case "test_replresp_retry" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        local function set_mshr_wayOH()
            for i = 1, 4 do
                mshrs[i + 5].io_status_valid:set_force(1)
                mshrs[i + 5].io_status_wayOH:set_force(utils.uint_to_onehot(i - 1))
                mshrs[i + 5].io_status_set:set_force(0x01)
            end
        end

        local function reset_mshr_wayOH()
            for i = 1, 4 do
                mshrs[i + 5].io_status_valid:set_release()
                mshrs[i + 5].io_status_wayOH:set_release()
                mshrs[i + 5].io_status_set:set_release()
            end
        end

        set_mshr_wayOH()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1)

        local clientsOH = ("0b00"):number()
        env.negedge()
            write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

        env.negedge()
        tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoT, 0)
        env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.addr:is(to_address(0x01, 0x05)) end)
        env.negedge()
        chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.txnID:is(5) end)

        for i = 1, 20 do
            env.expect_happen_until(10, function () return mp.io_replResp_s3_valid:is(1) and mp.io_replResp_s3_bits_retry:is(1) end)
            print(env.cycles() .. " do replRetry " .. i)
            env.posedge()
        end
        reset_mshr_wayOH()

        env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
        env.negedge()
        chi_rxrsp:comp(0, 5)

        env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead end)
        env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef end)

        env.negedge()
        tl_e:grantack(0)

        env.negedge(10)
        mshrs[0].io_status_valid:expect(0)

        env.posedge(100)
    end
}

local test_txrsp_mp_replay = env.register_test_case "test_txrsp_mp_replay" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        chi_txrsp.ready:set(0)
        mp.io_txrsp_s4_ready:set_force(0)
        mp.io_txrspCnt:set_force(4)

        env.negedge()
        write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, 0x00)
        
        env.negedge()
            chi_rxsnp:snpunique(to_address(0x01, 0x01), 3, 0)

        for i = 1, 10 do
            env.expect_happen_until(10, function ()
                return mp.io_replay_s4_valid:is(1)
            end)
            env.expect_not_happen_until(10, function ()
                return mp.io_dirWrite_s3_valid:is(1)
            end)
            env.negedge()
            print(env.cycles() .. " do replay_s4 " .. i)
        end

        mp.io_txrsp_s4_ready:set_release()
        mp.io_txrspCnt:set_release()

        env.expect_happen_until(10, function ()
            return chi_txrsp.valid:is(1) and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp)
        end)
        chi_txrsp:dump()
        chi_txrsp.bits.resp:expect(CHIResp.I)
       
        env.posedge(100)
    end
}

local test_sinkA_replay = env.register_test_case "test_sinkA_replay" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_d.ready:set(1)

        mp.hasValidDataBuf_s6s7:set_force(0)

        env.negedge()
            write_dir(0x11, utils.uint_to_onehot(0), 0x01, MixedState.TC, 0x00)
        
        env.negedge()
            write_ds(0x11, ("0b0001"):number(), utils.bitpat_to_hexstr({
                {s = 0,   e = 63, v = 0xdead},
                {s = 256, e = 256 + 63, v = 0xbeef}
            }, 512))
        
        env.negedge()
            tl_a:acquire_block(to_address(0x11, 0x01), TLParam.NtoT, 4)

        
        for i = 1, 10 do
            env.expect_happen_until(10, function ()
                return mp.io_replay_s4_valid:is(1)
            end)
            env.expect_not_happen_until(10, function ()
                return mp.io_dirWrite_s3_valid:is(1)
            end)
            env.negedge()
            print(env.cycles() .. " do replay_s4 " .. i)
        end

        mp.hasValidDataBuf_s6s7:set_release()

        env.expect_happen_until(10, function ()
            return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead
        end)
        tl_d:dump()

        env.expect_happen_until(10, function ()
            return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef
        end)
        tl_d:dump()

        env.posedge(100)
    end
}


local test_snoop_nested_writebackfull = env.register_test_case "test_snoop_nested_writebackfull" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local function send_and_resp_request()
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoB, source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNotSharedDirty) end)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.txnID:is(5) end)
        end

        do
            -- SnpShared nested at TD state
            local clientsOH = ("0b00"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TD, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
            local snp_address = chi_txreq.bits.addr:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x00) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x02) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TD)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(1)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(1)
                env.negedge()
                    mp.valid_s5:expect(1)
                env.negedge()
                    mp.valid_s6:expect(1)
                    mp.io_txdat_s6s7_valid:is(1)
                    txdat.io_data_s6s7_valid:is(1)
                env.negedge()
                    mp.valid_s7:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp_dbidresp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }
            local dataID, opcode, resp = chi_txdat.bits.dataID, chi_txdat.bits.opcode, chi_txdat.bits.resp
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x00) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.SC) end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x02) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.SC) end)

            tl_e:grantack(0)  
        end

        env.negedge(20)
        
        do
            -- SnpShared nested at TTD state, snoop wait rprobe finish
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTD, clientsOH)

            send_and_resp_request()
            
            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming, and the snoop should also be stalled until the rprobe finish(receive the ProbeAck)
            env.negedge()
                chi_rxsnp.ready:expect(0)
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(snp_address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpShared)
            env.expect_not_happen_until(20, function ()
                return reqArb.io_mpReq_s2_valid:is(1)
            end)

            tl_c:probeack(snp_address, TLParam.TtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
            
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x00) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x02) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.SC_PD)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TTD)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(1)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(1)
                env.negedge()
                    mp.valid_s5:expect(1)
                env.negedge()
                    mp.valid_s6:expect(1)
                    mp.io_txdat_s6s7_valid:is(1)
                    txdat.io_data_s6s7_valid:is(1)
                env.negedge()
                    mp.valid_s7:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp_dbidresp(0, 3)    
                
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }
            local dataID, opcode, resp = chi_txdat.bits.dataID, chi_txdat.bits.opcode, chi_txdat.bits.resp
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x00) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.SC) end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x02) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.SC) end)

            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpUnique nested at TD state
            local clientsOH = ("0b00"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TD, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
            local snp_address = chi_txreq.bits.addr:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming
            chi_rxsnp:snpunique(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x00) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.I_PD)
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x02) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.I_PD)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TD)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(1)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toN:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(1)
                env.negedge()
                    mp.valid_s5:expect(1)
                env.negedge()
                    mp.valid_s6:expect(1)
                    mp.io_txdat_s6s7_valid:is(1)
                    txdat.io_data_s6s7_valid:is(1)
                env.negedge()
                    mp.valid_s7:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp_dbidresp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }
            local dataID, opcode, resp = chi_txdat.bits.dataID, chi_txdat.bits.opcode, chi_txdat.bits.resp
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x00) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.I) end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x02) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.I) end)

            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpUnique nested at TTD state, snoop wait rprobe finish
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTD, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTD, clientsOH)

            send_and_resp_request()
            
            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming, and the snoop should also be stalled until the rprobe finish(receive the ProbeAck)
            env.negedge()
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(snp_address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpUnique)
                env.posedge()
                chi_rxsnp.ready:expect(0)
            env.expect_not_happen_until(20, function ()
                return reqArb.io_mpReq_s2_valid:is(1)
            end)

            tl_c:probeack(snp_address, TLParam.TtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
            
            chi_rxsnp:snpunique(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x00) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.I_PD)
                        env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.dataID:is(0x02) and chi_txdat.bits.opcode:is(OpcodeDAT.SnpRespData) end)
                        chi_txdat.bits.resp:expect(CHIResp.I_PD)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TTD)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(1)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toN:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(1)
                env.negedge()
                    mp.valid_s5:expect(1)
                env.negedge()
                    mp.valid_s6:expect(1)
                    mp.io_txdat_s6s7_valid:is(1)
                    txdat.io_data_s6s7_valid:is(1)
                env.negedge()
                    mp.valid_s7:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp_dbidresp(0, 3)    
                
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }
            local dataID, opcode, resp = chi_txdat.bits.dataID, chi_txdat.bits.opcode, chi_txdat.bits.resp
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x00) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.I) end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and dataID:is(0x02) and opcode:is(OpcodeDAT.CopyBackWrData) and resp:is(CHIResp.I) end)

            tl_e:grantack(0)  
        end

        env.posedge(100)
    end
}

local test_snoop_nested_evict = env.register_test_case "test_snoop_nested_evict" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local function send_and_resp_request()
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoB, source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNotSharedDirty) end)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.txnID:is(5) end)
        end

        do
            -- SnpShared nested at BC state
            local clientsOH = ("0b00"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.BC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            local snp_address = chi_txreq.bits.addr:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for Comp, a snoop is comming
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.SC)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.BC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpShared nested at BBC state
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.BC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.BC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming, and the snoop should also be stalled until the rprobe finish(receive the ProbeAck)
            env.negedge()
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(snp_address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpShared)
            env.posedge()
                chi_rxsnp.ready:expect(0)
            env.expect_not_happen_until(20, function ()
                return reqArb.io_mpReq_s2_valid:is(1)
            end)

            tl_c:probeack(snp_address, TLParam.BtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.SC)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.BC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpShared nested at TC state
            local clientsOH = ("0b00"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            local snp_address = chi_txreq.bits.addr:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.SC)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0) 
        end

        env.negedge(20)

        do
            -- SnpShared nested at TTC state
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming, and the snoop should also be stalled until the rprobe finish(receive the ProbeAck)
            env.negedge()
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(snp_address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpShared)
            env.posedge()
                chi_rxsnp.ready:expect(0)
            env.expect_not_happen_until(20, function ()
                return reqArb.io_mpReq_s2_valid:is(1)
            end)

            tl_c:probeack(snp_address, TLParam.TtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            
            chi_rxsnp:snpshared(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.SC)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TTC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpUnique nested at TC state
            local clientsOH = ("0b00"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            local snp_address = chi_txreq.bits.addr:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming
            chi_rxsnp:snpunique(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.I)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toB:expect(0)
                    mp.io_mshrNested_snoop_toN:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpUnique nested at TTC state
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            -- When MSHR is waiting for CompDBIDResp, a snoop is comming, and the snoop should also be stalled until the rprobe finish(receive the ProbeAck)
            env.negedge()
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(snp_address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpUnique)
            env.posedge()
                chi_rxsnp.ready:expect(0)
            env.expect_not_happen_until(20, function ()
                return reqArb.io_mpReq_s2_valid:is(1)
            end)

            tl_c:probeack(snp_address, TLParam.TtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            
            chi_rxsnp:snpunique(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.I)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TTC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toN:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end

        env.negedge(20)

        do
            -- SnpUnique nested at TTC state(after ProbeAck)
            local clientsOH = ("0b01"):number()
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTC, clientsOH)

            send_and_resp_request()

            env.expect_happen_until(10, function () return tl_b:fire() end)
            local snp_address = tl_b.bits.address:get()
            print(("snp_address is 0x%x"):format(snp_address))

            tl_c:probeack(snp_address, TLParam.TtoN, 0)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
            
            chi_rxsnp:snpunique(snp_address, 3, 0)

            do
                verilua "appendTasks" {
                    function ()
                        env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
                        chi_txrsp.bits.resp:expect(CHIResp.I)
                    end,
                    function ()
                        env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
                    end
                }

                env.expect_happen_until(10, function () return mpReq_s2:fire() and mpReq_s2.bits.channel:is(0x02) and mpReq_s2.bits.snpHitWriteBack:is(1) end)
                env.negedge()
                    mp.valid_s3:expect(1)
                    mp.io_dirResp_s3_bits_hit:expect(1)
                    mp.io_dirResp_s3_bits_meta_state:expect(MixedState.TTC)
                    mp.io_dirWrite_s3_valid:expect(0)
                    mp.io_toDS_dsRead_s3_valid:expect(0)
                    mp.io_mshrAlloc_s3_valid:expect(0)
                    mp.io_mshrNested_snoop_toN:expect(1)
                env.negedge()
                    mp.valid_snpresp_s4:is(1)
                    mp.valid_s4:expect(0)
                env.negedge()
                    mp.valid_s5:expect(0)
            end

            env.negedge()
                chi_rxrsp:comp(0, 3)
            
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return reqArb.io_tempDsRead_s1_valid:is(1) end)
                    env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }

            env.negedge(5)
            tl_e:grantack(0)  
        end
        
        -- TODO: SnpUnique BC/BBC

        env.posedge(100)
    end
}

-- TODO: nest: Release nest Probe, Snoop nest WriteBack/Evict
local test_release_nested_probe = env.register_test_case "test_release_nested_probe" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        do
            -- 
            -- TD      I
            --   \    /
            --     TTC
            -- 
            -- single client
            local clientsOH = ("0b01"):number()
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TTC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TTC, clientsOH)

            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoT, source)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)
            
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) end)
            mshrs[0].state_s_evict:expect(0)
            mshrs[0].state_s_wb:expect(1)
            local probe_address = tl_b.bits.address:get()
            
            tl_c:release_data(probe_address, TLParam.TtoN, 8, "0xdead1", "0xbeef1")
            env.expect_happen_until(10, function () return mp.io_mshrNested_release_setDirty:is(1) and mp.io_mshrNested_release_TtoN:is(1) end)
            env.negedge()
                mshrs[0].state_s_evict:expect(1)
                mshrs[0].state_s_wb:expect(0)
            tl_c:probeack(probe_address, TLParam.NtoN, 0)
            
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
            chi_rxrsp:comp_dbidresp(0, 5)

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead end)
                    env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef end)
                    env.negedge()
                    tl_e:grantack(0)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.CopyBackWrData) and chi_txdat.bits.data:get()[1] == 0xdead1 end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.CopyBackWrData) and chi_txdat.bits.data:get()[1] == 0xbeef1 end)
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        do
            -- 
            -- BC      BC (send release)
            --   \    /
            --     TC
            -- 
            -- single client
            local clientsOH = ("0b11"):number()
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x06), TLParam.NtoT, source)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead2", "0xbeef2", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)
            
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(0) end)
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(16) end)

            mshrs[0].state_s_evict:expect(0)
            mshrs[0].state_s_wb:expect(1)
            local probe_address = tl_b.bits.address:get()
            
            tl_c:release(probe_address, TLParam.BtoN, 8)
            env.expect_happen_until(10, function () return mp.io_mshrNested_release_setDirty:is(0) and mp.io_mshrNested_release_BtoN:is(1) end)
            env.negedge()
                mshrs[0].state_s_evict:expect(0)
                mshrs[0].state_s_wb:expect(1)
                mshrs[0].state_w_rprobeack:expect(0)

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
                    chi_rxrsp:comp(0, 5)
                end
            }
            tl_c:probeack(probe_address, TLParam.BtoN, 16)
            tl_c:probeack(probe_address, TLParam.NtoN, 0)
                mshrs[0].state_w_rprobeack:expect(1)

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead2 end)
                    env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef2 end)
                    env.negedge()
                    tl_e:grantack(0)
                end
            }
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        do
            -- 
            -- BC      BC (send release data)
            --   \    /
            --     TC
            -- 
            -- single client
            local clientsOH = ("0b11"):number()
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
                write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)

            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x06), TLParam.NtoT, source)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead2", "0xbeef2", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)
            
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(0) end)
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(16) end)

            mshrs[0].state_s_evict:expect(0)
            mshrs[0].state_s_wb:expect(1)
            local probe_address = tl_b.bits.address:get()
            
            tl_c:release_data(probe_address, TLParam.BtoN, 8, "0xdead2", "0xbeef2")
            env.expect_happen_until(10, function () return mp.io_mshrNested_release_setDirty:is(1) and mp.io_mshrNested_release_BtoN:is(1) end)
            env.negedge()
                mshrs[0].state_s_evict:expect(1)
                mshrs[0].state_s_wb:expect(0)
                mshrs[0].state_w_rprobeack:expect(0)

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteBackFull) end)
                    chi_rxrsp:comp_dbidresp(0, 5)
                end
            }
            tl_c:probeack(probe_address, TLParam.BtoN, 16)
            tl_c:probeack(probe_address, TLParam.NtoN, 0)
                mshrs[0].state_w_rprobeack:expect(1)

            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(15, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead2 end)
                    env.expect_happen_until(15, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef2 end)
                    env.negedge()
                    tl_e:grantack(0)
                end
            }
            env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.CopyBackWrData) and chi_txdat.bits.data:get()[1] == 0xdead2 end)
            env.expect_happen_until(10, function () return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.CopyBackWrData) and chi_txdat.bits.data:get()[1] == 0xbeef2 end)
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        env.posedge(100)
    end
}

local test_multi_probe = env.register_test_case "test_multi_probe" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local clientsOH = ("0b11"):number()
        env.negedge(20)
            write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(1), 0x02, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(2), 0x03, MixedState.TC, clientsOH)
            write_dir(0x01, utils.uint_to_onehot(3), 0x04, MixedState.TC, clientsOH)
        
        do
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x06), TLParam.NtoT, source)
            env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead22", "0xbeef22", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)

            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(0) end)
                local probe_address = tl_b.bits.address:get()
                mshrs[0].state_s_rprobe:expect(0)
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) and tl_b.bits.source:is(16) end)
                mshrs[0].state_s_rprobe:expect(0)
            env.negedge()
                mshrs[0].state_s_rprobe:expect(1)
            env.expect_not_happen_until(10, function () return tl_b:fire() end)
                mshrs[0].state_w_rprobeack:expect(0)
                mshrs[0].probeAckClients:expect(0x0)
            tl_c:probeack(probe_address, TLParam.BtoN, 0) -- core 0
            env.negedge()
                mshrs[0].probeAckClients:expect(0x01)
            env.negedge()
                mshrs[0].probeAckClients:expect(0x01)
                mshrs[0].state_s_evict:expect(0)
            verilua "appendTasks" {
                function()
                    env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.Evict) end)
                    chi_rxrsp:comp(0, 5)
                end
            }
            tl_c:probeack(probe_address, TLParam.BtoN, 16) -- core 1
            env.negedge()
                mshrs[0].probeAckClients:expect(("0b11"):number())
                mshrs[0].state_s_evict:expect(1)

            env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xdead22 end)
            env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) and tl_d.bits.data:get()[1] == 0xbeef22 end)
            env.negedge()
            tl_e:grantack(0)
            env.negedge(10)
            mshrs[0].io_status_valid:expect(0)
        end

        env.posedge(100)
    end
}

local test_grant_block_probe = env.register_test_case "test_grant_block_probe" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        do
            local clientsOH = ("0b00"):number()
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, clientsOH)

            local source = 4
            local sink = nil
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x01), TLParam.NtoT, source)
            env.expect_happen_until(10, function () return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.GrantData) end)
            env.negedge()
                tl_d.valid:expect(1)
                sink = tl_d.bits.sink:get()
            
            chi_rxsnp:snpunique(to_address(0x01, 0x01), 4, 0)
            env.expect_not_happen_until(50, function () return tl_b:fire() end) -- Probe is blocked by the pending GrantAck
            
            env.negedge()
                tl_e:grantack(sink)
            env.expect_happen_until(10, function() return tl_b:fire() end)

            env.dut_reset()
        end

        env.posedge(100)
    end
}


local test_snoop_nested_read = env.register_test_case "test_snoop_nested_read" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        local function send_and_resp_request()
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x05), TLParam.NtoB, source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNotSharedDirty) end)
            env.posedge()
                chi_rxdat:compdat(0, "0xdead", "0xbeef", 5, CHIResp.UC) -- dbID = 5
            env.posedge()
                env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.txnID:is(5) end)
        end

        -- 
        --             |   Snoop     Read   |
        -- needs probe |    N         N     |
        --             |    Y         N     |
        --             |    Y         Y     |
        --             |    N         Y     |
        -- 

        -- 
        -- [1] Snoop needs MSHR
        -- 
        -- do
        --     -- 
        --     --   TC    I <-- AcquireBlock
        --     --    \   / 
        --     --     TTC
        --     -- 
        --     -- Snoop needs probe other core / Acquire needs probe other core
        --     env.negedge()
        --         write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TTC, ("0b10"):number())
        --     local source = 4
        --     env.negedge()
        --         tl_a:acquire_block(to_address(0x01, 0x01), TLParam.NtoB, source)
            

        -- end

        do
            -- 
            --  I    BC <- AcquireBlock.BtoT
            --   \   /
            --    BC 
            --       <- SnpCleanInvalid
            -- 
            env.negedge()
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.BC, ("0b01"):number())
            local source = 4
            local address = to_address(0x01, 0x01)
            env.negedge()
                tl_a:acquire_block(address, TLParam.BtoT, source)
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.MakeUnique) end)
            
            env.negedge()
                chi_rxsnp.valid:set(1)
                chi_rxsnp.bits.addr:set(bit.rshift(address, 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpCleanInvalid)
                env.posedge()
                chi_rxsnp.ready:expect(1) -- Snoop will not be blocked since the Acquire MSHR is scheduling MakeUnique and does not get Comp from next level cache.
            env.negedge()
                chi_rxsnp.valid:set(0)
            env.expect_happen_until(10, function() return mp.io_dirResp_s3_valid:is(1) and mp.io_dirResp_s3_bits_meta_state:is(MixedState.BC) and mp.io_dirResp_s3_bits_meta_clientsOH:is(1) end)
            mp.io_mshrNested_snoop_toN:expect(0) -- Snoop cannot nest Acquire MSHR because the Snoop needs to Probe the upper level cache. The mshr nested action by Snoop will be deferred until the Snoop MSHR scheduling mainpipe task to update the directory meta.
            
            env.expect_happen_until(10, function() return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) end)
            tl_c:probeack(address, TLParam.BtoN, 0) -- TODO: ProbeAckData, ret2src = 1, SnpShared, SnpUnique
            env.expect_happen_until(10, function() return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.I) end)
            mp.io_mshrNested_snoop_toN:expect(1)
            env.expect_happen_until(10, function() return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)

            chi_rxrsp:comp(0, 0)
            env.expect_happen_until(10, function() return tl_d:fire() end)
            env.negedge()
                tl_d.valid:expect(1)
            env.negedge()
                tl_e:grantack(0)
            env.negedge(10)
                mshrs[0].io_status_valid:expect(0)
                mshrs[1].io_status_valid:expect(0)
        end

        -- env.posedge(200)
        -- env.TEST_SUCCESS()

        -- 
        -- [2] Snoop does not require MSHR
        -- 
        do
            -- SnpShared + BC
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.BC, ("0b00"):number())
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x01), TLParam.NtoT, source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.MakeUnique) end)
                chi_txreq:dump()
            
            chi_rxsnp:snpshared(to_address(0x01, 0x01), 3, 0)
            
            env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toB:is(1) end)

            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
            chi_txrsp.bits.resp:expect(CHIResp.SC)

            env.negedge()
                chi_rxrsp:comp(0, 5) -- dbID = 5
            env.negedge(10)
                tl_e:grantack(0)
            env.negedge(20)
                mshrs[0].io_status_valid:expect(0)
        end

        do
            -- SnpUnique + BC
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.BC, ("0b00"):number())
            local source = 4
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x01), TLParam.NtoT, source)
            env.posedge()
                env.expect_happen_until(10, function () return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.MakeUnique) end)
                chi_txreq:dump()
            
            chi_rxsnp:snpunique(to_address(0x01, 0x01), 3, 0)

            env.expect_happen_until(10, function () return mshrs[0].io_nested_snoop_toN:is(1) end)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.SnpResp) end)
            chi_txrsp.bits.resp:expect(CHIResp.I)

            env.negedge()
                chi_rxrsp:comp(0, 5) -- dbID = 5
            env.negedge(10)
                tl_e:grantack(0)
            env.negedge(20)
                mshrs[0].io_status_valid:expect(0)
        end

        do
            -- 
            --   BC     BC <--- AcquirePerm.BtoT
            --     \    /
            --       TD <--- SnpShared
            -- 
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TD, ("0b11"):number())
            local source = 4
            env.negedge()
                tl_a:acquire_perm(to_address(0x01, 0x01), TLParam.BtoT, source)
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toN) end)
            env.negedge()
                chi_rxsnp.bits.txnID:set(3)
                chi_rxsnp.bits.addr:set(bit.rshift(to_address(0x01, 0x01), 3), true)
                chi_rxsnp.bits.opcode:set(OpcodeSNP.SnpShared)
                chi_rxsnp.bits.retToSrc:set(0)
                chi_rxsnp.valid:set(1)
            env.posedge()
                chi_rxsnp.ready:expect(0) -- Snoop will be blocked beacuse Acquire MSHR needs Probe
            env.negedge()
                chi_rxsnp.valid:set(0)

            tl_c:probeack(to_address(0x01, 0x01), TLParam.BtoN, 17)

            env.negedge(10)
            chi_rxsnp.ready:expect(0) -- Snoop will be blocked beacuse Acquire MSHR needs to wait grant ack
            tl_e:grantack(0)

            -- 
            --    I     TC
            --     \    /
            --       TTD
            -- 
            chi_rxsnp:snpshared(to_address(0x01, 0x01), 3, 0)
            env.expect_happen_until(10, function () return mp.io_dirResp_s3_valid:is(1) and mp.io_dirResp_s3_bits_meta_state:is(MixedState.TTD) end)
            env.expect_happen_until(10, function () return tl_b:fire() and tl_b.bits.param:is(TLParam.toB) end)
            tl_c:probeack(to_address(0x01, 0x01), TLParam.TtoN, 0)
            env.expect_happen_until(20, function () return chi_txdat:fire() and chi_txdat.bits.resp:is(CHIResp.SC_PD) end) 
            env.expect_happen_until(20, function () return chi_txdat:fire() and chi_txdat.bits.resp:is(CHIResp.SC_PD) end)
            env.negedge(20)
            mshrs[0].io_status_valid:expect(0)
        end


        env.posedge(100)
    end
}

local test_acquire_BtoT_miss = env.register_test_case "test_acquire_BtoT_miss" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        do
            -- AcquireBlock
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.I, ("0b00"):number())
            env.negedge()
                tl_a:acquire_block(to_address(0x01, 0x01), TLParam.BtoT, 0)
            
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead22", "0xbeef22", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)
            
            env.expect_happen_until(20, function () return tl_d:fire() and tl_d.bits.source:is(0) and tl_d.bits.data:get()[1] == 0xdead22 end)
            env.expect_happen_until(20, function () return tl_d:fire() and tl_d.bits.source:is(0) and tl_d.bits.data:get()[1] == 0xbeef22 end)
            local sink = tl_d.bits.sink:get()

            tl_e:grantack(sink)
        end

        do
            -- AcquirePerm
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.I, ("0b00"):number())
            env.negedge()
                tl_a:acquire_perm(to_address(0x01, 0x01), TLParam.BtoT, 0)
            
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadUnique) end)
            chi_rxdat:compdat(0, "0xdead22", "0xbeef22", 5, CHIResp.UC)
            env.expect_happen_until(10, function () return chi_txrsp:fire() and chi_txrsp.bits.opcode:is(OpcodeRSP.CompAck) end)
            
            env.expect_happen_until(20, function () return tl_d:fire() and tl_d.bits.source:is(0) and tl_d.bits.opcode:is(TLOpcodeD.Grant) end)
            local sink = tl_d.bits.sink:get()
            env.negedge()
            env.expect_not_happen_until(20, function () return tl_d:fire() end)

            tl_e:grantack(sink)
        end

        env.posedge(100)
    end
}

local test_grant_on_stage4 = env.register_test_case "test_grant_on_stage4" {
    function ()
        env.dut_reset()
        resetFinish:posedge()

        tl_b.ready:set(1); tl_d.ready:set(1); chi_txrsp.ready:set(1); chi_txreq.ready:set(1); chi_txdat.ready:set(1)

        do
            -- basic AcquirePerm
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, ("0b00"):number())
            env.negedge()
                tl_a:acquire_perm(to_address(0x01, 0x01), TLParam.BtoT, 0)
            env.expect_happen_until(10, function () return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01) end)
            env.expect_happen_until(10, function() return mp.io_sourceD_s4_valid:is(1) end)
            verilua "appendTasks" {
                function ()
                    env.expect_not_happen_until(10, function() return mp.io_sourceD_s6s7_valid:is(1) end)
                end,
                function ()
                    env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.Grant) end)
                    env.negedge()
                    env.expect_not_happen_until(10, function() return tl_d:fire() end)
                    local sink = tl_d.bits.sink()
                    env.negedge()
                    tl_e:grantack(sink)
                end
            }
        end

        do
            -- replay
            env.negedge(20)
                write_dir(0x01, utils.uint_to_onehot(0), 0x01, MixedState.TC, ("0b00"):number())
                mp.io_nonDataRespCnt:set_force(3)
                mp.io_sourceD_s4_valid:set_force(0)
            env.negedge()
                tl_a:acquire_perm(to_address(0x01, 0x01), TLParam.BtoT, 0)
            
            verilua "appendTasks" {
                function ()
                    env.expect_not_happen_until(10, function() return mp.io_dirWrite_s3_valid:is(1) end)
                end
            }
            env.expect_happen_until(10, function() return mp.io_replay_s4_valid:is(1) end)

            env.negedge(10)
                mp.io_nonDataRespCnt:set_release()
                mp.io_sourceD_s4_valid:set_release()
            verilua "appendTasks" {
                function ()
                    env.expect_happen_until(10, function() return mp.io_dirWrite_s3_valid:is(1) and mp.io_dirWrite_s3_bits_meta_state:is(MixedState.TTC) and mp.io_dirWrite_s3_bits_meta_clientsOH:is(0x01) end)
                end
            }
            env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.Grant) end)
            env.negedge()
            env.expect_not_happen_until(10, function() return tl_d:fire() end)
            local sink = tl_d.bits.sink()
            env.negedge()
            tl_e:grantack(sink)
        end

        env.posedge(100)
    end
}

-- TODO: SnpOnce / Hazard
-- TODO: replacement policy
-- TODO: Get not preferCache
-- TODO: CHI retry

-- TODO: Grant at MainPipe should block Probe with same address

 
jit.off()
verilua "mainTask" { function ()
    sim.dump_wave()

    -- local test_all = false
    local test_all = true

    -- test_snoop_nested_read()

    -- 
    -- normal test cases
    -- 
    if test_all then
    
    mp.dirWen_s3:set_force(0)
        test_replay_valid()
        test_load_to_use()
        test_load_to_use_latency()
        test_load_to_use_stall_simple()
        test_load_to_use_stall_complex()
        test_grantdata_continuous_stall_3()
        test_grantdata_mix_grant()
        test_release_write()
        test_release_continuous_write()
    mp.dirWen_s3:set_release()


    test_sinkA_hit()
    test_sinkA_miss()
    test_release_hit()
    test_acquire_and_release()
    test_acquire_perm_and_probeack_data()
    test_probe_toN()
    test_probe_toB()

    test_get_miss() -- TODO: for now we suppose preferCache is true! 
    test_get_hit("probeack_data") -- TODO: for now we suppose preferCache is true! 
    test_get_hit("probeack") -- TODO: for now we suppose preferCache is true! 

    test_miss_need_evict()
    test_miss_need_evict_and_probe("probeack")
    test_miss_need_evict_and_probe("probeack_data")
    -- test_miss_need_evict_and_probe("probeack_data_multi_clients") -- TODO:

    test_miss_need_writebackfull()
    test_miss_need_writebackfull_and_probe("probeack")
    test_miss_need_writebackfull_and_probe("probeack_data")
    -- test_miss_need_writebackfull_and_probe("probeack_data_multi_clients") -- TODO:
    
    test_snoop_shared()
    test_snoop_unique()

    test_stage2_mshr_retry()
    test_stage4_mshr_retry()
    test_replresp_retry()
    test_txrsp_mp_replay()
    test_sinkA_replay()

    test_snoop_nested_writebackfull()
    test_snoop_nested_evict()

    test_multi_probe()
    test_release_nested_probe()
    test_grant_block_probe()
    test_acquire_BtoT_miss()
    test_grant_on_stage4()
    end

   
    env.posedge(200)
    env.TEST_SUCCESS()
end }
