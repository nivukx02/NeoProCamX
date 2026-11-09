"""One inline SDK-ADB owner for reproducible sequential LQ-001 recording runs.

This controller never marks a checkpoint PASS. The unchanged recording runner and subsequent
independent original-file inspector supply their own evidence and quality gates.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease
from tools.neo_qa_hypergrid.inspect_lq001_direct_recordings import inspect_and_record
from tools.neo_qa_hypergrid.lq001_external_stop import read_run_external_stop


def parse_case(value: str) -> tuple[str, str, str, str]:
    parts = value.split("/")
    if len(parts) != 4:
        raise ValueError("Invalid case")
    mode, pipeline, profile, resolution = parts
    if mode not in {"VIDEO", "PRO", "RAW"} or pipeline not in {"DIRECT", "CINEMA"}:
        raise ValueError("Unknown recording mode or pipeline")
    if mode == "RAW" and pipeline != "CINEMA":
        raise ValueError("RAW has only the Cinema pipeline; Direct would change RAW fidelity")
    if profile not in {"NATURAL_DETAIL", "FOCUSED_DETAIL", "EXTREME_DETAIL"}:
        raise ValueError("Unknown profile")
    if resolution not in {"3264x2448", "4096x3072", "3840x2160"}:
        raise ValueError("Unknown resolution")
    return mode, pipeline, profile, resolution


def recording_command(args, case: tuple[str, str, str, str]) -> list[str]:
    mode, pipeline, profile, resolution = case
    aspect = "16:9" if resolution == "3840x2160" else "4:3"
    script = "run_lq001_raw_endurance.ps1" if mode == "RAW" else "run_lq001_endurance.ps1"
    command = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
               "tools/neo_qa_hypergrid/" + script]
    if mode != "RAW":
        command.extend(["-Mode", mode, "-Pipeline", pipeline])
    command.extend(["-DetailProfile", profile, "-Resolution", resolution,
                    "-Aspect", aspect, "-Fps", "25", "-DurationSeconds", "300",
                    "-Serial", args.serial, "-AdbPath", args.adb,
                    "-EvidenceRoot", str(args.evidence_root)])
    background_at = getattr(args, "background_at_seconds", 0)
    if background_at:
        if mode == "RAW":
            raise ValueError("The RAW runner does not implement visible-window lifecycle injection")
        command.extend(["-BackgroundAtSeconds", str(background_at),
                        "-BackgroundDurationSeconds", str(args.background_duration_seconds)])
    return command


def find_new_run(evidence: Path, previous: set[Path], case: tuple[str, str, str, str]) -> Path:
    """Reject ambiguous/mismatched evidence instead of auditing an older PASS."""
    mode, pipeline, profile, resolution = case
    checkpoint = {"VIDEO": "05", "PRO": "06", "RAW": "07"}[mode]
    prefix = f"lq001_{checkpoint}_{mode.lower()}_{pipeline.lower()}_{profile.lower()}_{resolution}_25fps_"
    matches = [path for path in evidence.iterdir()
               if path.is_dir() and path not in previous and path.name.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"Expected one new matching recording directory, found {len(matches)}")
    return matches[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--physical-serial", required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--install-apk", type=Path)
    parser.add_argument("--native-residency-test", type=Path)
    parser.add_argument("--inspect-direct", action="store_true",
                        help="Independently inspect each Direct MOV before starting the next case")
    parser.add_argument("--background-at-seconds", type=int, default=0,
                        help="Explicit HOME/foreground regression; zero leaves ordinary tests unchanged")
    parser.add_argument("--background-duration-seconds", type=int, default=5)
    parser.add_argument("--case", action="append", required=True,
                        help="MODE/PIPELINE/DETAIL_PROFILE/WIDTHxHEIGHT; 300 seconds at 25 FPS")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Fa-f0-9]{64}", args.apk_sha256):
        raise ValueError("Expected a SHA-256 candidate identity")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        raise ValueError("Invalid evidence label")
    cases = []
    for value in args.case:
        cases.append(parse_case(value))
    if len(set(cases)) != len(cases):
        raise ValueError("Duplicate cases require separately labelled batches")
    if not 0 <= args.background_at_seconds <= 280 or not 1 <= args.background_duration_seconds <= 30:
        raise ValueError("Invalid lifecycle interruption boundary")
    if args.background_at_seconds and (
            args.background_at_seconds + args.background_duration_seconds + 5 >= 300
            or any(case[0] == "RAW" for case in cases)):
        raise ValueError("Lifecycle regression must be a supported Video/Pro flow within the recording")
    evidence = args.evidence_root
    evidence.mkdir(parents=True, exist_ok=True)
    log_path = evidence / (args.label + "-adb.jsonl")
    summary_path = evidence / (args.label + "-batch.json")
    if log_path.exists() or summary_path.exists():
        raise ValueError("Use a fresh evidence label; existing failed/superseded evidence is retained")
    results = []
    package = "com.neoproc.neoproccamx"
    failed = False

    def adb(*command: str, timeout: int = 45, checked: bool = True) -> str:
        process = subprocess.run([args.adb, "-s", args.serial, *command],
                                 capture_output=True, text=True, timeout=timeout)
        entry = {"time": datetime.now().isoformat(), "command": command,
                 "exit_code": process.returncode, "stdout": process.stdout, "stderr": process.stderr}
        with log_path.open("a", encoding="utf-8") as log:
            log.write(json.dumps(entry) + "\n")
        if checked and process.returncode:
            raise RuntimeError(f"ADB failed: {command}: {process.stderr}")
        return process.stdout.strip()

    def verify_installed() -> None:
        package_path = adb("shell", "pm", "path", package)
        if not package_path.startswith("package:") or "\n" in package_path:
            raise ValueError("Expected one installed candidate APK")
        installed_hash = adb("shell", "sha256sum", package_path.removeprefix("package:")).split()[0]
        if installed_hash.upper() != args.apk_sha256.upper():
            raise ValueError("Installed candidate SHA-256 mismatch")

    with ExclusiveAdbLease(None, "root-inline-" + args.label, args.serial):
        if adb("shell", "getprop", "ro.serialno") != args.physical_serial:
            raise ValueError("Physical device identity mismatch")
        try:
            adb("shell", "am", "force-stop", package)
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            if args.install_apk:
                with args.install_apk.open("rb") as source:
                    local_hash = hashlib.file_digest(source, "sha256").hexdigest()
                if local_hash.upper() != args.apk_sha256.upper():
                    raise ValueError("Local candidate SHA-256 mismatch")
                adb("install", "-r", "-t", str(args.install_apk), timeout=180)
            verify_installed()
            if args.native_residency_test:
                remote = "/data/local/tmp/camx-egl-residency-" + args.label
                adb("push", str(args.native_residency_test), remote)
                adb("shell", "chmod", "700", remote)
                output = adb("shell", remote, timeout=60)
                print(output, flush=True)
                # The tiny diagnostic executable is retained with the evidence identity; no media deletion.

            for index, (mode, pipeline, profile, resolution) in enumerate(cases, 1):
                verify_installed()
                thermal = adb("shell", "dumpsys", "thermalservice")
                match = re.search(r"Thermal Status:\s*([0-6])\b", thermal)
                current_hal = thermal.split("Current temperatures from HAL:", 1)[-1].split(
                    "Current cooling devices from HAL:", 1)[0] if "Current temperatures from HAL:" in thermal else ""
                hal_statuses = [int(value) for value in re.findall(r"mStatus=([0-6])\}", current_hal)]
                if match is None or max([int(match[1])] + hal_statuses) >= 5:
                    results.append({"case": cases[index - 1], "status": "BLOCKED",
                                    "reason": "Missing or emergency thermal admission"})
                    failed = True
                    break
                adb("shell", "dumpsys", "battery")
                adb("shell", "df", "-k", "/data")
                print(json.dumps({"event": "BATCH_CELL_START", "index": index,
                                  "total": len(cases), "case": cases[index - 1],
                                  "fps": 25, "seconds": 300, "thermal_before": int(match[1]),
                                  "apk_sha256": args.apk_sha256}), flush=True)
                existing_run_directories = {path for path in evidence.iterdir() if path.is_dir()}
                command = recording_command(args, cases[index - 1])
                process = subprocess.run(command, timeout=1800)
                result = {"event": "BATCH_CELL_END", "index": index, "case": cases[index - 1],
                          "exit_code": process.returncode,
                          "scope": "Recording-run gates only; independent file verification is separate"}
                run_directory = find_new_run(evidence, existing_run_directories, cases[index - 1])
                result["run_directory"] = str(run_directory)
                external_stop = read_run_external_stop(run_directory)
                if external_stop is not None:
                    # Android level 4 does not mean the independent OEM warning/kill policy
                    # has cleared. Never relaunch the next case after a confirmed OEM stop.
                    result["status"] = "FAIL" if external_stop["terminal_recording_failure"] else "BLOCKED"
                    result["external_stop"] = external_stop
                    result["remaining_cases_not_started"] = cases[index:]
                    results.append(result)
                    failed = True
                    print(json.dumps(result), flush=True)
                    summary_path.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
                    break
                if args.inspect_direct and pipeline == "DIRECT":
                    # The runner has finalized and closed the app. Reuse this lease; never
                    # spawn a second ADB owner or inspect media while the camera records.
                    if adb("shell", "pidof", package, checked=False):
                        raise RuntimeError("Camera app is still active before original-file audit")
                    run_directory = find_new_run(evidence, existing_run_directories, cases[index - 1])
                    result["run_directory"] = str(run_directory)
                    result["independent_audit"] = inspect_and_record(args, run_directory)
                    failed |= result["independent_audit"]["status"] != "PASS"
                results.append(result)
                failed |= process.returncode != 0
                print(json.dumps(result), flush=True)
                summary_path.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
        finally:
            summary_path.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
            try:
                adb("shell", "am", "force-stop", package, checked=False)
            finally:
                adb("shell", "input", "keyevent", "KEYCODE_SLEEP", checked=False)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
