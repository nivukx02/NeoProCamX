"""Compile and execute production lossless-codec/controller checks on the SDK-ADB device.

This is native correctness evidence, never a recording or image-quality PASS.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess

from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--serial", default="10BF8M2NDX003P3")
    parser.add_argument("--sdk", default=r"C:\Users\ELCOT\AppData\Local\Android\Sdk", type=Path)
    parser.add_argument("--recorded-prefix", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    native = repo / "app/src/main/cpp"
    ndk = args.sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64"
    adb = args.sdk / "platform-tools/adb.exe"
    manifest = {}
    for path in [native / "npv10_lossless_storage_pressure.h", native / "npv10_lossless_chunk_codec.cpp",
                 native / "npv10_lossless_chunk_codec.h", native / "npv10_split10_simd.h",
                 native / "third_party/lz4/lz4.c", repo / "tools/lq001_lossless_storage_pressure_test.cpp",
                 repo / "tools/lq001_lz4_device_benchmark.cpp", repo / "tools/lq001_nlc2_bounds_device_test.cpp"]:
        manifest[str(path.relative_to(repo))] = hashlib.sha256(path.read_bytes()).hexdigest()
    (evidence / "source-sha256.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    with (evidence / "commands.jsonl").open("x", encoding="utf-8") as log:
        def run(command, timeout=120):
            p = subprocess.run([str(item) for item in command], capture_output=True, text=True,
                               timeout=timeout, encoding="utf-8", errors="replace")
            log.write(json.dumps({"time": datetime.now().isoformat(), "command": list(map(str, command)),
                                  "exit_code": p.returncode, "stdout": p.stdout, "stderr": p.stderr}) + "\n")
            log.flush()
            if p.returncode:
                raise RuntimeError(f"Validation command failed ({p.returncode}): {p.stderr}\n{p.stdout}")
            return p.stdout

        flags = ["--target=aarch64-linux-android28", "-O3", "-Wall", "-Wextra", "-Werror", "-I" + str(native)]
        lz4 = evidence / "lz4.o"
        run([ndk / "bin/clang.exe", *flags, "-c", native / "third_party/lz4/lz4.c", "-o", lz4])
        for label, source, codec in [
            ("pressure-policy", "lq001_lossless_storage_pressure_test.cpp", False),
            ("lossless-codec", "lq001_lz4_device_benchmark.cpp", True),
            ("decoder-bounds", "lq001_nlc2_bounds_device_test.cpp", True),
        ]:
            command = [ndk / "bin/clang++.exe", *flags, "-std=c++17", "-static-libstdc++",
                       repo / "tools" / source]
            if codec:
                command.extend([native / "npv10_lossless_chunk_codec.cpp", lz4, "-lz"])
            command.extend(["-o", evidence / label])
            run(command)
        print("NATIVE_COMPILE_PASS arm64-v8a, zero compiler warnings", flush=True)

        def shell(*words, timeout=45):
            return run([adb, "-s", args.serial, "shell", shlex.join(words)], timeout=timeout).strip()

        with ExclusiveAdbLease(None, "root-inline-lossless-pressure-native-validation", args.serial):
            if shell("getprop", "ro.serialno") != "10BF8M2NDX003P3":
                raise RuntimeError("Wrong physical device")
            try:
                shell("am", "force-stop", "com.neoproc.neoproccamx")
                shell("input", "keyevent", "KEYCODE_WAKEUP")
                shell("getprop", "ro.build.fingerprint")
                package_path = shell("pm", "path", "com.neoproc.neoproccamx").removeprefix("package:")
                shell("sha256sum", package_path)
                shell("df", "-k", "/data")
                for label in ("pressure-policy", "lossless-codec", "decoder-bounds"):
                    thermal = shell("dumpsys", "thermalservice")
                    current = thermal.split("Current temperatures from HAL:", 1)[-1].split(
                        "Current cooling devices from HAL:", 1)[0]
                    statuses = [int(v) for v in re.findall(r"mStatus=([0-6])\}", current)]
                    status = re.search(r"Thermal Status:\s*([0-6])", thermal)
                    if status is None or max([int(status[1]), *statuses]) >= 5:
                        raise RuntimeError("Emergency/missing thermal state: native run not admitted")
                    remote = "/data/local/tmp/camx-" + evidence.name + "-" + label
                    run([adb, "-s", args.serial, "push", evidence / label, remote])
                    shell("chmod", "700", remote)
                    print("DEVICE_NATIVE_START " + label, flush=True)
                    result = shell(remote, timeout=180)
                    (evidence / (label + ".jsonl")).write_text(result + "\n", encoding="utf-8")
                    print(result.splitlines()[-1], flush=True)
                    if label == "lossless-codec" and args.recorded_prefix:
                        prefix = args.recorded_prefix.resolve()
                        remote_prefix = remote + "-frame-prefix.bin"
                        run([adb, "-s", args.serial, "push", prefix, remote_prefix])
                        command = shlex.join([remote, "--npv10-stdin"]) + " < " + shlex.quote(remote_prefix)
                        result = run([adb, "-s", args.serial, "shell", command], timeout=180)
                        (evidence / "recorded-frame.jsonl").write_text(result, encoding="utf-8")
                        print("RECORDED_FRAME_BIT_EXACT_PASS", flush=True)
                shell("dumpsys", "thermalservice")
            finally:
                try:
                    shell("am", "force-stop", "com.neoproc.neoproccamx")
                finally:
                    shell("input", "keyevent", "KEYCODE_SLEEP")
    print("NATIVE_VALIDATION_COMPLETE (not a recording PASS)", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
