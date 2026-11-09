"""Emit a reviewable ledger patch from completed physical runs; never full-checkpoint PASS."""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import difflib
import hashlib
import json
from pathlib import Path
import re

from tools.neo_qa_hypergrid.lq001_external_stop import read_run_external_stop

DIRECT_GATES = frozenset(("source_unchanged", "no_transport_error", "metadata_consistent",
                         "resolution", "ten_bit_hevc", "sdr_colour", "frame_count",
                         "exact_25fps_dts", "no_reordered_frames", "bitrate", "duration",
                         "audio", "terminal_av_difference", "source_cadence",
                         "source_zero_drops", "profile", "run_gates"))


def completed_failure_reason(run: dict, audit: dict | None) -> str:
    """Describe measured failed gates, never mistake the planned Stop action for a cause."""
    detail = run.get("recording_stop_detail")
    if run.get("recording_stop_origin") == "NPV10_WRITER_FAILURE":
        return detail or "NPV10 writer failure; see retained runtime evidence."
    reasons = []
    if run.get("recording_start_proven") is True and run.get("output_finalized") is False:
        reasons.append("Recording started but output did not finalize; no completed-file proof")
    if audit is not None:
        gates = audit.get("gates", {})
        failed = sorted(name for name in DIRECT_GATES | gates.keys()
                        if gates.get(name) is not True)
        reasons.append("Independent transport audit failed" +
                       (" gates: " + ", ".join(failed) if failed else "; see retained evidence"))
        for name in ("source_unrecovered_timestamp_gap_frames",
                     "source_explicit_dropped_recording_frames"):
            if name in run:
                reasons.append(f"{name}={run[name]}")
    if detail and not run.get("planned_stop_origin_valid", False):
        reasons.append(detail)
    return "; ".join(reasons) or "Independent transport audit failed; see retained evidence."


def update_latest_attempt_summary(cell: dict, run_directory: str) -> None:
    """Keep the current summary on this measured APK; the preceding state stays in history."""
    cell["latest_attempt"] = {
        "status": cell["status"],
        "transport_status": cell["transport_status"],
        "candidate_apk_sha256": cell["candidate_apk_sha256"],
        "label": cell["measured"].get("label") or Path(run_directory).name,
        "evidence": deepcopy(cell["evidence_paths"]),
        "reason": cell["blocker"],
        "scope": "Latest executed attempt; historical evidence keeps its APK identity.",
    }


