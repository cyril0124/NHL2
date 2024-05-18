#!/usr/bin/env bash

curr_dir=$(realpath .)
prj_dir=$(realpath ..)
build_dir=${prj_dir}/build
lua_dir=${prj_dir}/src/main/lua

cd ${prj_dir}; make package=AsyncBridgeCHI target=AsyncBridgeCHI rtl

export CASE_NAME=slow_to_fast
cd ${curr_dir}; verilua_run -f ${build_dir}/AsyncBridgeCHI_TB.v \
                            --prjdir . \
                            --top AsyncBridgeCHI_TB \
                            --lua_main ${lua_dir}/AsyncBridgeCHI/AsyncBridgeCHI.lua \
                            --lua_file ${lua_dir}/common/env.lua \
                            --sim vcs \
                            --top_file ${build_dir}/AsyncBridgeCHI_TB.v \
                            --shutdown 10000 \
                            --tb-gen-args "--period 5 --custom-code ${lua_dir}/AsyncBridgeCHI/slow_to_fast.v"


