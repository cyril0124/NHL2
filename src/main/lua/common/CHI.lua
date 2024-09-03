local utils = require "LuaUtils"
local setmetatable = setmetatable
local lshift = bit.lshift
local enum_search = utils.enum_search

local OpcodeREQ = setmetatable({
    name = "OpcodeREQ",

    ReqLCrdReturn     = 0x00 + lshift(0, 6),
    ReadShared        = 0x01 + lshift(0, 6),
    MakeReadUnique    = 0x01 + lshift(1, 6),

    ReadClean         = 0x02 + lshift(0, 6),
    WriteEvictOrEvict = 0x02 + lshift(1, 6),
    ReadOnce          = 0x03 + lshift(0, 6),
    ReadNoSnp         = 0x04 + lshift(0, 6),
    PCrdReturn        = 0x05 + lshift(0, 6),
    ReadUnique        = 0x07 + lshift(0, 6),

    CleanUnique       = 0x0b + lshift(0, 6),
    MakeUnique        = 0x0c + lshift(0, 6),
    Evict             = 0x0d + lshift(0, 6),

    WriteUniqueFull   = 0x19 + lshift(0, 6),
    WriteBackFull     = 0x1B + lshift(0, 6),

    ReadNotSharedDirty = 0x26 + lshift(0, 6),
}, { __call = enum_search })


local OpcodeDAT = setmetatable({
    name = "OpcodeDAT",

    DataLCrdReturn    = 0x00,
    SnpRespData       = 0x01,
    CopyBackWrData    = 0x02,
    NonCopyBackWrData = 0x03,
    CompData          = 0x04,
    SnpRespDataPtl    = 0x05,
    SnpRespDataFwded  = 0x06,
    WriteDataCancel   = 0x07,
    DataSepResp       = 0x0B,
    NCBWrDataCompAck  = 0x0C,
}, { __call = enum_search })

local OpcodeRSP = setmetatable({
    name = "OpcodeRSP",

    RespLCrdReturn  = 0x00,
    SnpResp         = 0x01,
    CompAck         = 0x02,
    RetryAck        = 0x03,
    Comp            = 0x04,
    CompDBIDResp    = 0x05,
    DBIDResp        = 0x06,
    PCrdGrant       = 0x07,
    ReadReceipt     = 0x08,
}, { __call = enum_search })


local OpcodeSNP = setmetatable({
    name = "OpcodeSNP",

    SnpLCrdReturn        = 0x00,
    SnpShared            = 0x01,
    SnpClean             = 0x02,
    SnpOnce              = 0x03,
    SnpNotSharedDirty    = 0x04,
    SnpUnique            = 0x07,
    SnpCleanShared       = 0x08,
    SnpCleanInvalid      = 0x09,
    SnpMakeInvalid       = 0x0A,
    SnpQuery             = 0x10,
    SnpSharedFwd         = 0x11,
    SnpCleanFwd          = 0x12,
    SnpOnceFwd           = 0x13,
    SnpNotSharedDirtyFwd = 0x14,
    SnpPreferUnique      = 0x15,
    SnpPreferUniqueFwd   = 0x16,
    SnpUniqueFwd         = 0x17,
}, { __call = enum_search })


local function dat_has_data(opcode)
    return opcode == OpcodeDAT.SnpRespData or opcode == OpcodeDAT.CopyBackWrData or opcode == OpcodeDAT.CompData
end

local function dat_is_snpresp(opcode)
    return opcode == OpcodeDAT.SnpRespData or opcode == OpcodeDAT.SnpRespDataPtl or opcode == OpcodeDAT.SnpRespDataFwded
end

local function req_need_data(opcode)
    return opcode == OpcodeREQ.ReadShared or opcode == OpcodeREQ.ReadUnique or opcode == OpcodeREQ.ReadNotSharedDirty
end

local CHIResp = utils.enum_define {
    name = "CHIResp",
    
    I     = ("0b000"):number(),
    SC    = ("0b001"):number(),
    UC    = ("0b010"):number(),
    UD    = ("0b010"):number(),  -- for Snoop responses
    SD    = ("0b011"):number(),  -- for Snoop responses
    I_PD  = ("0b100"):number(),  -- for Snoop responses
    SC_PD = ("0b101"):number(),  -- for Snoop responses
    UC_PD = ("0b110"):number(),  -- for Snoop responses
    UD_PD = ("0b110"):number(),
    SD_PD = ("0b111"):number(),
}

return {
    OpcodeREQ = OpcodeREQ,
    OpcodeDAT = OpcodeDAT,
    OpcodeRSP = OpcodeRSP,
    OpcodeSNP = OpcodeSNP,
    dat_has_data  = dat_has_data,
    req_need_data = req_need_data,
    dat_is_snpresp = dat_is_snpresp,
    CHIResp = CHIResp,
}