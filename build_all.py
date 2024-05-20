#!/usr/bin/env python3
import subprocess

build_targets = [
    ("SimpleL2",       "L2Cache"        ),
    ("SimpleL2",       "CHIBridge"      ),
    ("SimpleL2",       "Directory"      ),
    ("SimpleL2",       "RequestArbiter" ),
    ("SimpleL2",       "RequestBuffer"  ),
    ("AsyncBridgeCHI", "AsyncBridgeCHI" ),
]

def execute_cmd_pipe(cmd):
    return subprocess.run(cmd, shell=True, stderr=subprocess.PIPE, text=True)

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
    if not isinstance(ret, int) and ret.stderr != None and "error" in ret.stderr:
        assert False, f"build package: {package} target: {target} failed! ==> {ret.stderr}"
    print(f"""\
---------------------------------------------------------
| [{index}/{max_builds}] => build SUCCESS! package: {package} target: {target}  
---------------------------------------------------------
""")
