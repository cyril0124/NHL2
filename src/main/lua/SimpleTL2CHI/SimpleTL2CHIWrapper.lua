local env = require "env"
local tl = require "TileLink"
local chi = require "CHI"

local TLParam = tl.TLParam
local TLOpcodeA = tl.TLOpcodeA
local TLOpcodeD = tl.TLOpcodeD

local OpcodeDAT = chi.OpcodeDAT
local OpcodeREQ = chi.OpcodeREQ
local OpcodeRSP = chi.OpcodeRSP
local CHIResp = chi.CHIResp
local CHIOrder = chi.CHIOrder

local tl_a = ([[
    | valid
    | ready
    | opcode
    | param
    | size
    | mask
    | data
    | source
    | address
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "tl2chi_0_a_", name = "tl_a"}

tl_a.get = function (this, addr, source)
    assert(addr ~= nil)

    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.Get)
        this.bits.address:set(addr, true)
        this.bits.param:set(0)
        this.bits.source:set(source or 0)
        this.bits.size:set(3) -- 2^3 == 8 bytes
    env.negedge()
        this.valid:set(0)
end

tl_a.put_full = function (this, addr, source, data)
    assert(addr ~= nil)

    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.PutFullData)
        this.bits.address:set(addr, true)
        this.bits.data:set(data, true)
        this.bits.param:set(0)
        this.bits.source:set(source or 0)
        this.bits.size:set(3) -- 2^3 == 8 bytes
    env.negedge()
        this.valid:set(0)
end

tl_a.put_partial = function (this, addr, source, data, mask)
    assert(addr ~= nil)

    env.negedge()
        this.valid:set(1)
        this.bits.opcode:set(TLOpcodeA.PutPartialData)
        this.bits.address:set(addr, true)
        this.bits.data:set(data, true)
        this.bits.mask:set(mask)
        this.bits.param:set(0)
        this.bits.source:set(source or 0)
        this.bits.size:set(3) -- 2^3 == 8 bytes
    env.negedge()
        this.valid:set(0)
end

local tl_d = ([[
    | valid
    | ready
    | data
    | sink
    | source
    | param
    | opcode
]]):bundle {hier = cfg.top, is_decoupled=true, prefix = "tl2chi_0_d_", name = "tl_d"}

