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

    I   = tonumber("0b0000"),
    BC  = tonumber("0b0100"),
    BD  = tonumber("0b0101"),
    BBC = tonumber("0b0110"),
    BBD = tonumber("0b0111"),
    TTC = tonumber("0b1010"),
    TTD = tonumber("0b1011"),
    TC  = tonumber("0b1100"),
    TD  = tonumber("0b1101"),
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