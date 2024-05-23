#!/usr/bin/env python3
import subprocess
import argparse
import json

json_file_path = 'module_info.json'

def execute_cmd(cmd):
    subprocess.run(cmd, shell=True, text=True)

def execute_cmd_pipe(cmd):
    return subprocess.run(cmd, shell=True, stderr=subprocess.PIPE, text=True)

def test_all(test_targets):
    max_tests = len(test_targets)
    for index, (package, target) in enumerate(test_targets):
        index = index + 1
        print(f"""\
------------------------------------------------------
| [{index}/{max_tests}] => start test package: {package} target: {target}
------------------------------------------------------""")
        ret = execute_cmd_pipe(f"make package={package} target={target} simulator=vcs unit-test-quiet")
        if isinstance(ret, int) and ret != 0:
            assert False, f"build package: {package} target: {target} failed!"
        if not isinstance(ret, int) and ret.stderr != None and "[error]" in ret.stderr:
            assert False, f"build package: {package} target: {target} failed! ==> {ret.stderr}"
        print(f"""\
---------------------------------------------------------
| [{index}/{max_tests}] => test SUCCESS! package: {package} target: {target}  
---------------------------------------------------------
""")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('--target', '-t', dest="target", type=str, help='build target')
    parser.add_argument('--package', '-p', dest="package", type=str, default="SimpleL2", help='build target package')
    args = parser.parse_args()
    
    if args.target != None:
        package = args.package
        target = args.target
        execute_cmd(f"make package={package} target={target} simulator=vcs unit-test")
    else:
        with open(json_file_path, 'r') as file:
            json_data = json.load(file)
        test_targets = [(entry['package'], entry['target']) for entry in json_data if entry['unit_test']]
        test_all(test_targets)
        