"""Deterministic host-side digital twins for NEO-QA HyperGrid.

This module models algorithm and source-contract behaviour.  It deliberately
does *not* claim to measure a physical camera, codec, display, thermal system,
colour response, image quality, or realtime FPS.  Those properties require
reproducible evidence from the target device.

The implementation uses logical sequence numbers instead of wall-clock time,
contains no I/O except the explicit deterministic JSON writer, and gives every
owned resource an exact-once release contract.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
import hashlib
import json
import math
import os
from pathlib import Path
import random
import tempfile
from typing import Any, Callable, Iterable, Mapping, Optional, Sequence


EVIDENCE_SCOPE = (
    "Host digital-twin algorithm/source-contract evidence only; this is not "
    "proof of physical camera FPS, codec throughput, thermal performance, "
    "camera colour, image quality, display behaviour, or device compatibility."
)


class ContractViolation(RuntimeError):
    """Raised when a simulated source contract is violated."""


class ExactOnceViolation(ContractViolation):
    """Raised for duplicate ownership or double/unknown release."""


class TwinStatus(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"


@dataclass(frozen=True)
class TwinEvent:
    sequence: int
    component: str
    action: str
    status: str
    details: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "sequence": self.sequence,
            "component": self.component,
            "action": self.action,
            "status": self.status,
            "details": dict(sorted(self.details.items())),
        }


class EventJournal:
    """Monotonic logical event journal with no wall-clock nondeterminism."""

    def __init__(self) -> None:
        self._sequence = 0
        self._events: list[TwinEvent] = []

    def record(
        self,
        component: str,
        action: str,
        status: str = "OK",
        **details: Any,
    ) -> TwinEvent:
        self._sequence += 1
        event = TwinEvent(
            sequence=self._sequence,
            component=component,
            action=action,
            status=status,
            details=dict(sorted(details.items())),
        )
        self._events.append(event)
        return event

    @property
    def events(self) -> tuple[TwinEvent, ...]:
        return tuple(self._events)

    def to_list(self) -> list[dict[str, Any]]:
        return [event.to_dict() for event in self._events]


@dataclass
class _OwnedResource:
    kind: str
    generation: int
    released: bool = False
    release_reason: Optional[str] = None


class ExactOnceResourceTracker:
    """Tracks explicit ownership and detects duplicate/double release."""

    def __init__(self, journal: EventJournal, component: str) -> None:
        self.journal = journal
        self.component = component
        self._resources: dict[str, _OwnedResource] = {}
        self.release_count = 0
        self.violations: list[str] = []

    def acquire(self, token: str, kind: str, generation: int) -> None:
        previous = self._resources.get(token)
        if previous is not None:
            message = f"duplicate ownership token: {token}"
            self.violations.append(message)
            self.journal.record(self.component, "acquire", "FAIL", token=token, error=message)
            raise ExactOnceViolation(message)
        self._resources[token] = _OwnedResource(kind=kind, generation=generation)
        self.journal.record(
            self.component,
            "acquire",
            token=token,
            kind=kind,
            generation=generation,
        )

    def release(self, token: str, reason: str) -> None:
        resource = self._resources.get(token)
        if resource is None:
            message = f"unknown release token: {token}"
            self.violations.append(message)
            self.journal.record(self.component, "release", "FAIL", token=token, error=message)
            raise ExactOnceViolation(message)
        if resource.released:
            message = f"double release token: {token}"
            self.violations.append(message)
            self.journal.record(self.component, "release", "FAIL", token=token, error=message)
            raise ExactOnceViolation(message)
        resource.released = True
        resource.release_reason = reason
        self.release_count += 1
        self.journal.record(self.component, "release", token=token, reason=reason)

    def cleanup_generation(self, generation: int, reason: str) -> int:
        tokens = sorted(
            token
            for token, resource in self._resources.items()
            if resource.generation == generation and not resource.released
        )
        for token in tokens:
            self.release(token, reason)
        return len(tokens)

    def cleanup_all(self, reason: str) -> int:
        tokens = sorted(
            token for token, resource in self._resources.items() if not resource.released
        )
        for token in tokens:
            self.release(token, reason)
        return len(tokens)

    @property
    def active_tokens(self) -> tuple[str, ...]:
        return tuple(
            sorted(token for token, resource in self._resources.items() if not resource.released)
        )

    @property
    def active_count(self) -> int:
        return len(self.active_tokens)

    @property
    def exact_once_clean(self) -> bool:
        return not self.violations and self.active_count == 0


class PermissionState(str, Enum):
    UNKNOWN = "UNKNOWN"
    GRANTED = "GRANTED"
    DENIED = "DENIED"
    PERMANENTLY_DENIED = "PERMANENTLY_DENIED"
    REVOKED = "REVOKED"


class PermissionTwin:
    def __init__(self, journal: Optional[EventJournal] = None) -> None:
        self.journal = journal or EventJournal()
        self._states: dict[str, PermissionState] = {}

    def state(self, permission: str) -> PermissionState:
        return self._states.get(permission, PermissionState.UNKNOWN)

    def grant(self, permission: str) -> None:
        self._states[permission] = PermissionState.GRANTED
        self.journal.record("permissions", "grant", permission=permission)

    def deny(self, permission: str, permanent: bool = False) -> None:
        state = PermissionState.PERMANENTLY_DENIED if permanent else PermissionState.DENIED
        self._states[permission] = state
        self.journal.record("permissions", "deny", permission=permission, permanent=permanent)

    def revoke(self, permission: str) -> None:
        self._states[permission] = PermissionState.REVOKED
        self.journal.record("permissions", "revoke", permission=permission)

    def is_granted(self, permission: str) -> bool:
        return self.state(permission) == PermissionState.GRANTED

    def can_request(self, permission: str) -> bool:
        return self.state(permission) != PermissionState.PERMANENTLY_DENIED


class CameraState(str, Enum):
    CLOSED = "CLOSED"
    OPENING = "OPENING"
    ACTIVE = "ACTIVE"
    SWITCHING = "SWITCHING"
    BACKGROUND = "BACKGROUND"
    ERROR = "ERROR"


class CameraLifecycleTwin:
    VALID_MODES = ("Video", "Pro", "Raw")

    def __init__(
        self,
        permission_check: Callable[[], bool],
        journal: Optional[EventJournal] = None,
    ) -> None:
        self.journal = journal or EventJournal()
        self.permission_check = permission_check
        self.tracker = ExactOnceResourceTracker(self.journal, "camera")
        self.state = CameraState.CLOSED
        self.mode: Optional[str] = None
        self.generation = 0
        self.error: Optional[str] = None

    def _allocate_session(self, mode: str) -> None:
        self.tracker.acquire(f"camera:{self.generation}", "camera", self.generation)
        self.tracker.acquire(f"session:{self.generation}", "session", self.generation)
        self.tracker.acquire(f"surface:{self.generation}", "surface", self.generation)
        self.mode = mode

    def open(self, mode: str = "Video") -> bool:
        if mode not in self.VALID_MODES:
            raise ValueError(f"unsupported camera mode: {mode}")
        if not self.permission_check():
            self.state = CameraState.ERROR
            self.error = "CAMERA_PERMISSION_UNAVAILABLE"
            self.journal.record("camera", "open", "FAIL", mode=mode, error=self.error)
            return False
        if self.tracker.active_count:
            self.tracker.cleanup_all("open_replacement")
        self.generation += 1
        self.state = CameraState.OPENING
        self.error = None
        self._allocate_session(mode)
        self.journal.record("camera", "open", mode=mode, generation=self.generation)
        return True

    def activate(self) -> bool:
        if self.state != CameraState.OPENING:
            self.error = "INVALID_ACTIVATION_STATE"
            self.journal.record("camera", "activate", "FAIL", state=self.state.value)
            return False
        self.state = CameraState.ACTIVE
        self.journal.record("camera", "activate", mode=self.mode, generation=self.generation)
        return True

    def switch_mode(self, mode: str) -> bool:
        if mode not in self.VALID_MODES:
            raise ValueError(f"unsupported camera mode: {mode}")
        if self.state != CameraState.ACTIVE:
            self.error = "CAMERA_NOT_ACTIVE"
            self.journal.record("camera", "switch", "FAIL", mode=mode, state=self.state.value)
            return False
        if mode == self.mode:
            self.journal.record("camera", "switch_noop", mode=mode, generation=self.generation)
            return True
        old_generation = self.generation
        self.state = CameraState.SWITCHING
        self.tracker.cleanup_generation(old_generation, "mode_switch")
        self.generation += 1
        self._allocate_session(mode)
        self.state = CameraState.ACTIVE
        self.error = None
        self.journal.record(
            "camera",
            "switch",
            mode=mode,
            old_generation=old_generation,
            generation=self.generation,
        )
        return True

    def _terminal_failure(self, code: str, action: str) -> None:
        self.tracker.cleanup_all(code.lower())
        self.state = CameraState.ERROR
        self.error = code
        self.journal.record("camera", action, "FAIL", error=code, generation=self.generation)

    def disconnect(self) -> None:
        self._terminal_failure("CAMERA_DISCONNECTED", "disconnect")

    def surface_lost(self) -> None:
        self._terminal_failure("SURFACE_LOST", "surface_lost")

    def permission_revoked(self) -> None:
        self._terminal_failure("CAMERA_PERMISSION_REVOKED", "permission_revoked")

    def cancel_lifecycle(self) -> None:
        self.tracker.cleanup_all("lifecycle_cancelled")
        self.state = CameraState.CLOSED
        self.error = "LIFECYCLE_CANCELLED"
        self.mode = None
        self.journal.record("camera", "lifecycle_cancel", error=self.error)

    def background(self) -> bool:
        if self.state != CameraState.ACTIVE:
            return False
        self.tracker.cleanup_all("background")
        self.state = CameraState.BACKGROUND
        self.journal.record("camera", "background", generation=self.generation)
        return True

    def recover(self, mode: str = "Video") -> bool:
        if self.state not in (CameraState.ERROR, CameraState.CLOSED, CameraState.BACKGROUND):
            return False
        if not self.open(mode):
            return False
        recovered = self.activate()
        self.journal.record("camera", "recover", "OK" if recovered else "FAIL", mode=mode)
        return recovered

    def close(self) -> None:
        self.tracker.cleanup_all("close")
        self.state = CameraState.CLOSED
        self.mode = None
        self.journal.record("camera", "close", generation=self.generation)


class RawOwnershipTwin:
    """Models ImageReader ownership, capacity, and release wakeups."""

    def __init__(self, capacity: int, journal: Optional[EventJournal] = None) -> None:
        if capacity <= 0:
            raise ValueError("RAW capacity must be positive")
        self.capacity = capacity
        self.journal = journal or EventJournal()
        self.tracker = ExactOnceResourceTracker(self.journal, "raw")
        self.peak_owned = 0
        self.accepted_frames = 0
        self.backpressured_frames = 0
        self.capacity_wakeups = 0
        self.last_error: Optional[str] = None

    @staticmethod
    def token(frame_id: int, generation: int) -> str:
        return f"raw:{generation}:{frame_id}"

    def try_acquire(self, frame_id: int, generation: int) -> bool:
        token = self.token(frame_id, generation)
        if token in self.tracker.active_tokens:
            self.last_error = "DUPLICATE_RAW_FRAME"
            self.journal.record("raw", "pre_acquire", "FAIL", frame_id=frame_id, error=self.last_error)
            return False
        if self.tracker.active_count >= self.capacity:
            self.backpressured_frames += 1
            self.last_error = "RAW_CAPACITY_BACKPRESSURE"
            self.journal.record(
                "raw",
                "pre_acquire",
                "BLOCKED",
                frame_id=frame_id,
                owned=self.tracker.active_count,
                capacity=self.capacity,
            )
            return False
        self.tracker.acquire(token, "image", generation)
        self.accepted_frames += 1
        self.peak_owned = max(self.peak_owned, self.tracker.active_count)
        self.last_error = None
        return True

    def release(self, frame_id: int, generation: int, reason: str = "processed") -> None:
        self.tracker.release(self.token(frame_id, generation), reason)
        self.capacity_wakeups += 1
        self.journal.record("raw", "capacity_wakeup", owned=self.tracker.active_count)

    def cleanup_generation(self, generation: int, reason: str) -> int:
        count = self.tracker.cleanup_generation(generation, reason)
        self.capacity_wakeups += count
        return count

    def cleanup_all(self, reason: str) -> int:
        count = self.tracker.cleanup_all(reason)
        self.capacity_wakeups += count
        return count

    @property
    def ownership_safe(self) -> bool:
        return self.peak_owned <= self.capacity and not self.tracker.violations


class EncoderState(str, Enum):
    IDLE = "IDLE"
    CONFIGURED = "CONFIGURED"
    RUNNING = "RUNNING"
    ERROR = "ERROR"
    STOPPED = "STOPPED"


@dataclass(frozen=True)
class CadenceReport:
    target_fps: float
    target_interval_us: float
    submitted: int
    encoded: int
    missing: int
    duplicate_outputs: int
    submitted_pts_monotonic: bool
    output_pts_monotonic: bool
    measured_output_fps: Optional[float]
    max_interval_error_us: Optional[float]
    cadence_within_tolerance: Optional[bool]
    frame_drop_detected: bool
    frame_delivery_ratio: float
    contract_pass: bool

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class EncoderCadenceTwin:
    """Models submitted/output cadence without claiming physical throughput."""

    def __init__(self, target_fps: float, journal: Optional[EventJournal] = None) -> None:
        if not math.isfinite(target_fps) or target_fps <= 0:
            raise ValueError("target_fps must be finite and positive")
        self.target_fps = float(target_fps)
        self.target_interval_us = 1_000_000.0 / target_fps
        self.journal = journal or EventJournal()
        self.tracker = ExactOnceResourceTracker(self.journal, "encoder")
        self.state = EncoderState.IDLE
        self.generation = 0
        self.error: Optional[str] = None
        self.submitted: list[tuple[int, int]] = []
        self.outputs: list[tuple[int, int, bool]] = []
        self._submitted_ids: set[int] = set()
        self._output_ids: set[int] = set()
        self.duplicate_outputs = 0

    def configure(self, accepted: bool = True, rejection_code: str = "CODEC_REJECTED") -> bool:
        self.reset("reconfigure")
        self.generation += 1
        token = f"codec:{self.generation}"
        self.tracker.acquire(token, "codec", self.generation)
        if not accepted:
            self.tracker.release(token, "configuration_rejected")
            self.state = EncoderState.ERROR
            self.error = rejection_code
            self.journal.record("encoder", "configure", "FAIL", error=rejection_code)
            return False
        self.state = EncoderState.CONFIGURED
        self.error = None
        self.journal.record("encoder", "configure", target_fps=self.target_fps)
        return True

    def submit(self, frame_id: int, pts_us: int) -> bool:
        if self.state not in (EncoderState.CONFIGURED, EncoderState.RUNNING):
            self.error = "ENCODER_NOT_CONFIGURED"
            return False
        if frame_id in self._submitted_ids:
            self.error = "DUPLICATE_INPUT_FRAME"
            self.state = EncoderState.ERROR
            return False
        if self.submitted and pts_us <= self.submitted[-1][1]:
            self.error = "NON_MONOTONIC_INPUT_PTS"
            self.state = EncoderState.ERROR
            return False
        self._submitted_ids.add(frame_id)
        self.submitted.append((frame_id, int(pts_us)))
        self.state = EncoderState.RUNNING
        self.journal.record("encoder", "submit", frame_id=frame_id, pts_us=pts_us)
        return True

    def emit(self, frame_id: int, pts_us: int, keyframe: bool = False) -> bool:
        if frame_id not in self._submitted_ids:
            self.error = "OUTPUT_WITHOUT_INPUT"
            self.state = EncoderState.ERROR
            return False
        if frame_id in self._output_ids:
            self.duplicate_outputs += 1
            self.error = "DUPLICATE_ENCODER_OUTPUT"
            self.state = EncoderState.ERROR
            return False
        if self.outputs and pts_us <= self.outputs[-1][1]:
            self.error = "NON_MONOTONIC_OUTPUT_PTS"
            self.state = EncoderState.ERROR
            return False
        self._output_ids.add(frame_id)
        self.outputs.append((frame_id, int(pts_us), bool(keyframe)))
        self.journal.record(
            "encoder", "output", frame_id=frame_id, pts_us=pts_us, keyframe=keyframe
        )
        return True

    def cadence_report(self) -> CadenceReport:
        submitted_pts = [pts for _, pts in self.submitted]
        output_pts = [pts for _, pts, _ in self.outputs]
        input_monotonic = all(b > a for a, b in zip(submitted_pts, submitted_pts[1:]))
        output_monotonic = all(b > a for a, b in zip(output_pts, output_pts[1:]))
        missing = len(self._submitted_ids - self._output_ids)
        if len(output_pts) >= 2:
            duration = output_pts[-1] - output_pts[0]
            measured_fps = (len(output_pts) - 1) * 1_000_000.0 / duration if duration > 0 else None
            errors = [
                abs((b - a) - self.target_interval_us)
                for a, b in zip(output_pts, output_pts[1:])
            ]
            max_error = max(errors, default=0.0)
            cadence_within_tolerance: Optional[bool] = (
                max_error <= self.target_interval_us * 0.05
            )
        else:
            measured_fps = None
            max_error = None
            cadence_within_tolerance = None
        ratio = len(self.outputs) / len(self.submitted) if self.submitted else 0.0
        frame_drop_detected = missing > 0 or self.duplicate_outputs > 0
        contract_pass = (
            len(self.submitted) >= 2
            and len(self.outputs) >= 2
            and not frame_drop_detected
            and input_monotonic
            and output_monotonic
            and cadence_within_tolerance is True
            and self.error is None
        )
        return CadenceReport(
            target_fps=self.target_fps,
            target_interval_us=self.target_interval_us,
            submitted=len(self.submitted),
            encoded=len(self.outputs),
            missing=missing,
            duplicate_outputs=self.duplicate_outputs,
            submitted_pts_monotonic=input_monotonic,
            output_pts_monotonic=output_monotonic,
            measured_output_fps=measured_fps,
            max_interval_error_us=max_error,
            cadence_within_tolerance=cadence_within_tolerance,
            frame_drop_detected=frame_drop_detected,
            frame_delivery_ratio=ratio,
            contract_pass=contract_pass,
        )

    def finalize(self, require_all_frames: bool = True) -> CadenceReport:
        report = self.cadence_report()
        if require_all_frames and report.missing:
            self.error = "ENCODER_STARVATION"
            self.state = EncoderState.ERROR
            self.journal.record("encoder", "finalize", "FAIL", missing=report.missing)
            report = self.cadence_report()
        elif not report.contract_pass:
            if self.error is None:
                self.error = (
                    "ENCODER_CADENCE_VIOLATION"
                    if report.cadence_within_tolerance is False
                    else "ENCODER_CONTRACT_FAILED"
                )
            self.state = EncoderState.ERROR
            self.journal.record("encoder", "finalize", "FAIL", error=self.error)
            report = self.cadence_report()
        else:
            self.state = EncoderState.STOPPED
            self.journal.record("encoder", "finalize", encoded=report.encoded)
        self.tracker.cleanup_all("encoder_finalize")
        return report

    def reset(self, reason: str = "reset") -> None:
        self.tracker.cleanup_all(reason)
        self.state = EncoderState.IDLE
        self.error = None
        self.submitted.clear()
        self.outputs.clear()
        self._submitted_ids.clear()
        self._output_ids.clear()
        self.duplicate_outputs = 0


@dataclass(frozen=True)
class ControlLimits:
    iso_min: int = 50
    iso_max: int = 12800
    exposure_min_ns: int = 100_000
    exposure_max_ns: int = 1_000_000_000
    ev_min: float = -4.0
    ev_max: float = 4.0
    wb_min_kelvin: int = 2000
    wb_max_kelvin: int = 12000
    max_focus_diopters: float = 10.0


@dataclass(frozen=True)
class ControlRequest:
    target_fps: float
    auto_exposure: bool = True
    iso: Optional[int] = None
    shutter_ns: Optional[int] = None
    ev: float = 0.0
    wb_kelvin: int = 5500
    wb_tint: float = 0.0
    focus_diopters: float = 0.0


@dataclass(frozen=True)
class ControlResult:
    iso: int
    shutter_ns: int
    effective_ev: float
    wb_kelvin: int
    wb_tint: float
    wb_gains: tuple[float, float, float, float]
    focus_diopters: float
    clipped_fields: tuple[str, ...]
    invariant_pass: bool


class ControlPolicyTwin:
    """Deterministic policy twin for ISO/shutter/EV/WB/focus invariants."""

    def __init__(
        self,
        limits: ControlLimits = ControlLimits(),
        journal: Optional[EventJournal] = None,
    ) -> None:
        self.limits = limits
        self.journal = journal or EventJournal()
        if (
            limits.iso_min <= 0
            or limits.iso_max < limits.iso_min
            or limits.exposure_min_ns <= 0
            or limits.exposure_max_ns < limits.exposure_min_ns
            or not math.isfinite(limits.ev_min)
            or not math.isfinite(limits.ev_max)
            or limits.ev_max < limits.ev_min
            or limits.wb_min_kelvin <= 0
            or limits.wb_max_kelvin < limits.wb_min_kelvin
            or not math.isfinite(limits.max_focus_diopters)
            or limits.max_focus_diopters < 0
        ):
            raise ValueError("control limits are invalid")

    @staticmethod
    def _clamp(value: float, lower: float, upper: float) -> float:
        return min(max(value, lower), upper)

    def apply(self, request: ControlRequest) -> ControlResult:
        if not math.isfinite(request.target_fps) or request.target_fps <= 0:
            raise ValueError("target_fps must be finite and positive")
        numeric_values = (request.ev, request.wb_tint, request.focus_diopters)
        if not all(math.isfinite(value) for value in numeric_values):
            raise ValueError("control values must be finite")
        clipped: list[str] = []
        requested_iso = request.iso if request.iso is not None else 400
        iso = int(self._clamp(requested_iso, self.limits.iso_min, self.limits.iso_max))
        if iso != requested_iso:
            clipped.append("iso")
        frame_period_ns = int(math.floor(1_000_000_000.0 / request.target_fps))
        shutter_ceiling = min(self.limits.exposure_max_ns, frame_period_ns)
        if shutter_ceiling < self.limits.exposure_min_ns:
            raise ValueError("target_fps leaves no valid sensor exposure interval")
        requested_shutter = request.shutter_ns if request.shutter_ns is not None else frame_period_ns // 2
        shutter = int(
            self._clamp(requested_shutter, self.limits.exposure_min_ns, shutter_ceiling)
        )
        if shutter != requested_shutter:
            clipped.append("shutter_ns")
        requested_ev = request.ev if request.auto_exposure else 0.0
        effective_ev = self._clamp(requested_ev, self.limits.ev_min, self.limits.ev_max)
        if effective_ev != request.ev:
            clipped.append("ev")
        wb_kelvin = int(
            self._clamp(request.wb_kelvin, self.limits.wb_min_kelvin, self.limits.wb_max_kelvin)
        )
        if wb_kelvin != request.wb_kelvin:
            clipped.append("wb_kelvin")
        wb_tint = self._clamp(request.wb_tint, -1.0, 1.0)
        if wb_tint != request.wb_tint:
            clipped.append("wb_tint")
        focus = self._clamp(request.focus_diopters, 0.0, self.limits.max_focus_diopters)
        if focus != request.focus_diopters:
            clipped.append("focus_diopters")

        temperature_ratio = 5500.0 / wb_kelvin
        red = self._clamp(temperature_ratio ** 0.55 + 0.10 * wb_tint, 0.25, 4.0)
        blue = self._clamp((1.0 / temperature_ratio) ** 0.55 - 0.10 * wb_tint, 0.25, 4.0)
        green_even = self._clamp(1.0 - 0.06 * wb_tint, 0.25, 4.0)
        green_odd = self._clamp(1.0 + 0.06 * wb_tint, 0.25, 4.0)
        gains = (red, green_even, green_odd, blue)
        invariant_pass = (
            self.limits.iso_min <= iso <= self.limits.iso_max
            and self.limits.exposure_min_ns <= shutter <= shutter_ceiling
            and self.limits.ev_min <= effective_ev <= self.limits.ev_max
            and self.limits.wb_min_kelvin <= wb_kelvin <= self.limits.wb_max_kelvin
            and 0.0 <= focus <= self.limits.max_focus_diopters
            and all(0.25 <= gain <= 4.0 and math.isfinite(gain) for gain in gains)
        )
        result = ControlResult(
            iso=iso,
            shutter_ns=shutter,
            effective_ev=effective_ev,
            wb_kelvin=wb_kelvin,
            wb_tint=wb_tint,
            wb_gains=gains,
            focus_diopters=focus,
            clipped_fields=tuple(sorted(set(clipped))),
            invariant_pass=invariant_pass,
        )
        self.journal.record(
            "controls",
            "apply",
            invariant_pass=invariant_pass,
            clipped=list(result.clipped_fields),
        )
        return result


@dataclass(frozen=True)
class ColourProfilePacket:
    generation: int
    mode: str
    correction: str
    science: str
    fingerprint: str


class ColourProfileLifecycleTwin:
    REQUIREMENTS: Mapping[str, tuple[str, str]] = {
        "SDR": ("ISP", "SDR"),
        "RAW": ("OFF", "RAW"),
        "XDR": ("NDR", "XDR"),
        "NDR10_PLUS": ("NDR", "NDR10_PLUS"),
        "NIVUKX_VISION": ("NDR", "NIVUKX_VISION"),
    }

    def __init__(self, journal: Optional[EventJournal] = None) -> None:
        self.journal = journal or EventJournal()
        self.generation = 0
        self.mode: Optional[str] = None
        self.committed: Optional[ColourProfilePacket] = None
        self.last_error: Optional[str] = None

    def transition(self, mode: str) -> int:
        if mode not in self.REQUIREMENTS:
            raise ValueError(f"unsupported colour mode: {mode}")
        self.generation += 1
        self.mode = mode
        self.committed = None
        self.last_error = None
        self.journal.record("colour_profile", "transition", mode=mode, generation=self.generation)
        return self.generation

    def expected_packet(self, fingerprint: str) -> ColourProfilePacket:
        if self.mode is None:
            raise ContractViolation("profile transition required")
        correction, science = self.REQUIREMENTS[self.mode]
        return ColourProfilePacket(
            generation=self.generation,
            mode=self.mode,
            correction=correction,
            science=science,
            fingerprint=fingerprint,
        )

    def commit(self, packet: ColourProfilePacket) -> bool:
        if packet.generation != self.generation:
            self.last_error = "STALE_PROFILE_GENERATION"
        elif packet.mode != self.mode:
            self.last_error = "PROFILE_MODE_MISMATCH"
        else:
            expected_correction, expected_science = self.REQUIREMENTS[packet.mode]
            if packet.correction != expected_correction:
                self.last_error = "PROFILE_CORRECTION_MISMATCH"
            elif packet.science != expected_science:
                self.last_error = "PROFILE_SCIENCE_MISMATCH"
            elif not packet.fingerprint:
                self.last_error = "PROFILE_FINGERPRINT_MISSING"
            else:
                self.committed = packet
                self.last_error = None
                self.journal.record(
                    "colour_profile",
                    "commit",
                    mode=packet.mode,
                    generation=packet.generation,
                )
                return True
        self.journal.record(
            "colour_profile",
            "commit",
            "FAIL",
            error=self.last_error,
            packet_generation=packet.generation,
            current_generation=self.generation,
        )
        return False


@dataclass(frozen=True)
class ConversionProfile:
    source: str
    destination: str
    width: int
    height: int
    bit_depth: int
    bitrate_bps: int
    required_metadata: tuple[str, ...] = (
        "sensor_timestamp_ns",
        "iso",
        "exposure_ns",
        "wb_gains",
    )


class ConversionState(str, Enum):
    IDLE = "IDLE"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    ERROR = "ERROR"


class ConversionPipelineTwin:
    def __init__(self, journal: Optional[EventJournal] = None) -> None:
        self.journal = journal or EventJournal()
        self.tracker = ExactOnceResourceTracker(self.journal, "conversion")
        self.state = ConversionState.IDLE
        self.generation = 0
        self.profile: Optional[ConversionProfile] = None
        self.frames: list[tuple[int, int]] = []
        self._frame_ids: set[int] = set()
        self.published = False
        self.error: Optional[str] = None

    def start(self, profile: ConversionProfile) -> bool:
        if profile.width <= 0 or profile.height <= 0 or profile.bit_depth < 10:
            self.error = "INVALID_CONVERSION_PROFILE"
            self.state = ConversionState.ERROR
            return False
        if profile.bitrate_bps <= 0:
            self.error = "INVALID_CONVERSION_BITRATE"
            self.state = ConversionState.ERROR
            return False
        self.tracker.cleanup_all("conversion_restart")
        self.generation += 1
        self.tracker.acquire(f"conversion:{self.generation}", "pipeline", self.generation)
        self.state = ConversionState.RUNNING
        self.profile = profile
        self.frames.clear()
        self._frame_ids.clear()
        self.published = False
        self.error = None
        self.journal.record(
            "conversion",
            "start",
            source=profile.source,
            destination=profile.destination,
            bit_depth=profile.bit_depth,
        )
        return True

    def ingest(self, frame_id: int, pts_us: int, metadata: Mapping[str, Any]) -> bool:
        if self.state != ConversionState.RUNNING or self.profile is None:
            self.error = "CONVERSION_NOT_RUNNING"
            return False
        if self.frames and pts_us <= self.frames[-1][1]:
            self.error = "NON_MONOTONIC_CONVERSION_PTS"
            self.state = ConversionState.ERROR
            self.tracker.cleanup_all("conversion_pts_failure")
            return False
        if frame_id in self._frame_ids:
            self.error = "DUPLICATE_CONVERSION_FRAME"
            self.state = ConversionState.ERROR
            self.tracker.cleanup_all("conversion_duplicate_frame")
            return False
        missing = [key for key in self.profile.required_metadata if key not in metadata]
        if missing:
            self.error = "CONVERSION_METADATA_MISSING"
            self.state = ConversionState.ERROR
            self.tracker.cleanup_all("conversion_metadata_failure")
            self.journal.record("conversion", "ingest", "FAIL", missing=sorted(missing))
            return False
        self._frame_ids.add(frame_id)
        self.frames.append((frame_id, pts_us))
        self.journal.record("conversion", "ingest", frame_id=frame_id, pts_us=pts_us)
        return True

    def complete(self) -> bool:
        if self.state != ConversionState.RUNNING or not self.frames:
            self.error = "CONVERSION_INCOMPLETE"
            self.state = ConversionState.ERROR
            self.tracker.cleanup_all("conversion_incomplete")
            self.published = False
            return False
        self.tracker.cleanup_all("conversion_complete")
        self.state = ConversionState.COMPLETED
        self.published = True
        self.error = None
        self.journal.record("conversion", "complete", frames=len(self.frames))
        return True

    def cancel(self) -> None:
        self.tracker.cleanup_all("conversion_cancelled")
        self.state = ConversionState.CANCELLED
        self.published = False
        self.error = "CONVERSION_CANCELLED"
        self.journal.record("conversion", "cancel", error=self.error)

    def fail(self, error: str) -> None:
        self.tracker.cleanup_all("conversion_failed")
        self.state = ConversionState.ERROR
        self.published = False
        self.error = error
        self.journal.record("conversion", "fail", "FAIL", error=error)


class PlaybackState(str, Enum):
    IDLE = "IDLE"
    PLAYING = "PLAYING"
    ERROR = "ERROR"
    COMPLETE = "COMPLETE"


class PlaybackSchedulerTwin:
    def __init__(
        self,
        late_threshold_us: int = 20_000,
        journal: Optional[EventJournal] = None,
    ) -> None:
        if late_threshold_us < 0:
            raise ValueError("late threshold cannot be negative")
        self.journal = journal or EventJournal()
        self.late_threshold_us = late_threshold_us
        self.queue: list[tuple[int, int]] = []
        self.rendered: list[int] = []
        self.dropped: list[int] = []
        self.state = PlaybackState.IDLE
        self.error: Optional[str] = None
        self._media_anchor: Optional[int] = None
        self._clock_anchor: Optional[int] = None

    def enqueue(self, frame_id: int, pts_us: int) -> bool:
        if self.queue and pts_us <= self.queue[-1][1]:
            self.error = "NON_MONOTONIC_PLAYBACK_PTS"
            self.state = PlaybackState.ERROR
            return False
        self.queue.append((frame_id, pts_us))
        self.journal.record("playback", "enqueue", frame_id=frame_id, pts_us=pts_us)
        return True

    def present_next(self, clock_us: int) -> Optional[str]:
        if self.state == PlaybackState.ERROR:
            return None
        if not self.queue:
            self.state = PlaybackState.COMPLETE
            return None
        frame_id, pts_us = self.queue[0]
        if self._media_anchor is None:
            self._media_anchor = pts_us
            self._clock_anchor = clock_us
        assert self._clock_anchor is not None
        due = self._clock_anchor + (pts_us - self._media_anchor)
        if clock_us < due:
            self.state = PlaybackState.PLAYING
            self.journal.record(
                "playback",
                "present",
                "WAIT",
                frame_id=frame_id,
                early_us=due - clock_us,
            )
            return "WAIT"
        self.queue.pop(0)
        lateness = clock_us - due
        self.state = PlaybackState.PLAYING
        if lateness > self.late_threshold_us:
            self.dropped.append(frame_id)
            outcome = "DROP"
        else:
            self.rendered.append(frame_id)
            outcome = "RENDER"
        self.journal.record(
            "playback",
            "present",
            outcome,
            frame_id=frame_id,
            lateness_us=lateness,
        )
        if not self.queue:
            self.state = PlaybackState.COMPLETE
        return outcome

    def decoder_failure(self) -> None:
        self.queue.clear()
        self.state = PlaybackState.ERROR
        self.error = "PLAYBACK_DECODER_FAILED"
        self.journal.record("playback", "decoder_failure", "FAIL", error=self.error)

    def recover(self) -> None:
        self.queue.clear()
        self.rendered.clear()
        self.dropped.clear()
        self._media_anchor = None
        self._clock_anchor = None
        self.state = PlaybackState.IDLE
        self.error = None
        self.journal.record("playback", "recover")


@dataclass
class _MediaTransaction:
    item_id: str
    required_bytes: int
    require_sidecar: bool
    main_valid: bool = False
    sidecar_valid: bool = False
    pending: bool = True
    published: bool = False
    error: Optional[str] = None


class MediaStoreTwin:
    """Models pending MediaStore publication with all-or-nothing commit."""

    def __init__(self, free_bytes: int, journal: Optional[EventJournal] = None) -> None:
        if free_bytes < 0:
            raise ValueError("free storage cannot be negative")
        self.free_bytes = free_bytes
        self.journal = journal or EventJournal()
        self.tracker = ExactOnceResourceTracker(self.journal, "mediastore")
        self.transactions: dict[str, _MediaTransaction] = {}
        self.published_items: set[str] = set()
        self.last_error: Optional[str] = None

    def begin(self, item_id: str, required_bytes: int, require_sidecar: bool = True) -> bool:
        if item_id in self.transactions:
            self.last_error = "DUPLICATE_MEDIA_TRANSACTION"
            return False
        if required_bytes <= 0:
            self.last_error = "INVALID_MEDIA_SIZE"
            return False
        reserved_bytes = sum(
            transaction.required_bytes
            for transaction in self.transactions.values()
            if transaction.pending
        )
        available_bytes = self.free_bytes - reserved_bytes
        if required_bytes > available_bytes:
            self.last_error = "LOW_STORAGE"
            self.journal.record(
                "mediastore",
                "begin",
                "FAIL",
                item_id=item_id,
                required_bytes=required_bytes,
                free_bytes=available_bytes,
                error=self.last_error,
            )
            return False
        transaction = _MediaTransaction(item_id, required_bytes, require_sidecar)
        self.transactions[item_id] = transaction
        self.tracker.acquire(f"media:{item_id}", "pending_item", 0)
        self.last_error = None
        self.journal.record("mediastore", "begin", item_id=item_id)
        return True

    def write_main(self, item_id: str, valid: bool = True) -> bool:
        transaction = self.transactions[item_id]
        if not transaction.pending:
            return False
        transaction.main_valid = valid
        if not valid:
            transaction.error = "MAIN_PUBLICATION_FAILED"
        self.journal.record(
            "mediastore", "write_main", "OK" if valid else "FAIL", item_id=item_id
        )
        return valid

    def write_sidecar(self, item_id: str, valid: bool = True) -> bool:
        transaction = self.transactions[item_id]
        if not transaction.pending:
            return False
        transaction.sidecar_valid = valid
        if not valid:
            transaction.error = "SIDECAR_PUBLICATION_FAILED"
        self.journal.record(
            "mediastore", "write_sidecar", "OK" if valid else "FAIL", item_id=item_id
        )
        return valid

    def commit(self, item_id: str) -> bool:
        transaction = self.transactions[item_id]
        valid = transaction.main_valid and (
            transaction.sidecar_valid or not transaction.require_sidecar
        )
        if not transaction.pending or not valid or transaction.error:
            self.last_error = transaction.error or "INCOMPLETE_MEDIA_TRANSACTION"
            self.abort(item_id, self.last_error)
            return False
        transaction.pending = False
        transaction.published = True
        self.free_bytes -= transaction.required_bytes
        self.published_items.add(item_id)
        self.tracker.release(f"media:{item_id}", "published")
        self.last_error = None
        self.journal.record("mediastore", "commit", item_id=item_id)
        return True

    def abort(self, item_id: str, error: str) -> None:
        transaction = self.transactions[item_id]
        if not transaction.pending:
            self.last_error = "MEDIA_TRANSACTION_NOT_PENDING"
            self.journal.record(
                "mediastore",
                "abort",
                "FAIL",
                item_id=item_id,
                error=self.last_error,
            )
            return
        transaction.pending = False
        transaction.published = False
        transaction.error = error
        self.tracker.release(f"media:{item_id}", "aborted")
        self.published_items.discard(item_id)
        self.last_error = error
        self.journal.record("mediastore", "abort", "FAIL", item_id=item_id, error=error)

    def add_storage(self, additional_bytes: int) -> None:
        if additional_bytes < 0:
            raise ValueError("additional storage cannot be negative")
        self.free_bytes += additional_bytes
        self.journal.record("mediastore", "add_storage", bytes=additional_bytes)

    @property
    def has_corrupt_publication(self) -> bool:
        return any(
            transaction.published
            and (
                not transaction.main_valid
                or (transaction.require_sidecar and not transaction.sidecar_valid)
            )
            for transaction in self.transactions.values()
        )


@dataclass(frozen=True)
class PressureDecision:
    thermal_status: int
    memory_used_bytes: int
    memory_limit_bytes: int
    functional_admitted: bool
    performance_measurement_admitted: bool
    secondary_work_fraction: float
    reason: Optional[str]


class PressureTwin:
    def __init__(self, memory_limit_bytes: int, journal: Optional[EventJournal] = None) -> None:
        if memory_limit_bytes <= 0:
            raise ValueError("memory limit must be positive")
        self.memory_limit_bytes = memory_limit_bytes
        self.journal = journal or EventJournal()
        self.last_decision: Optional[PressureDecision] = None

    def evaluate(self, thermal_status: int, memory_used_bytes: int) -> PressureDecision:
        if thermal_status < 0 or thermal_status > 6:
            raise ValueError("thermal status must be in Android range 0..6")
        if memory_used_bytes < 0:
            raise ValueError("memory use cannot be negative")
        memory_ok = memory_used_bytes <= self.memory_limit_bytes
        functional = thermal_status <= 2 and memory_ok
        performance = thermal_status <= 1 and memory_used_bytes <= int(self.memory_limit_bytes * 0.80)
        thermal_fraction = {0: 1.0, 1: 0.9, 2: 0.7, 3: 0.5, 4: 0.35, 5: 0.0, 6: 0.0}[
            thermal_status
        ]
        memory_fraction = 1.0 if memory_ok else 0.0
        fraction = min(thermal_fraction, memory_fraction)
        reason: Optional[str]
        if not memory_ok:
            reason = "MEMORY_PRESSURE"
        elif thermal_status > 2:
            reason = "THERMAL_NOT_ADMITTED_FOR_FUNCTIONAL"
        elif thermal_status > 1:
            reason = "THERMAL_NOT_ADMITTED_FOR_PERFORMANCE"
        else:
            reason = None
        decision = PressureDecision(
            thermal_status=thermal_status,
            memory_used_bytes=memory_used_bytes,
            memory_limit_bytes=self.memory_limit_bytes,
            functional_admitted=functional,
            performance_measurement_admitted=performance,
            secondary_work_fraction=fraction,
            reason=reason,
        )
        self.last_decision = decision
        self.journal.record(
            "pressure",
            "evaluate",
            functional=functional,
            performance=performance,
            reason=reason,
        )
        return decision


class BackendState(str, Enum):
    IDLE = "IDLE"
    READY = "READY"
    ERROR = "ERROR"


class ProcessingBackendTwin:
    """Lifecycle twin for GPU/native initialization and cleanup."""

    def __init__(self, journal: Optional[EventJournal] = None) -> None:
        self.journal = journal or EventJournal()
        self.tracker = ExactOnceResourceTracker(self.journal, "backend")
        self.state = BackendState.IDLE
        self.generation = 0
        self.error: Optional[str] = None

    def initialize(self, gpu_ok: bool = True, native_ok: bool = True) -> bool:
        self.tracker.cleanup_all("backend_reinitialize")
        self.generation += 1
        self.tracker.acquire(f"backend:{self.generation}", "backend", self.generation)
        if not gpu_ok or not native_ok:
            self.error = "GPU_INITIALIZATION_FAILED" if not gpu_ok else "NATIVE_INITIALIZATION_FAILED"
            self.state = BackendState.ERROR
            self.tracker.cleanup_all("backend_initialization_failed")
            self.journal.record("backend", "initialize", "FAIL", error=self.error)
            return False
        self.state = BackendState.READY
        self.error = None
        self.journal.record("backend", "initialize", generation=self.generation)
        return True

    def close(self) -> None:
        self.tracker.cleanup_all("backend_close")
        self.state = BackendState.IDLE
        self.journal.record("backend", "close")


@dataclass(frozen=True)
class ScenarioResult:
    name: str
    seed: int
    status: TwinStatus
    assertions: Mapping[str, bool]
    metrics: Mapping[str, Any]
    events: Sequence[TwinEvent]
    errors: Sequence[str] = ()
    evidence_scope: str = EVIDENCE_SCOPE
    evidence_class: str = "HOST_DIGITAL_TWIN_CONTRACT_ONLY"

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "seed": self.seed,
            "status": self.status.value,
            "evidence_class": self.evidence_class,
            "evidence_scope": self.evidence_scope,
            "assertions": dict(sorted(self.assertions.items())),
            "metrics": dict(sorted(self.metrics.items())),
            "events": [event.to_dict() for event in self.events],
            "errors": list(self.errors),
        }

    def deterministic_sha256(self) -> str:
        payload = json.dumps(
            self.to_dict(), sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()


def _serializable_payload(result: Any) -> Mapping[str, Any]:
    if isinstance(result, Mapping):
        return result
    to_dict = getattr(result, "to_dict", None)
    if callable(to_dict):
        payload = to_dict()
        if isinstance(payload, Mapping):
            return payload
    raise TypeError("scenario result must be a mapping or expose to_dict()")


def write_scenario_result_json(path: os.PathLike[str] | str, result: Any) -> str:
    """Atomically write canonical deterministic JSON and return its SHA-256.

    No current time, random filename, or host identity is included in the file.
    The temporary filename used during the atomic replacement is not evidence.
    """

    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = _serializable_payload(result)
    encoded = (
        json.dumps(payload, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False)
        + "\n"
    ).encode("utf-8")
    with tempfile.NamedTemporaryFile(
        mode="wb", dir=destination.parent, prefix=f".{destination.name}.", delete=False
    ) as temporary:
        temporary.write(encoded)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, destination)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()
    return hashlib.sha256(encoded).hexdigest()


class DigitalTwinGrid:
    """Runs a deterministic cross-component nominal contract scenario."""

    def __init__(self, seed: int = 0) -> None:
        self.seed = int(seed)
        self.random = random.Random(self.seed)

    def run_nominal(self) -> ScenarioResult:
        journal = EventJournal()
        permissions = PermissionTwin(journal)
        permissions.grant("CAMERA")
        permissions.grant("MICROPHONE")

        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"), journal)
        camera.open("Video")
        camera.activate()
        camera.switch_mode("Pro")
        camera.switch_mode("Raw")

        raw = RawOwnershipTwin(capacity=4, journal=journal)
        generation = camera.generation
        frame_order = list(range(8))
        self.random.shuffle(frame_order)
        for frame_id in frame_order:
            if raw.tracker.active_count == raw.capacity:
                owned = raw.tracker.active_tokens[0].split(":")
                raw.release(int(owned[2]), int(owned[1]), "capacity_progress")
            raw.try_acquire(frame_id, generation)
        raw.cleanup_all("nominal_complete")

        encoder = EncoderCadenceTwin(target_fps=25.0, journal=journal)
        encoder.configure()
        for frame_id in range(12):
            pts = frame_id * 40_000
            encoder.submit(frame_id, pts)
            encoder.emit(frame_id, pts, keyframe=frame_id == 0)
        cadence = encoder.finalize()

        control_policy = ControlPolicyTwin(journal=journal)
        control = control_policy.apply(
            ControlRequest(
                target_fps=25.0,
                auto_exposure=True,
                iso=800 + self.random.randint(-50, 50),
                shutter_ns=20_000_000,
                ev=0.25,
                wb_kelvin=5200,
                wb_tint=0.1,
                focus_diopters=1.5,
            )
        )

        colour = ColourProfileLifecycleTwin(journal)
        colour.transition("NDR10_PLUS")
        colour.commit(colour.expected_packet("nominal-calibration-v1"))

        backend = ProcessingBackendTwin(journal)
        backend.initialize()

        profile = ConversionProfile(
            source="RAW",
            destination="NDR10_PLUS",
            width=4096,
            height=3072,
            bit_depth=10,
            bitrate_bps=160_000_000,
        )
        conversion = ConversionPipelineTwin(journal)
        conversion.start(profile)
        metadata = {
            "sensor_timestamp_ns": 1,
            "iso": control.iso,
            "exposure_ns": control.shutter_ns,
            "wb_gains": control.wb_gains,
        }
        for frame_id in range(4):
            conversion.ingest(frame_id, frame_id * 40_000, metadata)
        conversion.complete()

        media = MediaStoreTwin(free_bytes=1_000_000_000, journal=journal)
        media.begin("nominal-clip", 100_000_000, require_sidecar=True)
        media.write_main("nominal-clip")
        media.write_sidecar("nominal-clip")
        media.commit("nominal-clip")

        playback = PlaybackSchedulerTwin(journal=journal)
        for frame_id in range(4):
            playback.enqueue(frame_id, frame_id * 40_000)
        for frame_id in range(4):
            playback.present_next(1_000_000 + frame_id * 40_000)

        pressure = PressureTwin(memory_limit_bytes=1_000_000_000, journal=journal)
        pressure_decision = pressure.evaluate(thermal_status=0, memory_used_bytes=250_000_000)

        backend.close()
        camera.close()
        assertions = {
            "camera_cleanup_exact_once": camera.tracker.exact_once_clean,
            "raw_cleanup_exact_once": raw.tracker.exact_once_clean,
            "raw_capacity_respected": raw.ownership_safe,
            "encoder_cadence_contract": cadence.contract_pass,
            "encoder_cleanup_exact_once": encoder.tracker.exact_once_clean,
            "control_invariants": control.invariant_pass,
            "colour_profile_committed": colour.committed is not None,
            "backend_cleanup_exact_once": backend.tracker.exact_once_clean,
            "conversion_completed": conversion.published,
            "conversion_cleanup_exact_once": conversion.tracker.exact_once_clean,
            "mediastore_atomic": (
                "nominal-clip" in media.published_items and not media.has_corrupt_publication
            ),
            "mediastore_cleanup_exact_once": media.tracker.exact_once_clean,
            "playback_contract": len(playback.rendered) == 4 and not playback.dropped,
            "pressure_admitted": pressure_decision.performance_measurement_admitted,
        }
        errors = tuple(name for name, passed in assertions.items() if not passed)
        status = TwinStatus.PASS if not errors else TwinStatus.FAIL
        return ScenarioResult(
            name="neo_qa_hypergrid_nominal",
            seed=self.seed,
            status=status,
            assertions=assertions,
            metrics={
                "event_count": len(journal.events),
                "raw_peak_owned": raw.peak_owned,
                "raw_capacity": raw.capacity,
                "encoder_submitted": cadence.submitted,
                "encoder_encoded": cadence.encoded,
                "encoder_delivery_ratio": cadence.frame_delivery_ratio,
                "playback_rendered": len(playback.rendered),
                "playback_dropped": len(playback.dropped),
            },
            events=journal.events,
            errors=errors,
        )


__all__ = [
    "BackendState",
    "CadenceReport",
    "CameraLifecycleTwin",
    "CameraState",
    "ColourProfileLifecycleTwin",
    "ColourProfilePacket",
    "ContractViolation",
    "ControlLimits",
    "ControlPolicyTwin",
    "ControlRequest",
    "ControlResult",
    "ConversionPipelineTwin",
    "ConversionProfile",
    "ConversionState",
    "DigitalTwinGrid",
    "EVIDENCE_SCOPE",
    "EncoderCadenceTwin",
    "EncoderState",
    "EventJournal",
    "ExactOnceResourceTracker",
    "ExactOnceViolation",
    "MediaStoreTwin",
    "PermissionState",
    "PermissionTwin",
    "PlaybackSchedulerTwin",
    "PlaybackState",
    "PressureDecision",
    "PressureTwin",
    "ProcessingBackendTwin",
    "RawOwnershipTwin",
    "ScenarioResult",
    "TwinEvent",
    "TwinStatus",
    "write_scenario_result_json",
]
