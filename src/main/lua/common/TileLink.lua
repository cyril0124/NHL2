local utils = require "LuaUtils"
local tonumber = tonumber

local TLChannel = utils.enum_define {
    name = "TLChannel",

    ChannelA = tonumber("0b001"),
    ChannelB = tonumber("0b010"),
    ChannelC = tonumber("0b100"),
}

local TLParam = utils.enum_define {
    name = "TLParam",

    -- Cap
    toT = 0,
    toB = 1,
    toN = 2,

    -- Grow
    NtoB = 0,
    NtoT = 1,
    BtoT = 2,

    -- Shrink
    TtoB = 0,
    TtoN = 1,
    BtoN = 2,

    -- Report
    TtoT = 3,
    BtoB = 4,
    NtoN = 5
}


local MixedState = utils.enum_define {
    name = "MixedState",

    I   = tonumber("0000", 2),
    BC  = tonumber("0100", 2),
    BD  = tonumber("0101", 2),
    BBC = tonumber("0110", 2),
    BBD = tonumber("0111", 2),
    TTC = tonumber("1010", 2),
    TTD = tonumber("1011", 2),
    TC  = tonumber("1100", 2),
    TD  = tonumber("1101", 2),
}

local TLOpcodeA = utils.enum_define {
    Get = 4,
    AcquireBlock = 6,
    AcquirePerm = 7,
}

local TLOpcodeC = utils.enum_define {
    Release = 6,
    ReleaseData = 7,
    ProbeAck = 4,
    ProbeAckData = 5,
}

return {
    TLChannel = TLChannel,
    TLParam = TLParam,
    MixedState = MixedState,
    TLOpcodeA = TLOpcodeA,
    TLOpcodeC = TLOpcodeC,
}