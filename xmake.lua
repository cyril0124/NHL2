local mill_project = "NHL2Project"
local mill_build_dir = "build"

local function generate_mill_build_targets(build_info_tbl)
    local package = build_info_tbl.package
    local modules = build_info_tbl.modules

    for _, module in ipairs(modules) do
        target(module)
            set_kind("phony")
            on_build(function (target)
                print("")
                print("-------------------------------------------------------------")
                print("==> build %s.%s", package, module)
                print("-------------------------------------------------------------")
                os.exec("mill -i %s.runMain %s.%s -td %s", mill_project, package, module, mill_build_dir)
            end)
    end

    target(package .. "All")
        set_kind("phony")
        on_build(function (target)
            for i, module in ipairs(modules) do
                print("")
                print("-------------------------------------------------------------")
                print("[%d/%d] ==> build_all %s.%s", i, #modules, package, module)
                print("-------------------------------------------------------------")
                os.exec("mill -i %s.runMain %s.%s -td %s", mill_project, package, module, mill_build_dir)
            end
        end)
end

local function generate_test_targets(test_info_tbl)
    for _, info in ipairs(test_info_tbl) do
        local files_table = info.files
        local top_name = info.top
        local lua_main = info.lua_main
        local sim = info.sim or "iverilog"
        target("Test" .. top_name)
            add_rules("verilua")
            add_toolchains("@" .. sim)
            
            add_files(files_table)
            add_files("src/main/lua/common/*.lua")

            set_values("cfg.top", top_name)
            set_values("cfg.lua_main", lua_main)
    end
end

local SimpleL2_modules = {
    "SimpleL2Cache",
    "SimpleL2CacheDecoupled",
    "Slice",
    "Directory",
    "DataStorage",
    "RequestBuffer",
    "MainPipe",
    "SinkA",
    "RequestArbiter",
    "TempDataStorage",
    "SourceD",
    "MissHandler",
    "TXREQ",
    "TXRSP",
    "RXDAT",
    "RXSNP",
    "MSHR",
    "SinkC",
    "SinkE",
    "ReplayStation"
}

local Utils_modules = {
    "LFSRArbiter",
    "SkidBuffer",
    "IDPool",
}

local SimpleTL2CHI_modules = {
    "SimpleTL2CHI",
}

generate_mill_build_targets {
    package = "SimpleL2",
    modules = SimpleL2_modules
}

generate_mill_build_targets {
    package = "Utils",
    modules = Utils_modules
}

generate_mill_build_targets {
    package = "simpleTL2CHI",
    modules = SimpleTL2CHI_modules
}

generate_test_targets {
    {
        files = {"build/Slice/*.sv"}, top = "Slice", lua_main = "src/main/lua/SimpleL2/Slice.lua", sim = "vcs"
    },
    {
        files = {"build/Directory/*.sv"}, top = "Directory", lua_main = "src/main/lua/SimpleL2/Directory.lua"
    },
    {
        files = {"build/DataStorage/*.sv"}, top = "DataStorage", lua_main = "src/main/lua/SimpleL2/DataStorage.lua"
    },
    {
        files = {"build/MainPipe.v"}, top = "MainPipe", lua_main = "src/main/lua/SimpleL2/MainPipe.lua"
    },
    {
        files = {"build/MissHandler.v"}, top = "MissHandler", lua_main = "src/main/lua/SimpleL2/MissHandler.lua"
    },
    {
        files = {"build/MSHR.v"}, top = "MSHR", lua_main = "src/main/lua/SimpleL2/MSHR.lua"
    },
    {
        files = {"build/ReplayStation.v"}, top = "ReplayStation", lua_main = "src/main/lua/SimpleL2/ReplayStation.lua"
    },
    {
        files = {"build/RequestArbiter.v"}, top = "RequestArbiter", lua_main = "src/main/lua/SimpleL2/RequestArbiter.lua"
    },
    {
        files = {"build/RequestBuffer.v"}, top = "RequestBuffer", lua_main = "src/main/lua/SimpleL2/RequestBuffer.lua"
    },
    {
        files = {"build/RXDAT.v"}, top = "RXDAT", lua_main = "src/main/lua/SimpleL2/RXDAT.lua"
    },
    {
        files = {"build/SinkC.v"}, top = "SinkC", lua_main = "src/main/lua/SimpleL2/SinkC.lua"
    },
    {
        files = {"build/SinkE.v"}, top = "SinkE", lua_main = "src/main/lua/SimpleL2/SinkE.lua"
    },
    {
        files = {"build/SourceD.v"}, top = "SourceD", lua_main = "src/main/lua/SimpleL2/SourceD.lua"
    },
    {
        files = {"build/TempDataStorage/*.sv"}, top = "TempDataStorage", lua_main = "src/main/lua/SimpleL2/TempDataStorage.lua"
    },
    {
        files = {"build/SimpleL2CacheWrapper.v"}, top = "SimpleL2CacheWrapper", lua_main = "src/main/lua/SimpleL2/SimpleL2CacheWrapper.lua"
    },
    {
        files = {"build/SimpleL2CacheWrapperDecoupled/*.sv"}, top = "SimpleL2CacheWrapperDecoupled", lua_main = "src/main/lua/SimpleL2/SimpleL2CacheWrapperDecoupled.lua", sim = "vcs"
    },
}

generate_test_targets {
    {
        files = {"build/IDPool.v"}, top = "IDPool", lua_main = "src/main/lua/Utils/IDPool.lua"
    },
    {
        files = {"build/LFSRArbiter.v"}, top = "LFSRArbiter", lua_main = "src/main/lua/Utils/LFSRArbiter.lua"
    },
    {
        files = {"build/SkidBuffer.v"}, top = "SkidBuffer", lua_main = "src/main/lua/Utils/SkidBuffer.lua"
    }
}

generate_test_targets {
    {
        files = {"build/SimpleTL2CHIWrapper.v"}, top = "SimpleTL2CHIWrapper", lua_main = "src/main/lua/SimpleTL2CHI/SimpleTL2CHIWrapper.lua"
    }
}

target("reformat")
    set_kind("phony")
    on_run(function (target)
        os.exec("mill -i %s.reformat", mill_project)
    end)

target("init")
    set_kind("phony")
    on_run(function (target)
        os.exec("git submodule update --init --recursive")
    end)

target("compile")
    set_kind("phony")
    on_run(function (target)
        os.exec("mill -i %s.compile", mill_project)
        os.exec("mill -i %s.test.compile", mill_project)
    end)

target("idea")
    set_kind("phony")
    on_run(function (target)
        os.exec("mill -i mill.idea.GenIdea/idea")
    end)