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

local urandom = urandom

local function build_channel(tl_prefix, chi_prefix)
    local tl_prefix = tl_prefix or "io_tl_"
    local chi_prefix = chi_prefix or "io_chi_"

    local channels = {}

    local tl_a = ([[
        | valid
        | ready
        | opcode
        | param
        | size
        | source
        | address
        | user_nanhu_pfHint
        | user_nanhu_vaddr
        | user_nanhu_alias
    ]]):bundle {hier = cfg.top, is_decoupled=true, prefix = tl_prefix .. "a_", name = "tl_a", optional_signals = {"user_nanhu_pfHint", "user_nanhu_vaddr", "user_nanhu_alias"} }
    
    local tl_b = ([[
        | valid
        | ready
        | opcode
        | param
        | size
        | source
        | address
        | data
    ]]):bundle {hier = cfg.top, is_decoupled=true, prefix = tl_prefix .. "b_", name = "tl_b"}
    
    local tl_c = ([[
        | valid
        | ready
        | opcode
        | param
        | size
        | source
        | address
        | data
    ]]):bundle {hier = cfg.top, is_decoupled=true, prefix = tl_prefix .. "c_", name = "tl_c"}
    
    local tl_d = ([[
        | valid
        | ready
        | data
        | sink
        | source
        | param
        | opcode
    ]]):bundle {hier = cfg.top, is_decoupled=true, prefix = tl_prefix .. "d_", name = "tl_d"}
    
    local tl_e = ([[
        | valid
        | ready
        | sink
    ]]):bundle {hier = cfg.top, is_decoupled=true, prefix = tl_prefix .. "e_", name = "tl_e"}
    
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
    
    tl_a.acquire_block = function (this, addr, param, source, need_hint)
        assert(addr ~= nil)
        assert(param ~= nil)
    
        env.negedge()
            this.ready:expect(1)
            this.valid:set(1)
            this.bits.opcode:set(TLOpcodeA.AcquireBlock)
            this.bits.address:set(addr, true)
            this.bits.param:set(param)
            this.bits.source:set(source or 0)
            this.bits.user_nanhu_alias:set(0)
            this.bits.size:set(6) -- 2^6 == 64
            if need_hint ~= nil then
                this.bits.user_nanhu_pfHint:set(need_hint)
            end
            env.posedge()
            this.ready:expect(1)
        env.negedge()
            this.valid:set(0)
        env.negedge()
    end
    
    tl_a.acquire_alias = function (this, opcode, addr, param, source, alias)
        assert(addr ~= nil)
        assert(param ~= nil)
    
        env.negedge()
            this.ready:expect(1)
            this.valid:set(1)
            this.bits.opcode:set(opcode)
            this.bits.address:set(addr, true)
            this.bits.param:set(param)
            this.bits.source:set(source or 0)
            this.bits.user_nanhu_alias:set(alias or 0)
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
    
    local chi_txreq = ([[
        | valid
        | ready
        | addr
        | opcode
        | txnID
        | addr
        | allowRetry
        | pCrdType
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "txreq_", name = "chi_txreq"}
    
    local chi_txdat = ([[
        | valid
        | ready
        | opcode
        | resp
        | respErr
        | txnID
        | tgtID
        | dbID
        | dataID
        | data
        | be
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "txdat_", name = "chi_txdat"}
    
    local chi_txrsp = ([[
        | valid
        | ready
        | opcode
        | resp
        | tgtID
        | srcID
        | txnID
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "txrsp_", name = "chi_txrsp"}
    
    local chi_rxrsp = ([[
        | valid
        | ready
        | opcode
        | resp
        | txnID
        | dbID
        | srcID
        | pCrdType
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "rxrsp_", name = "chi_rxrsp"}
    
    chi_rxrsp.comp = function (this, txn_id, db_id, resp)
        env.negedge()
            chi_rxrsp.bits.txnID:set(txn_id)
            chi_rxrsp.bits.opcode:set(OpcodeRSP.Comp)
            chi_rxrsp.bits.dbID:set(db_id)
            chi_rxrsp.bits.resp:set(resp or 0)
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
    
    chi_rxrsp.retry_ack = function (this, txn_id, src_id, pcrd_type)
        env.negedge()
            chi_rxrsp.bits.txnID:set(txn_id)
            chi_rxrsp.bits.opcode:set(OpcodeRSP.RetryAck)
            chi_rxrsp.bits.srcID:set(src_id)
            chi_rxrsp.bits.pCrdType:set(pcrd_type)
            chi_rxrsp.valid:set(1)
        env.negedge()
            chi_rxrsp.valid:set(0)
    end
    
    chi_rxrsp.pcrd_grant = function (this, src_id, pcrd_type)
        env.negedge()
            chi_rxrsp.bits.opcode:set(OpcodeRSP.PCrdGrant)
            chi_rxrsp.bits.txnID:set(urandom() % 16)
            chi_rxrsp.bits.srcID:set(src_id)
            chi_rxrsp.bits.pCrdType:set(pcrd_type)
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
        | homeNID
        | txnID
        | dbID
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "rxdat_", name = "rxdat"}
    
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
        | srcID
        | txnID
        | fwdNID
        | fwdTxnID
        | doNotGoToSD
        | retToSrc
    ]]):bundle {hier = cfg.top, is_decoupled = true, prefix = chi_prefix .. "rxsnp_", name = "chi_rxsnp"}
    
    chi_rxsnp.send_request = function(this, addr, opcode, txn_id, ret2src, src_id)
        local addr = bit.rshift(addr, 3) -- Addr in CHI SNP channel has 3 fewer bits than full address
        env.negedge()
            chi_rxsnp.ready:expect(1)
            chi_rxsnp.bits.srcID:set(src_id or 0)
            chi_rxsnp.bits.txnID:set(txn_id)
            chi_rxsnp.bits.addr:set(addr, true)
            chi_rxsnp.bits.opcode:set(opcode)
            chi_rxsnp.bits.retToSrc:set(ret2src)
            chi_rxsnp.valid:set(1)
            env.posedge()
            chi_rxsnp.ready:expect(1)
        env.negedge()
            chi_rxsnp.valid:set(0)
    end
    
    chi_rxsnp.send_fwd_request = function(this, addr, opcode, src_id, txn_id, ret2src, fwd_nid, fwd_txn_id, do_not_go_to_sd)
        local do_not_go_to_sd = do_not_go_to_sd or false
        local addr = bit.rshift(addr, 3) -- Addr in CHI SNP channel has 3 fewer bits than full address
        env.negedge()
            chi_rxsnp.ready:expect(1)
            chi_rxsnp.bits.srcID:set(src_id)
            chi_rxsnp.bits.txnID:set(txn_id)
            chi_rxsnp.bits.addr:set(addr, true)
            chi_rxsnp.bits.opcode:set(opcode)
            chi_rxsnp.bits.retToSrc:set(ret2src)
            chi_rxsnp.bits.fwdNID:set(fwd_nid)
            chi_rxsnp.bits.fwdTxnID:set(fwd_txn_id)
            chi_rxsnp.bits.doNotGoToSD:set(do_not_go_to_sd)
            chi_rxsnp.valid:set(1)
            env.posedge()
            chi_rxsnp.ready:expect(1)
        env.negedge()
            chi_rxsnp.valid:set(0)
    end
    
    chi_rxsnp.snpshared = function (this, addr, txn_id, ret2src, src_id)
        chi_rxsnp:send_request(addr, OpcodeSNP.SnpShared, txn_id, ret2src, src_id)
    end
    
    chi_rxsnp.snpunique = function (this, addr, txn_id, ret2src, src_id)
        chi_rxsnp:send_request(addr, OpcodeSNP.SnpUnique, txn_id, ret2src, src_id)
    end

    return {
        tl_a = tl_a,
        tl_b = tl_b,
        tl_c = tl_c,
        tl_d = tl_d,
        tl_e = tl_e,
        chi_txreq = chi_txreq,
        chi_txdat = chi_txdat,
        chi_txrsp = chi_txrsp,
        chi_rxsnp = chi_rxsnp,
        chi_rxdat = chi_rxdat,
        chi_rxrsp = chi_rxrsp
    }
end

return {
    build_channel = build_channel
}