def reconcile(ledger: dict, run: dict, identity: str, audit: dict | None,
              run_directory: str) -> dict:
    """Pure merge; caller reads retained evidence, with prior attempts kept intact."""
    candidate = re.search(r"^apkSha256=([A-Fa-f0-9]{64})\s", identity, re.M)
    serial = re.search(r"^serial=(\S+)", identity, re.M)
    if candidate is None or serial is None:
        raise ValueError("Missing physical candidate/device identity")
    candidate_hash = candidate[1].upper()
    if candidate_hash != ledger["current_app_sha256"].upper():
        raise ValueError("Previous-candidate evidence cannot become current-candidate PASS")
    if run.get("requested_duration_seconds") != 300 or run.get("fps") != 25:
        raise ValueError("Short diagnostics or different FPS do not replace a matrix run")
    if run.get("lifecycle_interruption_requested"):
        raise ValueError("Lifecycle injections remain separate regression evidence")
    mode, pipeline = run["mode"], run["pipeline"]
    if mode not in {"VIDEO", "PRO"} or pipeline not in {"DIRECT", "CINEMA"}:
        raise ValueError("RAW has a separate packet/audio oracle; no Direct inference")
    checkpoint = "LQ-001.05" if mode == "VIDEO" else "LQ-001.06"
    cell_id = "/".join((checkpoint, mode, pipeline, run["resolution"], run["requested_detail_profile"]))
    updated = deepcopy(ledger)
    matches = [cell for cell in updated["cells"] if cell["id"] == cell_id]
    if len(matches) != 1:
        raise ValueError("No unique canonical matrix cell")
    cell = matches[0]
    transport_pass = False
    if audit is not None:
        if (audit.get("candidate_apk_sha256", "").upper() != candidate_hash or
                audit.get("physical_serial") != serial[1]):
            raise ValueError("Independent audit identity differs from physical run")
        if Path(audit.get("evidence_directory", "")).parent.resolve() != Path(run_directory).resolve():
            raise ValueError("Independent audit belongs to a different recording")
        if pipeline != "DIRECT":
            raise ValueError("Direct MOV oracle cannot certify a Cinema master")
        gates = audit.get("gates", {})
        transport_pass = (audit.get("status") == "PASS" and DIRECT_GATES <= gates.keys()
                          and all(value is True for value in gates.values()))
        if audit.get("status") == "PASS" and not transport_pass:
            raise ValueError("Incomplete or contradictory PASS gates")
    publication_failed = (run.get("recording_start_proven") is True
                          and run.get("output_finalized") is False
                          and run.get("measured_wait_seconds", 0) >= 298)
    failed = ((audit is not None and not transport_pass)
              or run.get("recording_stop_origin") == "NPV10_WRITER_FAILURE"
              or publication_failed)
    if not transport_pass and not failed:
        raise ValueError("No final independent pass or explicit measured failure yet")
    expected_status = "PENDING" if transport_pass else "FAIL"
    expected_transport = "PASS" if transport_pass else "FAIL"
    # A repeated label is not enough: a stale/partially applied patch can leave the candidate,
    # status or oracle on another attempt. Validate the complete authoritative result first.
    if (cell.get("measured") == run
            and cell.get("candidate_apk_sha256") == candidate_hash
            and cell.get("device_serial") == serial[1]
            and cell.get("status") == expected_status
            and cell.get("transport_status") == expected_transport
            and cell.get("independent_transport_audit") == audit
            and cell.get("blocker") == (
                "Transport only: calibrated quality, physical A/V markers, continuous thermal proof and canonical endurance remain open."
                if transport_pass else completed_failure_reason(run, audit))
            and cell.get("latest_attempt", {}).get("status") == expected_status
            and cell.get("latest_attempt", {}).get("candidate_apk_sha256") == candidate_hash):
        return updated
    history = cell.setdefault("attempt_history", [])
    history.append({key: deepcopy(value) for key, value in cell.items() if key != "attempt_history"})
    # A successful later run supersedes this cell's current external-stop state, but the
    # exact interrupted attempt is already archived above. Never leave stale blockers
    # attached to a newly measured result or erase the history explaining a retry.
    cell.pop("external_stop", None)
    cell.update({"candidate_apk_sha256": candidate_hash, "device_serial": serial[1],
                 "status": "PENDING" if transport_pass else "FAIL",
                 "transport_status": "PASS" if transport_pass else "FAIL",
                 "actual_test": "Real SDK-ADB 300-second, 25-FPS run; exact candidate identity and retained runtime evidence.",
                 "measured": run,
                 "evidence_paths": [run_directory + "/run-result.json", run_directory + "/candidate-device-identity.txt"],
                 "blocker": ("Transport only: calibrated quality, physical A/V markers, continuous thermal proof and canonical endurance remain open."
                             if transport_pass else completed_failure_reason(run, audit))})
    if audit is not None:
        cell["independent_transport_audit"] = audit
        cell["evidence_paths"].append(audit["evidence_directory"].replace("\\", "/") + "/summary.json")
    else:
        cell.pop("independent_transport_audit", None)
    update_latest_attempt_summary(cell, run_directory)
    current_passes = sum(cell.get("transport_status") == "PASS" and
                         cell.get("candidate_apk_sha256", "").upper() == candidate_hash for cell in updated["cells"])
    updated["current_candidate_transport_pass_count"] = current_passes
    updated["latest_code_candidate"]["device_transport_passes"] = current_passes
    updated["execution_summary"]["current_candidate_transport_passes"] = current_passes
    updated["execution_summary"]["current_candidate_cells_without_transport_pass"] = len(updated["cells"]) - current_passes
    counts = Counter(cell["status"] for cell in updated["cells"])
    updated["status_counts"] = {key: counts[key] for key in ("PASS", "FAIL", "BLOCKED", "PENDING")}
    return updated


