"""One exclusive physical recording/CPU or scheduler diagnostic; no inferred matrix PASS."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess

from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease
from tools.neo_qa_hypergrid.run_lq001_recording_batch import parse_case


PACKAGE = "com.neoproc.neoproccamx"
DEFAULT_CASE = "VIDEO/CINEMA/NATURAL_DETAIL/3264x2448"


def streaming_trace_config(profile_seconds: int) -> str:
    """Use the existing bounded file-streaming trace instead of losing an early ring window."""
    if isinstance(profile_seconds, bool) or not isinstance(profile_seconds, int) or not 1 <= profile_seconds <= 295:
        raise ValueError("Streaming trace duration must be an explicit 1-295 seconds")
    template = Path(__file__).with_name("lq001_cinema_bounded_trace.pbtxt").read_text(encoding="utf-8")
    configured, replacements = re.subn(r"(?m)^duration_ms:\s*\d+\s*$",
                                       f"duration_ms: {profile_seconds * 1000}", template)
    if replacements != 1 or "write_into_file: true" not in configured:
        raise ValueError("Bounded streaming trace template has no unique duration/file writer")
    return configured


def diagnostic_case(value: str) -> tuple[str, str, str, str]:
    case = parse_case(value)
    if case[0] == "RAW":
        raise ValueError("RAW requires its own packet-recording diagnostic; never substitute a processed route")
    return case


def validate_request(adb: Path, apk_sha256: str, kind: str,
                     record_seconds: int, profile_seconds: int) -> None:
    if adb.name.lower() != "adb.exe" or adb.parent.name != "platform-tools":
        raise ValueError("Use the SDK platform-tools ADB executable")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", apk_sha256):
        raise ValueError("A candidate SHA-256 is required")
    if kind not in {"simpleperf", "perfetto"}:
        raise ValueError("Unknown physical profiling backend")
    if not 60 <= record_seconds <= 300 or not 15 <= profile_seconds <= record_seconds - 5:
        raise ValueError("Profile must fit within the declared 60–300-second recording")


def thermal_admitted(thermal: str) -> bool:
    status = re.search(r"Thermal Status:\s*([0-6])\b", thermal)
    if status is None or "Current temperatures from HAL:" not in thermal:
        return False
    current_hal = thermal.split("Current temperatures from HAL:", 1)[1].split(
        "Current cooling devices from HAL:", 1)[0]
    levels = [int(value) for value in re.findall(r"mStatus=([0-6])\}", current_hal)]
    return bool(levels) and max([int(status[1])] + levels) < 5


def recording_command(adb: Path, serial: str, evidence_root: Path,
                      record_seconds: int, case: str = DEFAULT_CASE) -> list[str]:
    mode, pipeline, profile, resolution = diagnostic_case(case)
    aspect = "16:9" if resolution == "3840x2160" else "4:3"
    return ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            "tools/neo_qa_hypergrid/run_lq001_endurance.ps1", "-Mode", mode,
            "-Pipeline", pipeline, "-DetailProfile", profile, "-Resolution", resolution,
            "-Aspect", aspect, "-Fps", "25", "-DurationSeconds", str(record_seconds),
            "-Serial", serial, "-AdbPath", str(adb), "-EvidenceRoot", str(evidence_root)]


def profile_command(adb: Path, serial: str, kind: str, remote: str,
                    profile_seconds: int, config_path: str | None = None) -> list[str]:
    base = [str(adb), "-s", serial, "shell"]
    if config_path is not None and kind != "perfetto":
        raise ValueError("Perfetto config cannot be substituted into a CPU sampling command")
    if kind == "simpleperf":
        return base + ["simpleperf", "record", "--app", PACKAGE, "-o", remote,
                       "-e", "cpu-clock", "-f", "1000", "-g", "--duration", str(profile_seconds)]
    if kind == "perfetto":
        if config_path is not None:
            if not re.fullmatch(r"/data/(?:local/tmp|misc/perfetto-configs)/lq001-[A-Za-z0-9_.-]+\.pbtxt", config_path):
                raise ValueError("Use an exact run-owned remote Perfetto config path")
            # Duration belongs inside this copied config. Android rejects -t with --config.
            return base + ["perfetto", "--txt", "-c", config_path, "-o", remote]
        # Scheduler and app Trace slices locate waits; CPU percentages cannot do that.
        # No root, permission changes, thermal overrides or kernel restrictions changed.
        return base + ["perfetto", "-o", remote, "-t", f"{profile_seconds}s", "-b", "64mb",
                       "--app", PACKAGE, "sched", "freq", "idle", "am", "wm", "gfx",
                       "view", "binder_driver", "hal", "dalvik"]
    raise ValueError("Unknown physical profiling backend")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--kind", choices=("simpleperf", "perfetto"), default="simpleperf")
    parser.add_argument("--record-seconds", type=int, default=60)
    parser.add_argument("--profile-seconds", type=int, default=45)
    parser.add_argument("--streaming-perfetto", action="store_true",
                        help="Stream the bounded project trace to disk so long diagnostics retain earlier events")
    parser.add_argument("--case", default=DEFAULT_CASE,
                        help="Exact Video/Pro recording case; defaults preserve the existing Cinema diagnostic")
    args = parser.parse_args()
    validate_request(args.adb, args.apk_sha256, args.kind, args.record_seconds, args.profile_seconds)
    if args.streaming_perfetto and args.kind != "perfetto":
        raise ValueError("Streaming trace is available only for Perfetto")
    selected_case = diagnostic_case(args.case)
    args.evidence_root.mkdir(parents=True, exist_ok=False)
    package = PACKAGE
    remote_root = "/data/local/tmp/" if args.kind == "simpleperf" else "/data/misc/perfetto-traces/"
    extension = ".perf.data" if args.kind == "simpleperf" else ".pftrace"
    remote = remote_root + "lq001-cinema-" + datetime.now().strftime("%Y%m%d-%H%M%S") + extension
    command_log = args.evidence_root / "adb.jsonl"
    profile = None
    recording = None
    profile_log = None
    remote_config = None
    local_config = None

    def adb(*command: str, checked: bool = True, timeout: int = 45) -> str:
        result = subprocess.run([str(args.adb), "-s", args.serial, *command],
                                capture_output=True, text=True, timeout=timeout)
        with command_log.open("a", encoding="utf-8") as output:
            output.write(json.dumps({"time": datetime.now().isoformat(), "command": command,
                                     "exit_code": result.returncode, "stdout": result.stdout,
                                     "stderr": result.stderr}) + "\n")
        if checked and result.returncode:
            raise RuntimeError(f"ADB diagnostic command failed: {command}: {result.stderr}")
        return result.stdout.strip()

    with ExclusiveAdbLease(None, "root-inline-cinema-" + args.kind + "-diagnostic", args.serial):
        try:
            if adb("shell", "getprop", "ro.serialno") != args.serial:
                raise ValueError("Wrong physical device")
            path = adb("shell", "pm", "path", package)
            if not path.startswith("package:") or "\n" in path:
                raise ValueError("Expected one APK")
            if adb("shell", "sha256sum", path.removeprefix("package:")).split()[0].upper() != args.apk_sha256.upper():
                raise ValueError("Installed candidate differs")
            thermal = adb("shell", "dumpsys", "thermalservice")
            if not thermal_admitted(thermal):
                raise RuntimeError("Emergency or unavailable thermal admission")
            flags = adb("shell", "dumpsys", "package", package)
            if "DEBUGGABLE" not in flags:
                raise RuntimeError("CPU profiling requires the debug candidate")
            adb("shell", args.kind, "--version")
            if args.kind == "perfetto":
                # Check the actual device CLI/config and permissions before consuming a long
                # camera run. A successful --version is not proof that capture is supported.
                probe_remote = remote + ".preflight"
                probe_config = None
                if args.streaming_perfetto:
                    # Android's traced/perfetto SELinux domain cannot read shell_data_file on
                    # this device. Use its documented config directory, not chmod/root overrides.
                    remote_config = "/data/misc/perfetto-configs/" + Path(remote).name + ".pbtxt"
                    probe_config = remote_config.removesuffix(".pbtxt") + ".preflight.pbtxt"
                    local_config = args.evidence_root / "perfetto-streaming.pbtxt"
                    local_probe = args.evidence_root / "perfetto-preflight.pbtxt"
                    local_config.write_text(streaming_trace_config(args.profile_seconds), encoding="utf-8")
                    local_probe.write_text(streaming_trace_config(1), encoding="utf-8")
                    adb("push", str(local_config), remote_config)
                    adb("push", str(local_probe), probe_config)
                probe_command = profile_command(args.adb, args.serial, args.kind, probe_remote, 1,
                                                config_path=probe_config)
                adb(*probe_command[3:], timeout=30)
                if int(adb("shell", "stat", "-c", "%s", probe_remote)) <= 0:
                    raise RuntimeError("Perfetto preflight produced no trace")
            # Select the exact failing route before recording; no profile/geometry fallback.
            command = recording_command(args.adb, args.serial, args.evidence_root,
                                        args.record_seconds, args.case)
            recording = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         text=True, encoding="utf-8", errors="replace")
            with (args.evidence_root / "recording-diagnostic.txt").open("w", encoding="utf-8") as output:
                for line in recording.stdout:
                    output.write(line)
                    output.flush()
                    print(line, end="", flush=True)
                    if "RECORDING_CONFIRMED" in line and profile is None:
                        capture_command = profile_command(
                            args.adb, args.serial, args.kind, remote, args.profile_seconds,
                            config_path=remote_config)
                        with command_log.open("a", encoding="utf-8") as log:
                            log.write(json.dumps({"event": "PROFILE_START", "command": capture_command,
                                                  "time": datetime.now().isoformat()}) + "\n")
                        profile_log = (args.evidence_root / (args.kind + "-record.txt")).open("w", encoding="utf-8")
                        profile = subprocess.Popen(capture_command, stdout=profile_log, stderr=subprocess.STDOUT)
                recording.wait(timeout=60)
            if profile is None:
                raise RuntimeError("No real recording start; profiling was not fabricated")
            profile.wait(timeout=args.profile_seconds + 30)
            profile_log.close()
            if profile.returncode:
                raise RuntimeError("Profiling failed; see the capture log")
            local_trace = args.evidence_root / ("perf.data" if args.kind == "simpleperf" else "cinema.pftrace")
            adb("pull", remote, str(local_trace), timeout=120)
            if not local_trace.is_file() or local_trace.stat().st_size == 0:
                raise RuntimeError("No captured trace; no profiling evidence")
            if args.kind == "simpleperf":
                report = adb("shell", "simpleperf", "report", "-i", remote, "--sort", "comm,dso,symbol", timeout=90)
                (args.evidence_root / "simpleperf-self.txt").write_text(report + "\n", encoding="utf-8")
            summary = {"scope": "Physical recording diagnostic; no inferred matrix or production PASS",
                       "requested_case": selected_case,
                       "candidate_apk_sha256": args.apk_sha256, "device_serial": args.serial,
                       "recording_runner_exit_code": recording.returncode, "profile_exit_code": profile.returncode,
                       "requested_recording_seconds": args.record_seconds, "requested_profile_seconds": args.profile_seconds,
                       "trace_kind": args.kind, "trace_path": str(local_trace),
                       "trace_config_path": str(local_config) if local_config else None,
                       "trace_streaming_requested": args.streaming_perfetto,
                       "profile_frequency_hz": 1000 if args.kind == "simpleperf" else None,
                       "profiling_can_perturb_performance": True,
                       "production_ready": False}
            (args.evidence_root / "diagnostic-result.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(summary), flush=True)
            return int(recording.returncode != 0)
        finally:
            if recording is not None and recording.poll() is None:
                recording.terminate()
                recording.wait(timeout=30)
            if profile is not None and profile.poll() is None:
                profile.terminate()
                profile.wait(timeout=30)
            if profile_log is not None and not profile_log.closed:
                profile_log.close()
            adb("shell", "am", "force-stop", package, checked=False)
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP", checked=False)


if __name__ == "__main__":
    raise SystemExit(main())
