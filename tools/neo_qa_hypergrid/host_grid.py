"""Conflict-aware parallel host test execution with isolated caches and evidence logs."""

from __future__ import annotations

import hashlib
import math
import os
import signal
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


@dataclass(frozen=True)
class HostLane:
    lane_id: str
    command: tuple[str, ...]
    cwd: Path
    artifact_directory: Path
    output_roots: tuple[Path, ...] = ()
    cache_directory: Path | None = None
    timeout_seconds: float = 1800
    read_only: bool = False
    kind: str = "generic"
    environment: Mapping[str, str] | None = None


@dataclass(frozen=True)
class HostLaneResult:
    lane_id: str
    exit_code: int
    duration_seconds: float
    stdout_path: str
    stderr_path: str
    stdout_sha256: str
    stderr_sha256: str
    timed_out: bool


def _canonical(path: Path) -> str:
    return os.path.normcase(str(path.resolve()))


def _paths_overlap(left: Path, right: Path) -> bool:
    left_text = _canonical(left)
    right_text = _canonical(right)
    try:
        return os.path.commonpath([left_text, right_text]) in {left_text, right_text}
    except ValueError:
        return False


def _is_gradle_lane(lane: HostLane) -> bool:
    if "gradle" in lane.kind.lower():
        return True
    if not lane.command:
        return False
    executable = Path(lane.command[0]).name.lower()
    return executable in {"gradle", "gradle.bat", "gradlew", "gradlew.bat"}


def lanes_conflict(left: HostLane, right: HostLane) -> bool:
    # Every lane writes stdout/stderr evidence even if its tested workload is read-only.
    if _paths_overlap(left.artifact_directory, right.artifact_directory):
        return True
    if left.cache_directory and right.cache_directory:
        if _canonical(left.cache_directory) == _canonical(right.cache_directory):
            return True
    if _is_gradle_lane(left) and _is_gradle_lane(right):
        # A missing cache means both processes may use the host default. Gradle also writes the
        # project-local .gradle/build trees, so lanes sharing a checkout remain sequential even
        # when their GRADLE_USER_HOME directories differ.
        if left.cache_directory is None or right.cache_directory is None:
            return True
        if _paths_overlap(left.cwd, right.cwd):
            return True
    if left.read_only and right.read_only:
        return False
    return any(
        _paths_overlap(left_root, right_root)
        for left_root in left.output_roots
        for right_root in right.output_roots
    )


def plan_host_waves(lanes: Iterable[HostLane]) -> tuple[tuple[HostLane, ...], ...]:
    ordered = sorted(lanes, key=lambda lane: lane.lane_id)
    if len({lane.lane_id for lane in ordered}) != len(ordered):
        raise ValueError("host lane IDs must be unique")
    for lane in ordered:
        if not lane.lane_id.strip():
            raise ValueError("host lane IDs cannot be empty")
        if not lane.command or any(not str(argument) for argument in lane.command):
            raise ValueError(f"host lane {lane.lane_id} has an empty command")
        if not lane.cwd.is_dir():
            raise ValueError(f"host lane {lane.lane_id} working directory does not exist")
        if not math.isfinite(lane.timeout_seconds) or lane.timeout_seconds <= 0:
            raise ValueError(f"host lane {lane.lane_id} timeout must be finite and positive")
        if _is_gradle_lane(lane) and lane.cache_directory is None:
            raise ValueError(f"Gradle lane {lane.lane_id} requires an isolated cache_directory")
    waves: list[list[HostLane]] = []
    for lane in ordered:
        for wave in waves:
            if all(not lanes_conflict(lane, peer) for peer in wave):
                wave.append(lane)
                break
        else:
            waves.append([lane])
    return tuple(tuple(wave) for wave in waves)


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def _exclusive_binary_writer(path: Path):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_BINARY"):
        flags |= os.O_BINARY
    descriptor = os.open(path, flags, 0o600)
    return os.fdopen(descriptor, "wb")


def _run_lane(lane: HostLane) -> HostLaneResult:
    lane.artifact_directory.mkdir(parents=True, exist_ok=True)
    stdout_path = lane.artifact_directory / "stdout.log"
    stderr_path = lane.artifact_directory / "stderr.log"
    environment = os.environ.copy()
    environment.update(dict(lane.environment or {}))
    if lane.cache_directory is not None:
        lane.cache_directory.mkdir(parents=True, exist_ok=True)
        environment["GRADLE_USER_HOME"] = str(lane.cache_directory.resolve())
    start = time.monotonic()
    timed_out = False
    exit_code = -1
    # Never overwrite a previous/superseded lane transcript. If reserving the second path fails,
    # remove the first empty reservation so the failed admission leaves no partial run evidence.
    stdout = _exclusive_binary_writer(stdout_path)
    try:
        stderr = _exclusive_binary_writer(stderr_path)
    except BaseException:
        stdout.close()
        stdout_path.unlink(missing_ok=True)
        raise
    with stdout, stderr:
        try:
            creation_flags = (
                subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            )
            process = subprocess.Popen(
                lane.command,
                cwd=lane.cwd,
                env=environment,
                stdout=stdout,
                stderr=stderr,
                shell=False,
                creationflags=creation_flags,
                start_new_session=os.name != "nt",
            )
            try:
                exit_code = process.wait(timeout=lane.timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                exit_code = 124
                if os.name == "nt":
                    try:
                        subprocess.run(
                            ("taskkill", "/PID", str(process.pid), "/T", "/F"),
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            check=False,
                            shell=False,
                            timeout=10,
                        )
                    except (OSError, subprocess.TimeoutExpired):
                        process.kill()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
        except OSError as exc:
            stderr.write(f"failed to launch lane {lane.lane_id}: {exc}\n".encode("utf-8"))
            exit_code = 127
    duration = time.monotonic() - start
    return HostLaneResult(
        lane_id=lane.lane_id,
        exit_code=exit_code,
        duration_seconds=duration,
        stdout_path=str(stdout_path),
        stderr_path=str(stderr_path),
        stdout_sha256=_digest(stdout_path),
        stderr_sha256=_digest(stderr_path),
        timed_out=timed_out,
    )


def execute_host_grid(
    lanes: Iterable[HostLane],
    *,
    allow_execution: bool = False,
    maximum_workers: int = 4,
) -> tuple[HostLaneResult, ...]:
    if not allow_execution:
        raise PermissionError("host command execution requires allow_execution=True")
    if maximum_workers <= 0:
        raise ValueError("maximum_workers must be positive")
    results: list[HostLaneResult] = []
    for wave in plan_host_waves(lanes):
        with ThreadPoolExecutor(max_workers=min(maximum_workers, len(wave))) as executor:
            futures = [executor.submit(_run_lane, lane) for lane in wave]
            results.extend(future.result() for future in futures)
        if any(result.exit_code != 0 for result in results[-len(wave) :]):
            break
    return tuple(results)
