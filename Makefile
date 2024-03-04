
module = NHL2


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

