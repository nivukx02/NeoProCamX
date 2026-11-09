"""Exclusive, thermal-aware physical-device orchestration primitives.

This module plans and admits ADB work but intentionally does not execute ADB itself. The
caller must hold :class:`ExclusiveAdbLease` for the complete device session and must save
every command/result in its run-specific evidence directory.
"""

from __future__ import annotations

import json
import math
import os
import re
import socket
import time
import tempfile
import uuid
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


GLOBAL_ADB_LOCK_PATH = (
    Path(tempfile.gettempdir()).resolve() / "neo-qa-hypergrid-exclusive-adb-v1.lock"
)
_SHA256 = re.compile(r"^[0-9A-Fa-f]{64}$")


@dataclass(frozen=True)
class AdbLeaseOwner:
    token: str
    process_id: int
    host: str
    controller: str
    serial: str
    acquired_unix_ms: int


class ExclusiveAdbLease:
    """Cross-process one-owner lease backed by atomic create, never silent takeover."""

    def __init__(
        self,
        lock_path: Path | None,
        controller: str,
        serial: str,
        *,
        _allow_test_lock_path: bool = False,
    ):
        selected_lock = (lock_path or GLOBAL_ADB_LOCK_PATH).resolve()
        if not _allow_test_lock_path and (
            os.path.normcase(str(selected_lock))
            != os.path.normcase(str(GLOBAL_ADB_LOCK_PATH))
        ):
            raise ValueError(
                "production ADB controllers must use the canonical global lock path"
            )
        if not controller.strip() or not serial.strip():
            raise ValueError("ADB lease controller and serial are required")
        self.lock_path = selected_lock
        self.owner = AdbLeaseOwner(
            token=uuid.uuid4().hex,
            process_id=os.getpid(),
            host=socket.gethostname(),
            controller=controller,
            serial=serial,
            acquired_unix_ms=int(time.time() * 1000),
        )
        self._held = False

    @classmethod
    def for_testing(
        cls,
        lock_path: Path,
        controller: str,
        serial: str,
    ) -> "ExclusiveAdbLease":
        """Create an isolated unit-test lease without weakening production lock identity."""

        return cls(
            lock_path,
            controller,
            serial,
            _allow_test_lock_path=True,
        )

    def acquire(self) -> "ExclusiveAdbLease":
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(asdict(self.owner), indent=2, sort_keys=True) + "\n"
        try:
            descriptor = os.open(
                self.lock_path,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
            )
        except FileExistsError as exc:
            try:
                existing: AdbLeaseOwner | str | None = self.inspect_owner(self.lock_path)
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                existing = "UNREADABLE_EXISTING_LOCK"
            raise RuntimeError(f"ADB is already owned: {existing}") from exc
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as target:
                target.write(payload)
                target.flush()
                os.fsync(target.fileno())
        except BaseException:
            self.lock_path.unlink(missing_ok=True)
            raise
        self._held = True
        return self

    def release(self) -> None:
        if not self._held:
            return
        current = self.inspect_owner(self.lock_path)
        if current is None or current.token != self.owner.token:
            raise RuntimeError("refusing to release an ADB lease owned by another controller")
        self.lock_path.unlink()
        self._held = False

    def __enter__(self) -> "ExclusiveAdbLease":
        return self.acquire()

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.release()

    @staticmethod
    def inspect_owner(lock_path: Path) -> AdbLeaseOwner | None:
        if not lock_path.is_file():
            return None
        payload = json.loads(lock_path.read_text(encoding="utf-8"))
        return AdbLeaseOwner(**payload)


@dataclass(frozen=True)
class DevicePreflight:
    serial: str
    expected_serial: str
    manufacturer: str
    model: str
    android_version: str
    api_level: int
    build_fingerprint: str
    abi: str
    boot_completed: bool
    interactive_unlocked: bool
    battery_percent: int
    charging: bool
    free_storage_bytes: int
    thermal_status: int
    skin_temperature_c: float
    active_camera_clients: tuple[str, ...]
    conflicting_background_processes: tuple[str, ...]
    candidate_apk_sha256: str
    installed_apk_sha256: str
    signing_certificate_sha256: str


