"""Command-line entry point for NEO-QA HyperGrid."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import asdict
from pathlib import Path
from typing import Any, Sequence

from .change_impact import compute_impact
from .checkpoint_graph import compile_checkpoint_graph, validate_graph, write_graph_artifacts
from .device_orchestrator import DevicePreflight, evaluate_preflight
from .digital_twin import DigitalTwinGrid, TwinStatus, write_scenario_result_json
from .evidence_oracle import (
    RecordingThresholds,
    analyze_ffprobe,
    verify_recording_wire,
)
from .failure_injection import FailureInjectionEngine
from .inventory import discover_repository, write_inventory
from .package_oracle import verify_startup_packaging
from .quality_guard import QualitySnapshot, compare_quality
from .reporting import build_checkpoint_report, verify_sealed_evidence_record, write_checkpoint_report


def _atomic_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(payload, target, indent=2, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _compile_graph(arguments: argparse.Namespace) -> int:
    graph = compile_checkpoint_graph(arguments.ledger)
    validate_graph(graph)
    paths = write_graph_artifacts(graph, arguments.output)
    print(json.dumps({name: str(path) for name, path in paths.items()}, indent=2, sort_keys=True))
    return 0


def _inventory(arguments: argparse.Namespace) -> int:
    workspace = Path(arguments.workspace).resolve()
    output = Path(arguments.output).resolve()
    inventory = discover_repository(
        workspace,
        include_evidence=not arguments.no_evidence,
        exclude_paths=(output,),
    )
    write_inventory(inventory, output)
    print(f"tools={len(inventory.tools)} evidence={len(inventory.evidence)}")
    return 0


def _digital_twin(arguments: argparse.Namespace) -> int:
    result = DigitalTwinGrid(arguments.seed).run_nominal()
    digest = write_scenario_result_json(arguments.output, result)
    print(f"status={result.status.value} assertions={len(result.assertions)} sha256={digest}")
    return 0 if result.status == TwinStatus.PASS else 1


def _inject(arguments: argparse.Namespace) -> int:
    outcomes = FailureInjectionEngine(arguments.seed).run_all()
    payload = {
        "schema": "neo-qa-hypergrid/failure-injection/v1",
        "evidence_scope": "host source-contract evidence only; not physical-device proof",
        "status": "PASS" if all(item.status == TwinStatus.PASS for item in outcomes) else "FAIL",
        "outcomes": [item.to_dict() for item in outcomes],
    }
    _atomic_json(Path(arguments.output), payload)
    passed = sum(item.status == TwinStatus.PASS for item in outcomes)
    print(f"failure_injections={len(outcomes)} passed={passed}")
    return 0 if passed == len(outcomes) else 1


def _analyze_recording(arguments: argparse.Namespace) -> int:
    probe = json.loads(Path(arguments.probe).read_text(encoding="utf-8"))
    frames = (
        json.loads(Path(arguments.frames).read_text(encoding="utf-8"))
        if arguments.frames
        else None
    )
    metrics = analyze_ffprobe(probe, frames)
    thresholds = RecordingThresholds(
        width=arguments.width,
        height=arguments.height,
        minimum_bit_depth=arguments.minimum_bit_depth,
        target_fps=arguments.target_fps,
        fps_tolerance_ratio=arguments.fps_tolerance_ratio,
        require_p_frames=arguments.require_p_frames,
        require_audio=True if arguments.require_audio else False if arguments.forbid_audio else None,
        required_codec=arguments.codec,
        required_transfer=arguments.transfer,
        required_primaries=arguments.primaries,
        required_profile_contains=arguments.profile_contains,
        minimum_timestamped_frames=arguments.minimum_timestamped_frames,
        minimum_measured_bitrate_bps=arguments.minimum_measured_bitrate,
        maximum_measured_bitrate_bps=arguments.maximum_measured_bitrate,
    )
    failures = verify_recording_wire(metrics, thresholds)
    _atomic_json(
        Path(arguments.output),
        {
            "schema": "neo-qa-hypergrid/recording-wire-oracle/v1",
            "status": "PASS" if not failures else "FAIL",
            "metrics": asdict(metrics),
            "thresholds": asdict(thresholds),
            "failures": list(failures),
        },
    )
    print(f"status={'PASS' if not failures else 'FAIL'} failures={len(failures)}")
    return 0 if not failures else 1


def _impact(arguments: argparse.Namespace) -> int:
    report = compute_impact(Path(arguments.workspace), arguments.changed_file)
    payload = asdict(report)
    _atomic_json(Path(arguments.output), payload)
    print(
        f"changed={len(report.changed_files)} direct={len(report.directly_invalidated)} "
        f"transitive={len(report.transitively_invalidated)}"
    )
    return 0


def _quality(arguments: argparse.Namespace) -> int:
    baseline = QualitySnapshot.from_json(Path(arguments.baseline))
    candidate = QualitySnapshot.from_json(Path(arguments.candidate))
    verdict = compare_quality(
        baseline,
        candidate,
        prediction_change_reason=arguments.prediction_change_reason,
    )
    _atomic_json(Path(arguments.output), asdict(verdict))
    print(f"status={'PASS' if verdict.accepted else 'FAIL'} violations={len(verdict.violations)}")
    return 0 if verdict.accepted else 1


def _verify_apk_startup(arguments: argparse.Namespace) -> int:
    report = verify_startup_packaging(Path(arguments.app_apk), Path(arguments.test_apk))
    _atomic_json(Path(arguments.output), report.to_dict())
    print(
        f"status={report.status} app_sha256={report.app_sha256} "
        f"test_sha256={report.test_sha256} errors={len(report.errors)}"
    )
    return 0 if report.status == "PASS" else 1


def _preflight(arguments: argparse.Namespace) -> int:
    state = DevicePreflight(**json.loads(Path(arguments.input).read_text(encoding="utf-8")))
    decision = evaluate_preflight(state, test_class=arguments.test_class)
    _atomic_json(Path(arguments.output), asdict(decision))
    print(f"admitted={str(decision.admitted).lower()} reasons={len(decision.reasons)}")
    return 0 if decision.admitted else 2


def _validate_evidence(arguments: argparse.Namespace) -> int:
    valid, errors = verify_sealed_evidence_record(
        Path(arguments.record), Path(arguments.workspace).resolve()
    )
    payload = {"valid": valid, "errors": list(errors)}
    if arguments.output:
        _atomic_json(Path(arguments.output), payload)
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if valid else 1


def _report(arguments: argparse.Namespace) -> int:
    rows = build_checkpoint_report(
        Path(arguments.graph),
        workspace=Path(arguments.workspace).resolve(),
        evidence_records=tuple(Path(value) for value in arguments.evidence_record),
    )
    write_checkpoint_report(rows, Path(arguments.output))
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.status] = counts.get(row.status, 0) + 1
    print(json.dumps(counts, sort_keys=True))
    return 0


def _self_test(arguments: argparse.Namespace) -> int:
    command = (
        sys.executable,
        "-X",
        "dev",
        "-W",
        "error",
        "-m",
        "unittest",
        "discover",
        "-s",
        str(Path(arguments.tests).resolve()),
        "-p",
        "test_*.py",
        "-v",
    )
    return subprocess.run(command, check=False, shell=False).returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="neo-qa-hypergrid")
    subcommands = parser.add_subparsers(dest="command", required=True)

    graph = subcommands.add_parser("compile-graph")
    graph.add_argument("--ledger", required=True)
    graph.add_argument("--output", required=True)
    graph.set_defaults(handler=_compile_graph)

    inventory = subcommands.add_parser("inventory")
    inventory.add_argument("--workspace", default=".")
    inventory.add_argument("--output", required=True)
    inventory.add_argument("--no-evidence", action="store_true")
    inventory.set_defaults(handler=_inventory)

    twin = subcommands.add_parser("digital-twin")
    twin.add_argument("--seed", type=int, default=20260819)
    twin.add_argument("--output", required=True)
    twin.set_defaults(handler=_digital_twin)

    inject = subcommands.add_parser("failure-injection")
    inject.add_argument("--seed", type=int, default=20260819)
    inject.add_argument("--output", required=True)
    inject.set_defaults(handler=_inject)

    recording = subcommands.add_parser("analyze-recording")
    recording.add_argument("--probe", required=True)
    recording.add_argument("--frames")
    recording.add_argument("--output", required=True)
    recording.add_argument("--width", type=int, required=True)
    recording.add_argument("--height", type=int, required=True)
    recording.add_argument("--minimum-bit-depth", type=int, default=10)
    recording.add_argument("--target-fps", type=float, required=True)
    recording.add_argument("--fps-tolerance-ratio", type=float, default=0.05)
    recording.add_argument("--require-p-frames", action="store_true")
    audio = recording.add_mutually_exclusive_group()
    audio.add_argument("--require-audio", action="store_true")
    audio.add_argument("--forbid-audio", action="store_true")
    recording.add_argument("--codec", default="hevc")
    recording.add_argument("--transfer", default="")
    recording.add_argument("--primaries", default="")
    recording.add_argument("--profile-contains", default="")
    recording.add_argument("--minimum-timestamped-frames", type=int, default=8)
    recording.add_argument("--minimum-measured-bitrate", type=int, default=0)
    recording.add_argument("--maximum-measured-bitrate", type=int, default=0)
    recording.set_defaults(handler=_analyze_recording)

    impact = subcommands.add_parser("impact")
    impact.add_argument("--workspace", default=".")
    impact.add_argument("--changed-file", action="append", required=True)
    impact.add_argument("--output", required=True)
    impact.set_defaults(handler=_impact)

    quality = subcommands.add_parser("quality-guard")
    quality.add_argument("--baseline", required=True)
    quality.add_argument("--candidate", required=True)
    quality.add_argument("--prediction-change-reason", default="")
    quality.add_argument("--output", required=True)
    quality.set_defaults(handler=_quality)

    startup = subcommands.add_parser("verify-apk-startup")
    startup.add_argument("--app-apk", required=True)
    startup.add_argument("--test-apk", required=True)
    startup.add_argument("--output", required=True)
    startup.set_defaults(handler=_verify_apk_startup)

    preflight = subcommands.add_parser("device-preflight")
    preflight.add_argument("--input", required=True)
    preflight.add_argument("--test-class", choices=("functional", "performance"), required=True)
    preflight.add_argument("--output", required=True)
    preflight.set_defaults(handler=_preflight)

    evidence = subcommands.add_parser("validate-evidence")
    evidence.add_argument("--workspace", default=".")
    evidence.add_argument("--record", required=True)
    evidence.add_argument("--output")
    evidence.set_defaults(handler=_validate_evidence)

    report = subcommands.add_parser("report")
    report.add_argument("--workspace", default=".")
    report.add_argument("--graph", required=True)
    report.add_argument("--evidence-record", action="append", default=[])
    report.add_argument("--output", required=True)
    report.set_defaults(handler=_report)

    self_test = subcommands.add_parser("self-test")
    self_test.add_argument("--tests", default="tools/neo_qa_hypergrid/tests")
    self_test.set_defaults(handler=_self_test)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    return int(arguments.handler(arguments))


if __name__ == "__main__":
    raise SystemExit(main())
