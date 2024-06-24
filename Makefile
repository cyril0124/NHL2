
module = NHL2
package ?= SimpleL2
target ?= L2Cache
simulator ?= vcs

SUCCESS_STRING = ">>>TEST_SUCCESS!<<<"
GREEN = \033[1;32m
RED = \033[1;31m
RESET = \033[0m

idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init --recursive 

reformat:
	mill -i NHL2.reformat

compile:
	mill -i $(module).compile
	mill -i $(module).test.compile

simple-test-top:
	mill -i $(module).test.runMain $(module).SimpleTestTop -td build

l2cache:
	mill -i $(module).runMain $(package).L2Cache -td build

rtl:
	mill -i --jobs 16 $(module).runMain $(package).$(target) -td build

unit-test:
	verilua_run -f ./build/$(target).v --prjdir . --top $(target) --lua-main ./src/main/lua/$(package)/$(target).lua --lua-file ./lua_file.f --sim $(simulator) --top-file ./build/$(target).v --shutdown 10000

unit-test-filelist:
	verilua_run -f ./build/$(target).v --prjdir . --top $(target) --lua-main ./src/main/lua/$(package)/$(target).lua --lua-file ./lua_file.f --sim $(simulator) --top-file ./build/$(target)/filelist.f --file ./build/$(target)/filelist.f --shutdown 10000

unit-test-quiet:
	@verilua_run -f ./build/$(target).v --prjdir . --top $(target) --lua-main ./src/main/lua/$(package)/$(target).lua --lua-file ./lua_file.f --sim $(simulator) --top-file ./build/$(target).v --shutdown 10000 > /dev/null
	@if grep -q $(SUCCESS_STRING) ./.verilua/$(target)/run.log; then \
		echo -e "UnitTest <$(target)> $(GREEN)SUCCESS!$(RESET)"; \
	else \
		echo -e "UnitTest <$(target)> $(RED)FAILED!$(RESET)"; \
	fi

.PHONY: l2cache rtl uint-test unit-test-quiet