@dataclass(frozen=True)
class ThermalAdmissionPolicy:
    minimum_battery_percent: int = 30
    minimum_free_storage_bytes: int = 10 * 1024**3
    maximum_functional_thermal_status: int = 2
    maximum_functional_skin_c: float = 42.0
    maximum_performance_thermal_status: int = 1
    maximum_performance_skin_c: float = 40.0


@dataclass(frozen=True)
class AdmissionDecision:
    admitted: bool
    test_class: str
    reasons: tuple[str, ...]


def evaluate_preflight(
    state: DevicePreflight,
    *,
    test_class: str,
    policy: ThermalAdmissionPolicy = ThermalAdmissionPolicy(),
) -> AdmissionDecision:
    if test_class not in {"functional", "performance"}:
        raise ValueError("test_class must be functional or performance")
    reasons: list[str] = []
    if not state.serial.strip() or state.serial.strip() != state.expected_serial.strip():
        reasons.append("device serial does not match the admitted target")
    identity_fields = {
        "manufacturer": state.manufacturer,
        "model": state.model,
        "android version": state.android_version,
        "build fingerprint": state.build_fingerprint,
        "ABI": state.abi,
    }
    for name, value in identity_fields.items():
        if not value.strip():
            reasons.append(f"device {name} identity is missing")
    if isinstance(state.api_level, bool) or not isinstance(state.api_level, int) or state.api_level <= 0:
        reasons.append("device API level is invalid")
    if not state.boot_completed:
        reasons.append("Android boot is incomplete")
    if not state.interactive_unlocked:
        reasons.append("device is not interactive and unlocked")
    if not 0 <= state.battery_percent <= 100:
        reasons.append("battery percentage is outside 0..100")
    elif state.battery_percent < policy.minimum_battery_percent and not state.charging:
        reasons.append("battery is below the non-charging admission floor")
    if state.free_storage_bytes < 0:
        reasons.append("free storage value is invalid")
    if state.free_storage_bytes < policy.minimum_free_storage_bytes:
        reasons.append("free storage is below the test floor")
    if state.active_camera_clients:
        reasons.append("CameraService already has active clients")
    if state.conflicting_background_processes:
        reasons.append("conflicting background processes are active")
    if _SHA256.fullmatch(state.candidate_apk_sha256) is None:
        reasons.append("candidate APK SHA-256 is missing or malformed")
    if _SHA256.fullmatch(state.installed_apk_sha256) is None:
        reasons.append("installed APK SHA-256 is missing or malformed")
    if state.candidate_apk_sha256.upper() != state.installed_apk_sha256.upper():
        reasons.append("installed APK identity does not match the candidate")
    if _SHA256.fullmatch(state.signing_certificate_sha256) is None:
        reasons.append("installed signing certificate SHA-256 is missing or malformed")

    if not isinstance(state.thermal_status, int) or isinstance(state.thermal_status, bool) or not 0 <= state.thermal_status <= 6:
        reasons.append("Android thermal status is outside 0..6")
    if not math.isfinite(state.skin_temperature_c) or not -20.0 <= state.skin_temperature_c <= 100.0:
        reasons.append("skin temperature is missing or implausible")

    if test_class == "performance":
        if 0 <= state.thermal_status <= 6 and state.thermal_status > policy.maximum_performance_thermal_status:
            reasons.append("thermal status is too high for performance evidence")
        if math.isfinite(state.skin_temperature_c) and state.skin_temperature_c > policy.maximum_performance_skin_c:
            reasons.append("skin temperature is too high for performance evidence")
    else:
        if 0 <= state.thermal_status <= 6 and state.thermal_status > policy.maximum_functional_thermal_status:
            reasons.append("thermal status is too high even for functional evidence")
        if math.isfinite(state.skin_temperature_c) and state.skin_temperature_c > policy.maximum_functional_skin_c:
            reasons.append("skin temperature is too high even for functional evidence")
    return AdmissionDecision(not reasons, test_class, tuple(reasons))


