target("TestSimpleTL2CHI")
    add_rules("verilua")
    add_toolchains("@vcs")

    add_files("build/SimpleTL2CHIWrapper.v")
    add_files("src/main/lua/common/*.lua")

    set_values("cfg.top", "SimpleTL2CHIWrapper")
    set_values("cfg.lua_main", "src/main/lua/SimpleTL2CHI/SimpleTL2CHIWrapper.lua")