local chi_txreq = ([[
    | valid
    | ready
    | addr
    | opcode
    | txnID
    | addr
    | allowRetry
    | pCrdType
    | order
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txreq_", name = "chi_txreq"}

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
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_txdat_", name = "chi_txdat"}

local chi_rxrsp = ([[
    | valid
    | ready
    | opcode
    | txnID
    | dbID
    | srcID
    | pCrdType
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxrsp_", name = "chi_rxrsp"}

chi_rxrsp.comp_dbidresp = function (this, txn_id, db_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.CompDBIDResp)
        chi_rxrsp.bits.dbID:set(db_id)
        chi_rxrsp.valid:set(1)
    env.negedge()
        chi_rxrsp.valid:set(0)
end

chi_rxrsp.comp = function (this, txn_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.Comp)
        chi_rxrsp.valid:set(1)
    env.negedge()
        chi_rxrsp.valid:set(0)
end

chi_rxrsp.dbidresp = function (this, txn_id, db_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.DBIDResp)
        chi_rxrsp.bits.dbID:set(db_id)
        chi_rxrsp.valid:set(1)
    env.negedge()
        chi_rxrsp.valid:set(0)
end

chi_rxrsp.read_receipt = function (this, txn_id)
    env.negedge()
        chi_rxrsp.bits.txnID:set(txn_id)
        chi_rxrsp.bits.opcode:set(OpcodeRSP.ReadReceipt)
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
]]):bundle {hier = cfg.top, is_decoupled = true, prefix = "io_chi_rxdat_", name = "rxdat"}

chi_rxdat.compdat = function (this, txn_id, data_str, dbID, resp)
    local dbID = dbID or 0
    local resp = resp or CHIResp.I
    env.negedge()
        chi_rxdat.bits.txnID:set(txn_id)
        chi_rxdat.bits.dataID:set(0)
        chi_rxdat.bits.opcode:set(OpcodeDAT.CompData)
        chi_rxdat.bits.data:set_str(data_str)
        chi_rxdat.bits.dbID:set(dbID)
        chi_rxdat.bits.resp:set(resp)
        chi_rxdat.valid:set(1)
    env.negedge()
        chi_rxdat.valid:set(0)
end

local nr_machine = 4
local machines = {}
for i = 1, nr_machine do
    table.insert(machines, (i - 1), dut.u_SimpleTL2CHIWrapper.simpleTL2CHI.machineHandler["machines_" .. (i - 1)])
end

local function initialize_tl_ports()
    tl_d.ready:set(1)
end

local function initialize_chi_ports()
    chi_txreq.ready:set(1)
    chi_txdat.ready:set(1)
end

local test_basic_get_put = env.register_test_case "test_basic_get_put" {
    function ()
        env.dut_reset()
        initialize_tl_ports()
        initialize_chi_ports()

        do
            print("Get")
            local source = 0x4
            tl_a:get(0x100, source)
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNoSnp) and chi_txreq.bits.order:is(CHIOrder.RequestOrder) end)
            
            local txn_id = chi_txreq.bits.txnID:get()
            local db_id = 6
            env.negedge()
                chi_rxrsp:read_receipt(txn_id)
            env.negedge()
                chi_rxdat:compdat(txn_id, "0xaabb", db_id, 0)
            env.expect_happen_until(10, function() return tl_d:fire() and tl_d.bits.opcode:is(TLOpcodeD.AccessAckData) and tl_d.bits.source:is(source) and tl_d.bits.data:is(0xaabb) end)
            
            env.posedge(10)
            machines[0].io_status_state:expect(0)
        end

        do
            print("PutFullData")
            local source = 0x5
            tl_a:put_full(0x100, source, 0xdead)
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) end)

            local txn_id = chi_txreq.bits.txnID:get()
            local db_id = 6
            chi_rxrsp:comp_dbidresp(txn_id, db_id)
            env.expect_happen_until(10, function() return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.NCBWrDataCompAck) and chi_txdat.bits.dataID:is(0) end)
            chi_txdat.bits.data:expect_hex_str("0xdead")
            chi_txdat.bits.be:expect_bin_str("0b11111111") -- full mask
            
            env.negedge(10)
            machines[0].io_status_state:expect(0)
        end

        do
            print("PutPartialData + CompDBIDResp")
            local source = 0x5
            tl_a:put_partial(0x100, source, 0xbeef, ("0b00000011"):number())
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) end)
            
            local txn_id = chi_txreq.bits.txnID:get()
            local db_id = 6
            chi_rxrsp:comp_dbidresp(txn_id, db_id)
            env.expect_happen_until(10, function() return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.NCBWrDataCompAck) and chi_txdat.bits.dataID:is(0) end)
            chi_txdat.bits.data:expect_hex_str("0xbeef")
            chi_txdat.bits.be:expect_bin_str("0b00000011") -- partial mask 

            env.negedge(10)
            machines[0].io_status_state:expect(0)
        end

        do
            print("PutPartialData + Comp + DBIDResp")
            local source = 0x5
            tl_a:put_partial(0x100, source, 0xbeef, ("0b00000011"):number())
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) end)
            
            local txn_id = chi_txreq.bits.txnID:get()
            local db_id = 6
            env.negedge()
                chi_rxrsp:comp(txn_id)
            env.negedge()
                chi_rxrsp:dbidresp(txn_id, db_id)
            env.expect_happen_until(10, function() return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.NCBWrDataCompAck) and chi_txdat.bits.dataID:is(0) end)
            chi_txdat.bits.data:expect_hex_str("0xbeef")
            chi_txdat.bits.be:expect_bin_str("0b00000011") -- partial mask 

            env.negedge(10)
            machines[0].io_status_state:expect(0)
        end

        do
            print("PutPartialData + DBIDResp + Comp")
            local source = 0x5
            tl_a:put_partial(0x100, source, 0xbeef, ("0b00000011"):number())
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) end)
            
            local txn_id = chi_txreq.bits.txnID:get()
            local db_id = 6
            env.negedge()
                chi_rxrsp:dbidresp(txn_id, db_id)
            env.negedge()
                chi_rxrsp:comp(txn_id)
            env.expect_happen_until(10, function() return chi_txdat:fire() and chi_txdat.bits.opcode:is(OpcodeDAT.NCBWrDataCompAck) and chi_txdat.bits.dataID:is(0) end)
            chi_txdat.bits.data:expect_hex_str("0xbeef")
            chi_txdat.bits.be:expect_bin_str("0b00000011") -- partial mask 

            env.negedge(10)
            machines[0].io_status_state:expect(0)
        end

        env.posedge(100)
    end
}

local test_block_get = env.register_test_case "test_block_get" {
    function ()
        env.dut_reset()
        initialize_tl_ports()
        initialize_chi_ports()

        local source = 0x4
        tl_a:get(0x100, source)
        env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.ReadNoSnp) and chi_txreq.bits.order:is(CHIOrder.RequestOrder) end)
        
        env.negedge()
            tl_a.valid:set(1)
            tl_a.bits.opcode:set(TLOpcodeA.Get)
            tl_a.bits.address:set(0x100)
            env.posedge()
            tl_a.ready:expect(0) -- same address Get will be blocked
        env.negedge()
            tl_a.valid:set(0)

        env.posedge(100)
    end
}

local test_block_put = env.register_test_case "test_block_put" {
    function ()
        env.dut_reset()
        initialize_tl_ports()
        initialize_chi_ports()

        local source = 0x4
        do
            tl_a:put_full(0x100, source, 0xdead)
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) and chi_txreq.bits.order:is(CHIOrder.OWO) end)
            
            env.negedge()
                tl_a.valid:set(1)
                tl_a.bits.opcode:set(TLOpcodeA.PutFullData)
                tl_a.bits.address:set(0x100)
                env.posedge()
                tl_a.ready:expect(0) -- same address PutFullData will be blocked
            env.negedge()
                tl_a.valid:set(0)
        end

        env.dut_reset()

        do
            tl_a:put_partial(0x100, source, 0xbeef, ("0b00000011"):number())
            env.expect_happen_until(10, function() return chi_txreq:fire() and chi_txreq.bits.opcode:is(OpcodeREQ.WriteNoSnpPtl) and chi_txreq.bits.order:is(CHIOrder.OWO) end)
            
            env.negedge()
                tl_a.valid:set(1)
                tl_a.bits.opcode:set(TLOpcodeA.PutPartialData)
                tl_a.bits.address:set(0x100)
                env.posedge()
                tl_a.ready:expect(0) -- same address PutPartialData will be blocked
            env.negedge()
                tl_a.valid:set(0)
        end

        env.posedge(100)
    end
}

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        test_basic_get_put()
        test_block_get()
        test_block_put()

        sim.finish()
    end
}