@dataclass(frozen=True)
class DeviceBatch:
    name: str
    checkpoint_ids: tuple[str, ...]
    test_class: str
    requires_clean_install: bool = False
    camera_exclusive: bool = True


DEFAULT_DEVICE_BATCHES: tuple[DeviceBatch, ...] = (
    DeviceBatch("identity_install", tuple(f"CP-{n:03d}" for n in range(21, 45)), "functional", True),
    DeviceBatch("startup_permissions", tuple(f"CP-{n:03d}" for n in range(45, 81)), "functional", True),
    DeviceBatch("modes_settings", tuple(f"CP-{n:03d}" for n in range(81, 111)), "functional"),
    DeviceBatch("controls_preview", tuple(f"CP-{n:03d}" for n in range(111, 141)), "functional"),
    DeviceBatch("capture_recording", tuple(f"CP-{n:03d}" for n in range(141, 171)), "functional"),
    DeviceBatch("conversion_playback", tuple(f"CP-{n:03d}" for n in range(171, 201)), "functional"),
    DeviceBatch("lifecycle_release", tuple(f"CP-{n:03d}" for n in range(201, 221)), "functional"),
    DeviceBatch(
        "performance_thermal_endurance",
        tuple(
            f"CP-{n:03d}"
            for n in (
                *range(134, 138),
                *range(158, 171),
                190,
                199,
                *range(206, 214),
                219,
            )
        ),
        "performance",
    ),
)


def build_adb_command(serial: str, *arguments: str) -> tuple[str, ...]:
    if not serial.strip():
        raise ValueError("an explicit ADB serial is mandatory")
    if any("\x00" in argument for argument in arguments):
        raise ValueError("ADB arguments cannot contain NUL")
    return ("adb", "-s", serial, *arguments)


@dataclass(frozen=True)
class UiTarget:
    resource_id: str
    text: str
    bounds: tuple[int, int, int, int]
    center: tuple[int, int]


_BOUNDS = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")


def ui_target_from_tree(
    xml_text: str,
    *,
    resource_id: str = "",
    text: str = "",
) -> UiTarget:
    if not resource_id and not text:
        raise ValueError("resource_id or text selector is required")
    root = ET.fromstring(xml_text)
    matches = []
    for node in root.iter("node"):
        if resource_id and node.attrib.get("resource-id", "") != resource_id:
            continue
        if text and node.attrib.get("text", "") != text:
            continue
        match = _BOUNDS.match(node.attrib.get("bounds", ""))
        if not match:
            continue
        bounds = tuple(int(value) for value in match.groups())
        matches.append((node, bounds))
    if len(matches) != 1:
        raise LookupError(f"UI selector resolved {len(matches)} nodes; exactly one is required")
    node, bounds = matches[0]
    left, top, right, bottom = bounds
    if right <= left or bottom <= top:
        raise ValueError("UI node has empty or inverted bounds")
    return UiTarget(
        resource_id=node.attrib.get("resource-id", ""),
        text=node.attrib.get("text", ""),
        bounds=(left, top, right, bottom),
        center=((left + right) // 2, (top + bottom) // 2),
    )


def tap_command_from_tree(
    serial: str,
    xml_text: str,
    *,
    resource_id: str = "",
    text: str = "",
) -> tuple[str, ...]:
    target = ui_target_from_tree(xml_text, resource_id=resource_id, text=text)
    return build_adb_command(serial, "shell", "input", "tap", *map(str, target.center))


def ensure_no_concurrent_camera_batches(batches: Sequence[DeviceBatch]) -> None:
    # Device batches are intentionally a sequence, not a parallel wave. Reject accidental duplicate
    # names because they make resumable evidence ownership ambiguous.
    names = [batch.name for batch in batches]
    if len(names) != len(set(names)):
        raise ValueError("device batch names must be unique")
    if any(batch.test_class not in {"functional", "performance"} for batch in batches):
        raise ValueError("device batches have an unsupported test class")