def reconcile_external_stop(ledger: dict, error: dict, identity: str, stop: dict,
                            run_directory: str) -> dict:
    """Retain interrupted real attempts without inventing a run-result or a PASS."""
    candidate = re.search(r"^apkSha256=([A-Fa-f0-9]{64})\s", identity, re.M)
    serial = re.search(r"^serial=(\S+)", identity, re.M)
    if candidate is None or serial is None or candidate[1].upper() != ledger["current_app_sha256"].upper():
        raise ValueError("External-stop evidence needs the current physical candidate identity")
    if error.get("requested_duration_seconds") != 300 or error.get("fps") != 25:
        raise ValueError("External-stop evidence is not the requested five-minute 25-FPS case")
    if (error.get("status") != "FAIL" or stop.get("status") != "BLOCKED"
            or stop.get("kind") not in {"OEM_FORCE_STOP", "OEM_THERMAL_FORCE_STOP"}
            or Path(stop.get("run_directory", "")).resolve() != Path(run_directory).resolve()):
        raise ValueError("Missing or mismatched external-stop evidence")
    mode = error["mode"]
    checkpoint = {"VIDEO": "LQ-001.05", "PRO": "LQ-001.06", "RAW": "LQ-001.07"}.get(mode)
    if checkpoint is None or error["pipeline"] not in {"DIRECT", "CINEMA"}:
        raise ValueError("Unknown recording route")
    cell_id = "/".join((checkpoint, mode, error["pipeline"], error["resolution"], error["requested_detail_profile"]))
    updated = deepcopy(ledger)
    matches = [cell for cell in updated["cells"] if cell["id"] == cell_id]
    if len(matches) != 1:
        raise ValueError("No unique canonical interrupted cell")
    cell = matches[0]
    if cell.get("external_stop", {}).get("run_directory") == stop["run_directory"]:
        return updated
    cell.setdefault("attempt_history", []).append(
        {key: deepcopy(value) for key, value in cell.items() if key != "attempt_history"})
    application_failure = error.get("terminal_recording_failure", "")
    cell.update({
        "candidate_apk_sha256": candidate[1].upper(),
        "device_serial": serial[1],
        "status": "FAIL" if application_failure else "BLOCKED",
        "transport_status": "FAIL" if application_failure else "BLOCKED",
        "actual_test": "Real SDK-ADB five-minute 25-FPS attempt interrupted by a correlated OEM force-stop; no completed recording claimed.",
        "measured": deepcopy(error),
        "external_stop": deepcopy(stop),
        "evidence_paths": list(stop["evidence_paths"]) + [run_directory + "/candidate-device-identity.txt"],
        "blocker": (("Application failure also remains: " + application_failure + "; ") if application_failure else "") + stop["reason"],
    })
    cell.pop("independent_transport_audit", None)
    update_latest_attempt_summary(cell, run_directory)
    passes = sum(cell.get("transport_status") == "PASS" and
                 cell.get("candidate_apk_sha256", "").upper() == candidate[1].upper() for cell in updated["cells"])
    updated["current_candidate_transport_pass_count"] = passes
    updated["latest_code_candidate"]["device_transport_passes"] = passes
    updated["execution_summary"]["current_candidate_transport_passes"] = passes
    updated["execution_summary"]["current_candidate_cells_without_transport_pass"] = len(updated["cells"]) - passes
    counts = Counter(cell["status"] for cell in updated["cells"])
    updated["status_counts"] = {key: counts[key] for key in ("PASS", "FAIL", "BLOCKED", "PENDING")}
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--run", type=Path, action="append", required=True)
    parser.add_argument("--with-postimage-sha256", action="store_true",
                        help="Emit the expected UTF-8 postimage hash for verified patch application")
    args = parser.parse_args()
    before = args.ledger.read_text(encoding="utf-8-sig")
    ledger = json.loads(before)
    for directory in args.run:
        identity = (directory / "candidate-device-identity.txt").read_text(encoding="utf-8-sig")
        external_stop = read_run_external_stop(directory)
        if external_stop is not None:
            error = json.loads((directory / "run-error.json").read_text(encoding="utf-8-sig"))
            ledger = reconcile_external_stop(ledger, error, identity, external_stop, directory.as_posix())
            continue
        run = json.loads((directory / "run-result.json").read_text(encoding="utf-8-sig"))
        audits = sorted(directory.glob("independent-*/summary.json"))
        audit = json.loads(audits[-1].read_text(encoding="utf-8-sig")) if audits else None
        ledger = reconcile(ledger, run, identity, audit, directory.as_posix())
    after = json.dumps(ledger, indent=2, ensure_ascii=False) + "\n"
    if json.loads(before) == ledger:
        return 0
    # The ledger contains repeated nested histories. Retain enough surrounding identity to avoid
    # applying a small, ambiguous status hunk to another attempt; callers can verify the full hash.
    difference = list(difflib.unified_diff(before.splitlines(), after.splitlines(), n=80))[2:]
    print("*** Begin Patch\n*** Update File: " + args.ledger.as_posix())
    for line in difference:
        print("@@" if line.startswith("@@") else line)
    print("*** End Patch")
    if args.with_postimage_sha256:
        print("POSTIMAGE_SHA256=" + hashlib.sha256(after.encode("utf-8")).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
