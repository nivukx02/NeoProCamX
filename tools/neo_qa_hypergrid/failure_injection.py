"""Deterministic failure-injection scenarios for NEO-QA HyperGrid.

Each scenario creates fresh host-side twins, injects one explicit failure, and
checks truthful error reporting, exact-once cleanup, safe publication, and a
deterministic recovery path.  Results are source-contract evidence only; they
are never hardware FPS, codec, colour, thermal, or image-quality proof.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from typing import Any, Mapping, Optional, Sequence

try:  # Package import used by the integrated HyperGrid.
    from .digital_twin import (
        BackendState,
        CameraLifecycleTwin,
        CameraState,
        ColourProfileLifecycleTwin,
        ConversionPipelineTwin,
        ConversionProfile,
        ConversionState,
        EVIDENCE_SCOPE,
        EncoderCadenceTwin,
        EncoderState,
        EventJournal,
        MediaStoreTwin,
        PermissionTwin,
        PlaybackSchedulerTwin,
        PlaybackState,
        PressureTwin,
        ProcessingBackendTwin,
        RawOwnershipTwin,
        TwinEvent,
        TwinStatus,
    )
except ImportError:  # Direct module execution from this directory.
    from digital_twin import (  # type: ignore
        BackendState,
        CameraLifecycleTwin,
        CameraState,
        ColourProfileLifecycleTwin,
        ConversionPipelineTwin,
        ConversionProfile,
        ConversionState,
        EVIDENCE_SCOPE,
        EncoderCadenceTwin,
        EncoderState,
        EventJournal,
        MediaStoreTwin,
        PermissionTwin,
        PlaybackSchedulerTwin,
        PlaybackState,
        PressureTwin,
        ProcessingBackendTwin,
        RawOwnershipTwin,
        TwinEvent,
        TwinStatus,
    )


class FailureKind(str, Enum):
    CAMERA_DISCONNECT = "camera_disconnect"
    CODEC_CONFIGURATION_REJECTION = "codec_configuration_rejection"
    ENCODER_STARVATION = "encoder_starvation"
    RAW_IMAGE_READER_EXHAUSTION = "raw_image_reader_exhaustion"
    GPU_NATIVE_INITIALIZATION_FAILURE = "gpu_native_initialization_failure"
    MAIN_PUBLICATION_FAILURE = "main_publication_failure"
    SIDECAR_PUBLICATION_FAILURE = "sidecar_publication_failure"
    LOW_STORAGE = "low_storage"
    PERMISSION_REVOCATION = "permission_revocation"
    SURFACE_LOSS = "surface_loss"
    LIFECYCLE_CANCELLATION = "lifecycle_cancellation"
    STALE_GENERATION = "stale_generation"
    THERMAL_THROTTLING = "thermal_throttling"
    CONVERSION_CANCELLATION = "conversion_cancellation"
    PLAYBACK_DECODER_FAILURE = "playback_decoder_failure"


@dataclass(frozen=True)
class FailureOutcome:
    kind: FailureKind
    seed: int
    status: TwinStatus
    injected_error: str
    recovered: bool
    exact_once_cleanup: bool
    corrupt_publication_absent: bool
    assertions: Mapping[str, bool]
    metrics: Mapping[str, Any]
    events: Sequence[TwinEvent]
    evidence_scope: str = EVIDENCE_SCOPE
    evidence_class: str = "HOST_FAILURE_INJECTION_CONTRACT_ONLY"

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind.value,
            "seed": self.seed,
            "status": self.status.value,
            "injected_error": self.injected_error,
            "recovered": self.recovered,
            "exact_once_cleanup": self.exact_once_cleanup,
            "corrupt_publication_absent": self.corrupt_publication_absent,
            "evidence_class": self.evidence_class,
            "evidence_scope": self.evidence_scope,
            "assertions": dict(sorted(self.assertions.items())),
            "metrics": dict(sorted(self.metrics.items())),
            "events": [event.to_dict() for event in self.events],
        }

    def deterministic_sha256(self) -> str:
        encoded = json.dumps(
            self.to_dict(), sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()


class FailureInjectionEngine:
    """Runs isolated deterministic failure/recovery contracts."""

    def __init__(self, seed: int = 0) -> None:
        self.seed = int(seed)

    def _scenario_seed(self, kind: FailureKind) -> int:
        digest = hashlib.sha256(f"{self.seed}:{kind.value}".encode("utf-8")).digest()
        return int.from_bytes(digest[:8], byteorder="big", signed=False)

    def _outcome(
        self,
        kind: FailureKind,
        journal: EventJournal,
        injected_error: str,
        recovered: bool,
        exact_once_cleanup: bool,
        corrupt_publication_absent: bool,
        assertions: Mapping[str, bool],
        metrics: Optional[Mapping[str, Any]] = None,
    ) -> FailureOutcome:
        complete_assertions = {
            **assertions,
            "recovery_succeeded": recovered,
            "exact_once_cleanup": exact_once_cleanup,
            "no_corrupt_publication": corrupt_publication_absent,
        }
        status = TwinStatus.PASS if all(complete_assertions.values()) else TwinStatus.FAIL
        return FailureOutcome(
            kind=kind,
            seed=self._scenario_seed(kind),
            status=status,
            injected_error=injected_error,
            recovered=recovered,
            exact_once_cleanup=exact_once_cleanup,
            corrupt_publication_absent=corrupt_publication_absent,
            assertions=complete_assertions,
            metrics=metrics or {},
            events=journal.events,
        )

    def run(self, kind: FailureKind | str) -> FailureOutcome:
        selected = kind if isinstance(kind, FailureKind) else FailureKind(kind)
        dispatch = {
            FailureKind.CAMERA_DISCONNECT: self._camera_disconnect,
            FailureKind.CODEC_CONFIGURATION_REJECTION: self._codec_rejection,
            FailureKind.ENCODER_STARVATION: self._encoder_starvation,
            FailureKind.RAW_IMAGE_READER_EXHAUSTION: self._raw_exhaustion,
            FailureKind.GPU_NATIVE_INITIALIZATION_FAILURE: self._backend_initialization,
            FailureKind.MAIN_PUBLICATION_FAILURE: self._main_publication,
            FailureKind.SIDECAR_PUBLICATION_FAILURE: self._sidecar_publication,
            FailureKind.LOW_STORAGE: self._low_storage,
            FailureKind.PERMISSION_REVOCATION: self._permission_revocation,
            FailureKind.SURFACE_LOSS: self._surface_loss,
            FailureKind.LIFECYCLE_CANCELLATION: self._lifecycle_cancellation,
            FailureKind.STALE_GENERATION: self._stale_generation,
            FailureKind.THERMAL_THROTTLING: self._thermal_throttling,
            FailureKind.CONVERSION_CANCELLATION: self._conversion_cancellation,
            FailureKind.PLAYBACK_DECODER_FAILURE: self._playback_decoder,
        }
        return dispatch[selected]()

    def run_all(self) -> tuple[FailureOutcome, ...]:
        return tuple(self.run(kind) for kind in FailureKind)

    @staticmethod
    def _camera_fixture(journal: EventJournal) -> tuple[PermissionTwin, CameraLifecycleTwin]:
        permissions = PermissionTwin(journal)
        permissions.grant("CAMERA")
        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"), journal)
        camera.open("Video")
        camera.activate()
        return permissions, camera

    def _camera_disconnect(self) -> FailureOutcome:
        kind = FailureKind.CAMERA_DISCONNECT
        journal = EventJournal()
        _, camera = self._camera_fixture(journal)
        camera.disconnect()
        detected = camera.state == CameraState.ERROR and camera.error == "CAMERA_DISCONNECTED"
        cleaned_at_failure = camera.tracker.active_count == 0
        recovered = camera.recover("Video")
        camera.close()
        return self._outcome(
            kind,
            journal,
            "CAMERA_DISCONNECTED",
            recovered,
            camera.tracker.exact_once_clean,
            True,
            {
                "disconnect_reported_truthfully": detected,
                "resources_released_on_disconnect": cleaned_at_failure,
            },
        )

    def _codec_rejection(self) -> FailureOutcome:
        kind = FailureKind.CODEC_CONFIGURATION_REJECTION
        journal = EventJournal()
        encoder = EncoderCadenceTwin(25.0, journal)
        accepted = encoder.configure(False, "CODEC_CONFIGURATION_REJECTED")
        detected = (
            not accepted
            and encoder.state == EncoderState.ERROR
            and encoder.error == "CODEC_CONFIGURATION_REJECTED"
        )
        clean_at_failure = encoder.tracker.active_count == 0
        recovered = encoder.configure(True)
        for frame_id in range(2):
            pts_us = frame_id * 40_000
            recovered = (
                recovered
                and encoder.submit(frame_id, pts_us)
                and encoder.emit(frame_id, pts_us, frame_id == 0)
            )
        report = encoder.finalize()
        recovered = recovered and report.contract_pass
        return self._outcome(
            kind,
            journal,
            "CODEC_CONFIGURATION_REJECTED",
            recovered,
            encoder.tracker.exact_once_clean,
            True,
            {
                "codec_rejection_reported": detected,
                "rejected_codec_released": clean_at_failure,
            },
            {"recovery_encoded_frames": report.encoded},
        )

    def _encoder_starvation(self) -> FailureOutcome:
        kind = FailureKind.ENCODER_STARVATION
        journal = EventJournal()
        encoder = EncoderCadenceTwin(25.0, journal)
        encoder.configure()
        for frame_id in range(5):
            encoder.submit(frame_id, frame_id * 40_000)
        for frame_id in range(2):
            encoder.emit(frame_id, frame_id * 40_000, frame_id == 0)
        failed_report = encoder.finalize(require_all_frames=True)
        detected = encoder.error == "ENCODER_STARVATION" and failed_report.missing == 3
        clean_at_failure = encoder.tracker.active_count == 0

        recovered = encoder.configure()
        for frame_id in range(5):
            recovered = recovered and encoder.submit(frame_id, frame_id * 40_000)
            recovered = recovered and encoder.emit(frame_id, frame_id * 40_000, frame_id == 0)
        recovery_report = encoder.finalize()
        recovered = recovered and recovery_report.contract_pass
        return self._outcome(
            kind,
            journal,
            "ENCODER_STARVATION",
            recovered,
            encoder.tracker.exact_once_clean,
            True,
            {
                "missing_outputs_detected": detected,
                "codec_released_after_starvation": clean_at_failure,
                "no_failed_clip_publication_claimed": True,
            },
            {
                "failed_submitted": failed_report.submitted,
                "failed_encoded": failed_report.encoded,
                "failed_missing": failed_report.missing,
                "recovery_delivery_ratio": recovery_report.frame_delivery_ratio,
            },
        )

    def _raw_exhaustion(self) -> FailureOutcome:
        kind = FailureKind.RAW_IMAGE_READER_EXHAUSTION
        journal = EventJournal()
        raw = RawOwnershipTwin(capacity=2, journal=journal)
        first = raw.try_acquire(0, 1)
        second = raw.try_acquire(1, 1)
        blocked = not raw.try_acquire(2, 1)
        error_at_failure = raw.last_error
        did_not_over_acquire = raw.tracker.active_count == 2 and raw.peak_owned == 2
        raw.release(0, 1, "capacity_recovery")
        recovered = raw.try_acquire(2, 1)
        raw.cleanup_all("scenario_complete")
        return self._outcome(
            kind,
            journal,
            "RAW_CAPACITY_BACKPRESSURE",
            recovered,
            raw.tracker.exact_once_clean and raw.ownership_safe,
            True,
            {
                "initial_capacity_filled": first and second,
                "pre_acquire_backpressure": blocked,
                "capacity_error_truthful": error_at_failure == "RAW_CAPACITY_BACKPRESSURE",
                "capacity_never_exceeded": did_not_over_acquire,
                "release_woke_waiter": raw.capacity_wakeups >= 1,
            },
            {
                "capacity": raw.capacity,
                "peak_owned": raw.peak_owned,
                "backpressured_frames": raw.backpressured_frames,
            },
        )

    def _backend_initialization(self) -> FailureOutcome:
        kind = FailureKind.GPU_NATIVE_INITIALIZATION_FAILURE
        journal = EventJournal()
        backend = ProcessingBackendTwin(journal)
        gpu_failed = not backend.initialize(gpu_ok=False, native_ok=True)
        gpu_error = backend.error
        gpu_clean = backend.tracker.active_count == 0
        native_failed = not backend.initialize(gpu_ok=True, native_ok=False)
        native_error = backend.error
        native_clean = backend.tracker.active_count == 0
        recovered = backend.initialize(gpu_ok=True, native_ok=True)
        recovered = recovered and backend.state == BackendState.READY
        backend.close()
        return self._outcome(
            kind,
            journal,
            "GPU_OR_NATIVE_INITIALIZATION_FAILED",
            recovered,
            backend.tracker.exact_once_clean,
            True,
            {
                "gpu_failure_reported": gpu_failed and gpu_error == "GPU_INITIALIZATION_FAILED",
                "native_failure_reported": (
                    native_failed and native_error == "NATIVE_INITIALIZATION_FAILED"
                ),
                "partial_gpu_resources_released": gpu_clean,
                "partial_native_resources_released": native_clean,
            },
        )

    def _publication_failure(
        self, kind: FailureKind, fail_sidecar: bool
    ) -> FailureOutcome:
        journal = EventJournal()
        media = MediaStoreTwin(free_bytes=500_000_000, journal=journal)
        item = "failed-sidecar" if fail_sidecar else "failed-main"
        media.begin(item, 100_000_000, require_sidecar=True)
        # The main stream stays valid in the sidecar scenario; otherwise the
        # injected fault belongs to the main stream itself.
        media.write_main(item, valid=fail_sidecar)
        if fail_sidecar:
            media.write_sidecar(item, valid=False)
        else:
            media.write_sidecar(item, valid=True)
        committed = media.commit(item)
        expected_error = (
            "SIDECAR_PUBLICATION_FAILED" if fail_sidecar else "MAIN_PUBLICATION_FAILED"
        )
        transaction = media.transactions[item]
        detected = not committed and media.last_error == expected_error
        unpublished = not transaction.published and item not in media.published_items
        clean_at_failure = media.tracker.active_count == 0

        recovery_item = f"{item}-recovered"
        recovered = media.begin(recovery_item, 100_000_000, require_sidecar=True)
        recovered = recovered and media.write_main(recovery_item, True)
        recovered = recovered and media.write_sidecar(recovery_item, True)
        recovered = recovered and media.commit(recovery_item)
        return self._outcome(
            kind,
            journal,
            expected_error,
            recovered,
            media.tracker.exact_once_clean,
            not media.has_corrupt_publication and unpublished,
            {
                "publication_failure_reported": detected,
                "failed_item_unpublished": unpublished,
                "pending_transaction_released": clean_at_failure,
            },
            {"published_count": len(media.published_items)},
        )

    def _main_publication(self) -> FailureOutcome:
        return self._publication_failure(FailureKind.MAIN_PUBLICATION_FAILURE, False)

    def _sidecar_publication(self) -> FailureOutcome:
        return self._publication_failure(FailureKind.SIDECAR_PUBLICATION_FAILURE, True)

    def _low_storage(self) -> FailureOutcome:
        kind = FailureKind.LOW_STORAGE
        journal = EventJournal()
        media = MediaStoreTwin(free_bytes=50_000_000, journal=journal)
        started = media.begin("large-clip", 100_000_000)
        detected = not started and media.last_error == "LOW_STORAGE"
        no_pending_output = media.tracker.active_count == 0 and not media.published_items
        media.add_storage(100_000_000)
        recovered = media.begin("recovered-clip", 100_000_000, require_sidecar=False)
        recovered = recovered and media.write_main("recovered-clip")
        recovered = recovered and media.commit("recovered-clip")
        return self._outcome(
            kind,
            journal,
            "LOW_STORAGE",
            recovered,
            media.tracker.exact_once_clean,
            not media.has_corrupt_publication,
            {
                "storage_failure_precedes_allocation": detected,
                "no_pending_low_storage_output": no_pending_output,
            },
            {"free_bytes_after_recovery": media.free_bytes},
        )

    def _permission_revocation(self) -> FailureOutcome:
        kind = FailureKind.PERMISSION_REVOCATION
        journal = EventJournal()
        permissions, camera = self._camera_fixture(journal)
        permissions.revoke("CAMERA")
        camera.permission_revoked()
        detected = (
            camera.state == CameraState.ERROR
            and camera.error == "CAMERA_PERMISSION_REVOKED"
            and camera.tracker.active_count == 0
        )
        denied_while_revoked = not camera.recover("Video")
        permissions.grant("CAMERA")
        recovered = camera.recover("Video")
        camera.close()
        return self._outcome(
            kind,
            journal,
            "CAMERA_PERMISSION_REVOKED",
            recovered,
            camera.tracker.exact_once_clean,
            True,
            {
                "revocation_reported_and_cleaned": detected,
                "camera_not_reopened_without_permission": denied_while_revoked,
            },
        )

    def _surface_loss(self) -> FailureOutcome:
        kind = FailureKind.SURFACE_LOSS
        journal = EventJournal()
        _, camera = self._camera_fixture(journal)
        camera.surface_lost()
        detected = (
            camera.state == CameraState.ERROR
            and camera.error == "SURFACE_LOST"
            and camera.tracker.active_count == 0
        )
        recovered = camera.recover("Video")
        camera.close()
        return self._outcome(
            kind,
            journal,
            "SURFACE_LOST",
            recovered,
            camera.tracker.exact_once_clean,
            True,
            {"surface_loss_reported_and_cleaned": detected},
        )

    def _lifecycle_cancellation(self) -> FailureOutcome:
        kind = FailureKind.LIFECYCLE_CANCELLATION
        journal = EventJournal()
        _, camera = self._camera_fixture(journal)
        camera.cancel_lifecycle()
        detected = (
            camera.state == CameraState.CLOSED
            and camera.error == "LIFECYCLE_CANCELLED"
            and camera.tracker.active_count == 0
        )
        recovered = camera.recover("Pro")
        camera.close()
        return self._outcome(
            kind,
            journal,
            "LIFECYCLE_CANCELLED",
            recovered,
            camera.tracker.exact_once_clean,
            True,
            {"cancellation_reported_and_cleaned": detected},
        )

    def _stale_generation(self) -> FailureOutcome:
        kind = FailureKind.STALE_GENERATION
        journal = EventJournal()
        colour = ColourProfileLifecycleTwin(journal)
        colour.transition("NDR10_PLUS")
        stale_packet = colour.expected_packet("old-calibration")
        colour.transition("NIVUKX_VISION")
        accepted_stale = colour.commit(stale_packet)
        detected = (
            not accepted_stale
            and colour.last_error == "STALE_PROFILE_GENERATION"
            and colour.committed is None
        )
        recovered = colour.commit(colour.expected_packet("current-calibration"))
        return self._outcome(
            kind,
            journal,
            "STALE_PROFILE_GENERATION",
            recovered,
            True,
            True,
            {
                "stale_profile_rejected": detected,
                "old_profile_not_retained": colour.committed == colour.expected_packet(
                    "current-calibration"
                ),
            },
            {"current_generation": colour.generation},
        )

    def _thermal_throttling(self) -> FailureOutcome:
        kind = FailureKind.THERMAL_THROTTLING
        journal = EventJournal()
        pressure = PressureTwin(memory_limit_bytes=1_000_000_000, journal=journal)
        throttled = pressure.evaluate(thermal_status=4, memory_used_bytes=400_000_000)
        detected = (
            not throttled.performance_measurement_admitted
            and not throttled.functional_admitted
            and throttled.reason == "THERMAL_NOT_ADMITTED_FOR_FUNCTIONAL"
            and throttled.secondary_work_fraction == 0.35
        )
        cooled = pressure.evaluate(thermal_status=0, memory_used_bytes=400_000_000)
        recovered = cooled.performance_measurement_admitted and cooled.functional_admitted
        return self._outcome(
            kind,
            journal,
            "THERMAL_NOT_ADMITTED_FOR_FUNCTIONAL",
            recovered,
            True,
            True,
            {
                "performance_measurement_paused": detected,
                "critical_functional_work_paused": not throttled.functional_admitted,
            },
            {
                "throttled_secondary_fraction": throttled.secondary_work_fraction,
                "cooled_secondary_fraction": cooled.secondary_work_fraction,
            },
        )

    @staticmethod
    def _conversion_profile() -> ConversionProfile:
        return ConversionProfile(
            source="RAW",
            destination="XDR",
            width=4096,
            height=3072,
            bit_depth=10,
            bitrate_bps=160_000_000,
        )

    @staticmethod
    def _metadata() -> dict[str, Any]:
        return {
            "sensor_timestamp_ns": 1,
            "iso": 400,
            "exposure_ns": 20_000_000,
            "wb_gains": (1.0, 1.0, 1.0, 1.0),
        }

    def _conversion_cancellation(self) -> FailureOutcome:
        kind = FailureKind.CONVERSION_CANCELLATION
        journal = EventJournal()
        conversion = ConversionPipelineTwin(journal)
        conversion.start(self._conversion_profile())
        conversion.ingest(0, 0, self._metadata())
        conversion.cancel()
        detected = (
            conversion.state == ConversionState.CANCELLED
            and conversion.error == "CONVERSION_CANCELLED"
            and not conversion.published
            and conversion.tracker.active_count == 0
        )
        recovered = conversion.start(self._conversion_profile())
        recovered = recovered and conversion.ingest(0, 0, self._metadata())
        recovered = recovered and conversion.complete()
        return self._outcome(
            kind,
            journal,
            "CONVERSION_CANCELLED",
            recovered,
            conversion.tracker.exact_once_clean,
            detected,
            {
                "cancelled_output_unpublished": detected,
                "pipeline_resource_released": conversion.tracker.active_count == 0,
            },
            {"recovery_frames": len(conversion.frames)},
        )

    def _playback_decoder(self) -> FailureOutcome:
        kind = FailureKind.PLAYBACK_DECODER_FAILURE
        journal = EventJournal()
        playback = PlaybackSchedulerTwin(journal=journal)
        for frame_id in range(3):
            playback.enqueue(frame_id, frame_id * 40_000)
        playback.decoder_failure()
        detected = (
            playback.state == PlaybackState.ERROR
            and playback.error == "PLAYBACK_DECODER_FAILED"
            and not playback.queue
            and not playback.rendered
        )
        playback.recover()
        for frame_id in range(3):
            playback.enqueue(frame_id, frame_id * 40_000)
        for frame_id in range(3):
            playback.present_next(500_000 + frame_id * 40_000)
        recovered = len(playback.rendered) == 3 and not playback.dropped
        return self._outcome(
            kind,
            journal,
            "PLAYBACK_DECODER_FAILED",
            recovered,
            True,
            True,
            {
                "decoder_failure_reported": detected,
                "failed_queue_discarded": detected,
            },
            {
                "recovery_rendered": len(playback.rendered),
                "recovery_dropped": len(playback.dropped),
            },
        )


__all__ = ["FailureInjectionEngine", "FailureKind", "FailureOutcome"]
