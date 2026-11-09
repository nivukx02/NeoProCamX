"""Classify retained OEM process exits; never infer camera success from an interruption.

The generic Android thermal level is not the whole vendor safety policy. On the
physical iQOO, the OEM can show a warning and force-stop a foreground camera at
framework level 4. Correlate the *current run's* process start, kill and persisted
exit record before stopping the batch. Historical exits alone are insufficient.
"""
from __future__ import annotations

from datetime import datetime, timedelta
import json
from pathlib import Path
import re


PACKAGE = "com.neoproc.neoproccamx"
OEM_STOPPER = "com.vivo.daemonService"
_TIME = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s")
_EXIT = re.compile(
    r"timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) pid=(\d+)\b"
    r"[^\n]*\n([^\n]*)\n([^\n]*)"
)
_KILL = re.compile(
    r"\bam_kill\s*:\s*\[\d+,(\d+)," + re.escape(PACKAGE)
    + r",[^\n]*\bstop " + re.escape(PACKAGE) + r" due to stop by "
    + re.escape(OEM_STOPPER) + r"(?:,|\])"
)
_START = re.compile(r"\bam_proc_start\s*:\s*\[\d+,(\d+),\d+," + re.escape(PACKAGE) + r",")


def _timestamp(line: str, reference: datetime) -> datetime | None:
    match = _TIME.match(line)
    if match is None:
        return None
    # Select the adjacent year too: a run may straddle midnight on New Year's Eve.
    candidates = []
    for year in (reference.year - 1, reference.year, reference.year + 1):
        try:
            candidates.append(datetime.strptime(f"{year}-{match[1]}", "%Y-%m-%d %H:%M:%S.%f"))
        except ValueError:
            continue
    return min(candidates, key=lambda value: abs(value - reference)) if candidates else None


def classify_external_stop(events: str, exit_info: str) -> dict | None:
    """Return a supported external-blocker classification, or no inference.

    A matched OEM force-stop is not relabelled as an app crash. A thermal cause
    additionally requires both independent OEM thermal broadcasts immediately
    before that kill. Other failures and old exit-info entries remain untouched.
    """
    lines = events.splitlines()
    for entry in _EXIT.finditer(exit_info):
        exit_time = datetime.strptime(entry[1], "%Y-%m-%d %H:%M:%S.%f")
        pid = entry[2]
        process_line, description = entry[3], entry[4]
        if not all((
            f"process={PACKAGE} " in process_line,
            "reason=10 (USER REQUESTED)" in process_line,
            "subreason=21 (FORCE STOP)" in process_line,
            f"description=stop {PACKAGE} due to stop by {OEM_STOPPER} " in description,
        )):
            continue
        for kill_line in lines:
            killed = _KILL.search(kill_line)
            kill_time = _timestamp(kill_line, exit_time)
            if killed is None or killed[1] != pid or kill_time is None:
                continue
            if abs(kill_time - exit_time) > timedelta(seconds=2):
                continue
            start_line = next((line for line in lines
                               if (started := _START.search(line)) is not None
                               and started[1] == pid
                               and (started_at := _timestamp(line, exit_time)) is not None
                               and timedelta(0) <= kill_time - started_at <= timedelta(minutes=30)), None)
            if start_line is None:
                continue
            preceding = [line for line in lines
                         if (when := _timestamp(line, exit_time)) is not None
                         and timedelta(0) <= kill_time - when <= timedelta(seconds=30)]
            high_warning = next((line for line in preceding
                                 if "broadcast com.iqoo.powersaving.temperature.high from system " in line
                                 and ":com.vivo.pem/1000 pkg com.vivo.pem " in line), None)
            warning_window = next((line for line in preceding
                                   if "broadcast android.thermal.WARNING_WINDOW_STATE from system " in line
                                   and f":{OEM_STOPPER}/1000 pkg {OEM_STOPPER} " in line), None)
            thermal = high_warning is not None and warning_window is not None
            return {
                "kind": "OEM_THERMAL_FORCE_STOP" if thermal else "OEM_FORCE_STOP",
                "status": "BLOCKED",
                "package": PACKAGE,
                "pid": int(pid),
                "exit_timestamp": entry[1],
                "stopper": OEM_STOPPER,
                "thermal_warning_correlated": thermal,
                "start_event": start_line,
                "kill_event": kill_line,
                "exit_record": entry[0],
                "thermal_events": [line for line in (high_warning, warning_window) if line],
                "reason": ("OEM overheating protection force-stopped the foreground camera; "
                           "cool the device before another recording. No safety override is permitted."
                           if thermal else "The OEM service force-stopped the current camera process; "
                           "resolve its external stop before another recording. Thermal cause is not proven."),
                "production_ready": False,
            }
    return None


def read_run_external_stop(run_directory: Path) -> dict | None:
    """Only the failed run's retained evidence may interrupt its batch."""
    error_path = run_directory / "run-error.json"
    events_path = run_directory / "system-events-logcat.txt"
    exit_path = run_directory / "exit-info-postmortem-before-cleanup.txt"
    if not all(path.is_file() for path in (error_path, events_path, exit_path)):
        return None
    error = json.loads(error_path.read_text(encoding="utf-8-sig"))
    if error.get("schema") != "neo-qa-hypergrid/lq001-run-error/v1" or error.get("status") != "FAIL":
        return None
    result = classify_external_stop(events_path.read_text(encoding="utf-8-sig"),
                                    exit_path.read_text(encoding="utf-8-sig"))
    if result is not None:
        result["run_directory"] = run_directory.as_posix()
        result["evidence_paths"] = [path.as_posix() for path in (error_path, events_path, exit_path)]
        # Never hide a simultaneous application recording failure behind an OEM blocker.
        result["terminal_recording_failure"] = error.get("terminal_recording_failure", "")
    return result
