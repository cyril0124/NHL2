
module = NHL2
target ?= L2Cache

SUCCESS_STRING = ">>>TEST_SUCCESS!<<<"
GREEN = \033[1;32m
RED = \033[1;31m
RESET = \033[0m

idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init --recursive 

reformat:
	mill -i __.reformat

compile:
	mill -i $(module).compile
	mill -i $(module).test.compile

simple-test-top:
	mill -i $(module).test.runMain $(module).SimpleTestTop -td build

l2cache:
	mill -i $(module).runMain SimpleL2.L2Cache -td build

rtl:
	mill -i --jobs 16 $(module).runMain SimpleL2.$(target) -td build

unit-test:
	verilua_run -f ./build/$(target).v --prjdir . --top $(target) --lua_main ./src/main/lua/SimpleL2/$(target).lua --lua_file ./src/main/lua/common/env.lua --sim vcs --top_file ./build/$(target).v --shutdown 10000

unit-test-quiet:
	@verilua_run -f ./build/$(target).v --prjdir . --top $(target) --lua_main ./src/main/lua/SimpleL2/$(target).lua --lua_file ./src/main/lua/common/env.lua --sim vcs --top_file ./build/$(target).v --shutdown 10000 > /dev/null
	@if grep -q $(SUCCESS_STRING) ./.verilua/$(target)/run.log; then \
		echo -e "UnitTest <$(target)> $(GREEN)SUCCESS!$(RESET)"; \
	else \
		echo -e "UnitTest <$(target)> $(RED)FAILED!$(RESET)"; \
	fi

.PHONY: l2cache rtl uint-test unit-test-quiet