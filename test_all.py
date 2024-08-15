#!/usr/bin/env python3
import subprocess
import argparse
import json
import re

json_file_path = 'module_info.json'
default_simulator = "iverilog"

def execute_cmd(cmd):
    subprocess.run(cmd, shell=True, text=True)

def execute_cmd_pipe(cmd):
    return subprocess.run(cmd, shell=True, stderr=subprocess.PIPE, text=True)

def test_all(test_targets):
    max_tests = len(test_targets)
    for index, (package, target, cmd) in enumerate(test_targets):
        index = index + 1
        print(f"""\
------------------------------------------------------
| [{index}/{max_tests}] => start test package: {package} target: {target}
------------------------------------------------------""")
        if cmd != "":
            ret = execute_cmd_pipe(cmd + f" simulator={default_simulator}")
        else:
            ret = execute_cmd_pipe(f"make package={package} target={target} simulator={default_simulator} unit-test-quiet")
        if isinstance(ret, int) and ret != 0:
            assert False, f"build package: {package} target: {target} failed!"
        if not isinstance(ret, int) and ret.stderr != None and "[error]" in ret.stderr:
            assert False, f"build package: {package} target: {target} failed! ==> {ret.stderr}"
        print(f"""\
---------------------------------------------------------
| [{index}/{max_tests}] => test SUCCESS! package: {package} target: {target}  
---------------------------------------------------------
""")
        
def test_all_summary(test_targets):
    GREEN = "\033[92m"
    RED = "\033[91m"
    RESET = "\033[0m"
    test_count = 0
    print("\n---------------------- test_all summary ---------------------")
    for index, (package, target, cmd) in enumerate(test_targets):
        with open(f".verilua/{target}/run.log", 'r') as file:
            content = file.read()
            package_target_str = f"{package}.{target}"
            if ">>>TEST_SUCCESS!<<<" in content:
                print(f"[{index:2}] {package_target_str:40} test {GREEN}SUCCESS!{RESET} |")
            else:
                print(f"[{index:2}] {package_target_str:40} test  {RED}FAILED!{RESET} |")
            
            if "total_test_cases" in content:
                match = re.search(r'<(\d+)>', content)
                test_count = test_count + int(match.group(1))
    print("--------------------------------------------------------------\n")
    print(f"total_test_cases: {test_count}\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('--target', '-t', dest="target", type=str, help='build target')
    parser.add_argument('--package', '-p', dest="package", type=str, default="SimpleL2", help='build target package')
    parser.add_argument('--sim', '-s', choices=['vcs', 'verilator', 'iverilog', 'wave_vpi'], dest="sim", default="iverilog", help="simulator of the simulation")
    args = parser.parse_args() 
    
    if args.target != None:
        package = args.package
        target = args.target
        if args.sim == "verilator":
            cmd = f"WAVE_ENABLE=1 make simulator={args.sim} package={package} target={target} unit-test"
        else:
            cmd = f"make simulator={args.sim} package={package} target={target} unit-test"
        with open(json_file_path, 'r') as file:
            json_data = json.load(file)
        for entry in json_data:
            if entry['package'] == package and entry['target'] == target:
                if args.sim == "verilator":
                    cmd = "WAVE_ENABLE=1 " + entry.get('unit_test_cmd', cmd) + f" simulator={args.sim}"
                else:
                    cmd = entry.get('unit_test_cmd', cmd) + f" simulator={args.sim}"
        execute_cmd(cmd)
    else:
        with open(json_file_path, 'r') as file:
            json_data = json.load(file)
        test_targets = [(entry['package'], entry['target'], entry.get('unit_test_cmd', "")) for entry in json_data if entry['unit_test']]
        test_all(test_targets)
        test_all_summary(test_targets)
        