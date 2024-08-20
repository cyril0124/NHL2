#!/usr/bin/env python3
import subprocess
import argparse
import json
import os

json_file_path = 'module_info.json'

def execute_cmd(cmd):
    subprocess.run(cmd, shell=True, text=True)

def execute_cmd_pipe(cmd):
    return subprocess.run(cmd, shell=True, stderr=subprocess.PIPE, text=True)

def build_all(build_targets):
    max_builds = len(build_targets)
    for index, (package, target) in enumerate(build_targets):
        index = index + 1
        print(f"""\
------------------------------------------------------
| [{index}/{max_builds}] => start build package: {package} target: {target}
------------------------------------------------------""")
        ret = execute_cmd_pipe(f"make package={package} target={target} rtl")
        if isinstance(ret, int) and ret != 0:
            assert False, f"build package: {package} target: {target} failed!"
        
        has_error = "[error]" in ret.stderr
        has_failed = "failed" in ret.stderr
        if not isinstance(ret, int) and ret.stderr != None and (has_error or has_failed):
            assert False, f"build package: {package} target: {target} failed! ==> {ret.stderr}"
        print(f"""\
---------------------------------------------------------
| [{index}/{max_builds}] => build SUCCESS! package: {package} target: {target}  
---------------------------------------------------------
""")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('--target', '-t', dest="target", type=str, help='build target')
    parser.add_argument('--package', '-p', dest="package", type=str, default="SimpleL2", help='build target package')
    parser.add_argument('--release', '-r', dest="release", action='store_true', help='Build with release option')
    args = parser.parse_args()

    if args.release:
        os.environ['GEN_RELEASE'] = '1'
    
    if args.target != None:
        package = args.package
        target = args.target
        execute_cmd(f"make package={package} target={target} rtl")
    else:
        with open(json_file_path, 'r') as file:
            json_data = json.load(file)
        build_targets = [(entry['package'], entry['target']) for entry in json_data]
        build_all(build_targets)