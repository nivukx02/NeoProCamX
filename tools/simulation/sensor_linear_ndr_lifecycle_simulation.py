#!/usr/bin/env python3
"""Deterministic host model for sensor-linear NDR recorder ownership.

This model is intentionally limited to concurrency and identity invariants. It cannot prove
Camera2, Vulkan, P010, codec, display, thermal, or physical frame-cadence behaviour.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from enum import Enum
from threading import Event, RLock
from typing import Callable


class SubmissionStatus(str, Enum):
    ACCEPTED_PENDING = "ACCEPTED_PENDING"
    ACCEPTED_COMPLETE = "ACCEPTED_COMPLETE"
    RETRY_PENDING = "RETRY_PENDING"
    RETRY_PREVIEW_BACKPRESSURE = "RETRY_PREVIEW_BACKPRESSURE"
    FAILED = "FAILED"


@dataclass(frozen=True)
class SubmissionResult:
    """Typed processor result matching the recorder's completionPending contract.

    RETRY_PENDING is deliberately ambiguous without this second bit: the exact retry Image may be
    waiting only for preview-ring capacity, or an older accepted native identity may still need to be
    polled. Tests must state that ownership fact explicitly instead of inferring it from the status.
    """

    status: SubmissionStatus
    completion_pending: bool = False


@dataclass
class OwnedFrame:
    sequence: int
    sensor_timestamp_ns: int = 0
    close_count: int = 0
    submission_count: int = 0

    def close(self) -> None:
        self.close_count += 1
        if self.close_count != 1:
            raise AssertionError(f"frame {self.sequence} closed {self.close_count} times")


@dataclass(frozen=True)
class PublicationIdentity:
    ingress_generation: int
    session_generation: int


class SessionPublicationGate:
    """Final-boundary identity gate shared by preview, encoder, and analytics."""

    CHANNELS = frozenset({"preview", "encoder", "analytics"})

    def __init__(self, ingress_generation: int, session_generation: int) -> None:
        self.ingress_generation = ingress_generation
        self.session_generation = session_generation
        self.active = True
        self.published: list[tuple[str, PublicationIdentity]] = []

    def advance_session(self, session_generation: int) -> None:
        self.session_generation = session_generation

    def retire(self) -> None:
        self.active = False

    def publish(self, channel: str, identity: PublicationIdentity) -> bool:
        if channel not in self.CHANNELS:
            raise ValueError(f"unknown publication channel: {channel}")
        current = (
            self.active
            and identity.ingress_generation == self.ingress_generation
            and identity.session_generation == self.session_generation
        )
        if current:
            self.published.append((channel, identity))
        return current


@dataclass(frozen=True, eq=False)
class CallbackIngressToken:
    """Reference-identity proof sampled at Camera2 callback entry."""

    generation: int
    session_generation: int
    writer_session: int = 0
    admission_epoch: int = 0
    retired_timestamp_floor_ns: int = 0
    cutover_timestamp_floor_ns: int = 0


class NdrCallbackLineageModel:
    """Exact NDR Image/result admission across start, stop, drain, and restart."""

    def __init__(self) -> None:
        self.generation = 0
        self.session_generation = -1
        self.active = False
        self.accepting = False
        self.published: CallbackIngressToken | None = None
        self.pending_images: set[int] = set()
        self.unmatched_results: set[int] = set()
        self.latest_result_timestamp_ns: int | None = None
        self.owned_frames = 0
        self.owned_high_watermark = 0
        self.cutover_retired_images = 0
        self.paired_timestamps: list[int] = []

    def observe(self) -> CallbackIngressToken | None:
        return self.published

    def start(
        self,
        session_generation: int,
        cutover_timestamp_floor_ns: int = 0,
    ) -> CallbackIngressToken:
        if self.active or self.pending_images:
            raise RuntimeError("NDR start requires terminal ownership")
        self.generation += 1
        self.session_generation = session_generation
        self.active = True
        self.accepting = True
        self.unmatched_results.clear()
        self.latest_result_timestamp_ns = None
        self.owned_frames = 0
        self.owned_high_watermark = 0
        self.cutover_retired_images = 0
        self.paired_timestamps.clear()
        token = CallbackIngressToken(
            self.generation,
            session_generation,
            cutover_timestamp_floor_ns=cutover_timestamp_floor_ns,
        )
        self.published = token
        return token

    def accept_image(self, observed: CallbackIngressToken | None, timestamp_ns: int) -> bool:
        current = (
            self.active
            and self.accepting
            and observed is not None
            and observed is self.published
            and observed.generation == self.generation
            and observed.session_generation == self.session_generation
        )
        if not current:
            return False
        self.owned_frames += 1
        self.owned_high_watermark = max(self.owned_high_watermark, self.owned_frames)
        if timestamp_ns <= observed.cutover_timestamp_floor_ns:
            self.owned_frames -= 1
            self.cutover_retired_images += 1
            return False
        if timestamp_ns in self.unmatched_results:
            self.unmatched_results.remove(timestamp_ns)
            self.owned_frames -= 1
            self.paired_timestamps.append(timestamp_ns)
        else:
            self.pending_images.add(timestamp_ns)
        return current

    def signal_stop(self) -> None:
        """Nonblocking close publication; accepting may remain true until lock acquisition."""
        self.published = None

    def commit_stop(self) -> None:
        self.published = None
        self.accepting = False

    def accept_result(
        self,
        observed: CallbackIngressToken | None,
        timestamp_ns: int,
        session_generation: int,
    ) -> bool:
        if not self.active or session_generation != self.session_generation:
            return False
        owns_open = (
            observed is not None
            and observed is self.published
            and observed.generation == self.generation
            and observed.session_generation == self.session_generation
            and self.accepting
        )
        may_complete_unpublished_exact = self.published is None and (
            observed is None
            or (
                observed.generation == self.generation
                and observed.session_generation == self.session_generation
            )
        )
        if owns_open and observed is not None and (
            timestamp_ns <= observed.cutover_timestamp_floor_ns
        ):
            return False
        if timestamp_ns in self.pending_images:
            if not owns_open and not may_complete_unpublished_exact:
                return False
            self.pending_images.remove(timestamp_ns)
            self.owned_frames -= 1
            self.paired_timestamps.append(timestamp_ns)
            self.latest_result_timestamp_ns = timestamp_ns
            return True
        if not owns_open:
            return False
        self.unmatched_results.add(timestamp_ns)
        self.latest_result_timestamp_ns = timestamp_ns
        return True

    def finish(self) -> None:
        if self.pending_images or self.owned_frames:
            raise RuntimeError("cannot finish with owned NDR Images")
        self.published = None
        self.accepting = False
        self.active = False
        self.unmatched_results.clear()


class RawResultLineageModel:
    """RAW metadata identity across pause/resume and complete writer restart."""

    def __init__(self, session_generation: int) -> None:
        self.session_generation = session_generation
        self.writer_session = 0
        self.admission_epoch = 0
        self.retired_timestamp_floor_ns = 0
        self.first_accepted_timestamp_ns = 0
        self.last_accepted_timestamp_ns = 0
        self.published: CallbackIngressToken | None = None
        self.result_identity: CallbackIngressToken | None = None
        self.pending: dict[int, CallbackIngressToken] = {}
        self.recent_results: set[int] = set()
        self.latest_result_timestamp_ns: int | None = None

    def _new_token(self) -> CallbackIngressToken:
        self.admission_epoch += 1
        return CallbackIngressToken(
            generation=self.writer_session,
            session_generation=self.session_generation,
            writer_session=self.writer_session,
            admission_epoch=self.admission_epoch,
            retired_timestamp_floor_ns=self.retired_timestamp_floor_ns,
        )

    def start(self) -> CallbackIngressToken:
        if self.published is not None or self.result_identity is not None or self.pending:
            raise RuntimeError("RAW start requires finalized prior result ownership")
        self.writer_session += 1
        self.first_accepted_timestamp_ns = 0
        self.last_accepted_timestamp_ns = 0
        token = self._new_token()
        self.result_identity = token
        self.published = token
        return token

    def observe_result(self) -> CallbackIngressToken | None:
        return self.result_identity

    def accept_image(self, timestamp_ns: int) -> bool:
        token = self.published
        if token is None or timestamp_ns <= token.retired_timestamp_floor_ns:
            return False
        self.pending[timestamp_ns] = token
        self.last_accepted_timestamp_ns = max(self.last_accepted_timestamp_ns, timestamp_ns)
        if self.first_accepted_timestamp_ns == 0:
            self.first_accepted_timestamp_ns = timestamp_ns
            self.recent_results = {value for value in self.recent_results if value >= timestamp_ns}
            if (
                self.latest_result_timestamp_ns is not None
                and self.latest_result_timestamp_ns < timestamp_ns
            ):
                self.latest_result_timestamp_ns = None
        return True

    def pause(self) -> None:
        self.published = None

    def resume(self) -> CallbackIngressToken:
        previous = self.result_identity
        if previous is None:
            raise RuntimeError("RAW resume requires retained writer identity")
        token = self._new_token()
        if token.writer_session != previous.writer_session:
            raise AssertionError("pause/resume changed writer session")
        self.result_identity = token
        self.published = token
        return token

    def accept_result(self, observed: CallbackIngressToken | None, timestamp_ns: int) -> bool:
        published = self.published
        current_open = (
            observed is not None
            and observed is self.result_identity
            and observed is published
            and timestamp_ns > observed.retired_timestamp_floor_ns
            and (
                self.first_accepted_timestamp_ns == 0
                or timestamp_ns >= self.first_accepted_timestamp_ns
            )
        )
        pending = self.pending.get(timestamp_ns)
        exact_owned = (
            pending is not None
            and observed is not None
            and pending.writer_session == observed.writer_session
            and pending.session_generation == observed.session_generation
        )
        if not current_open and not exact_owned:
            return False
        self.recent_results.add(timestamp_ns)
        if pending is self.result_identity or (current_open and pending is None):
            self.latest_result_timestamp_ns = timestamp_ns
        return True

    def finish(self) -> None:
        self.retired_timestamp_floor_ns = max(
            self.retired_timestamp_floor_ns,
            self.last_accepted_timestamp_ns,
            self.latest_result_timestamp_ns or 0,
        )
        self.published = None
        self.result_identity = None
        self.pending.clear()
        self.recent_results.clear()
        self.latest_result_timestamp_ns = None


class SensorLinearNdrLifecycleModel:
    """Single-worker ownership model with atomic terminal handoff.

    External callers only publish stop/discard/failure/destroy requests. The worker owns the
    dequeued frame, retry slot, native tail, lifecycle completion, and final destroy publication.
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self.generation = 0
        self.session_generation = -1
        self.active = False
        self.accepting = False
        self.discard_requested = False
        self.failure_requested = False
        self.destroy_requested = False
        self.destroyed = False
        self.worker_scheduled = False
        self.worker_running = False
        self.native_pending = False
        self.queue: deque[OwnedFrame] = deque()
        self.in_flight: OwnedFrame | None = None
        self.retry_frame: OwnedFrame | None = None
        self.owned_frames = 0
        self.finish_count = 0
        self.failure_callback_count = 0
        self.stop_latch = Event()

    def physically_quiescent(self) -> bool:
        with self._lock:
            return self._physically_quiescent_locked()

    def _physically_quiescent_locked(self) -> bool:
        return (
            not self.active
            and not self.worker_running
            and not self.worker_scheduled
            and not self.native_pending
            and self.in_flight is None
            and self.retry_frame is None
            and not self.queue
            and self.owned_frames == 0
        )

    def start(self, session_generation: int) -> int:
        with self._lock:
            if self.destroyed or self.destroy_requested or not self._physically_quiescent_locked():
                raise RuntimeError("new generation requires physical quiescence")
            self.generation += 1
            self.session_generation = session_generation
            self.active = True
            self.accepting = True
            self.discard_requested = False
            self.failure_requested = False
            self.stop_latch = Event()
            return self.generation

    def enqueue(self, frame: OwnedFrame) -> bool:
        with self._lock:
            if not self.active or not self.accepting or self.discard_requested:
                frame.close()
                return False
            self.queue.append(frame)
            self.owned_frames += 1
            self._ensure_worker_locked()
            return True

    def request_stop(self, discard: bool) -> None:
        with self._lock:
            if not self.active:
                return
            self.accepting = False
            self.discard_requested = self.discard_requested or discard
            self._ensure_worker_locked()

    def request_failure(self) -> None:
        with self._lock:
            self._request_failure_locked()

    def _request_failure_locked(self) -> bool:
        if not self.active or self.failure_requested:
            return False
        self.failure_requested = True
        self.failure_callback_count += 1
        self.accepting = False
        self.discard_requested = True
        self._ensure_worker_locked()
        return True

    def request_retry_timeout(self, expected_frame: OwnedFrame) -> bool:
        """Fail one exact retained retry without inventing native completion ownership."""
        with self._lock:
            if not self.active or self.retry_frame is not expected_frame:
                return False
            self.retry_frame = None
            self._close_owned_locked(expected_frame)
            self._request_failure_locked()
            self._discard_unsubmitted_locked()
            return True

    def request_destroy(self) -> None:
        with self._lock:
            self.destroy_requested = True
            if self.active:
                self.accepting = False
                self.discard_requested = True
                self._ensure_worker_locked()
            else:
                self._publish_destroy_if_quiescent_locked()

    def _ensure_worker_locked(self) -> None:
        if self.active and not self.worker_scheduled:
            self.worker_scheduled = True

    def _close_owned_locked(self, frame: OwnedFrame) -> None:
        frame.close()
        self.owned_frames -= 1
        if self.owned_frames < 0:
            raise AssertionError("owned frame budget underflow")

    def _discard_unsubmitted_locked(self) -> None:
        if self.retry_frame is not None:
            frame, self.retry_frame = self.retry_frame, None
            self._close_owned_locked(frame)
        while self.queue:
            self._close_owned_locked(self.queue.popleft())

    def _can_finish_locked(self) -> bool:
        return (
            self.active
            and not self.accepting
            and not self.native_pending
            and self.in_flight is None
            and self.retry_frame is None
            and not self.queue
            and self.owned_frames == 0
        )

    def _finish_locked(self) -> None:
        if not self._can_finish_locked():
            raise AssertionError("logical finish attempted before physical ownership retired")
        self.active = False
        self.session_generation = -1
        self.finish_count += 1
        self.stop_latch.set()

    def _work_required_locked(self) -> bool:
        return self.active and (
            self.discard_requested
            or self.native_pending
            or self.retry_frame is not None
            or bool(self.queue)
            or not self.accepting
        )

    def _publish_destroy_if_quiescent_locked(self) -> None:
        if self.destroy_requested and self._physically_quiescent_locked():
            self.destroyed = True

    def run_worker_once(
        self,
        submit: Callable[[OwnedFrame], SubmissionResult],
        poll_native: Callable[[], bool] = lambda: True,
        before_submit_arrived: Event | None = None,
        allow_submit: Event | None = None,
        before_handoff_arrived: Event | None = None,
        allow_handoff: Event | None = None,
    ) -> None:
        """Run one bounded worker turn with optional deterministic race latches."""
        with self._lock:
            if not self.worker_scheduled or self.worker_running:
                raise RuntimeError("worker turn is not scheduled")
            self.worker_running = True
            if self.discard_requested:
                self._discard_unsubmitted_locked()
            if self.retry_frame is not None:
                self.in_flight, self.retry_frame = self.retry_frame, None
            elif self.queue:
                self.in_flight = self.queue.popleft()
            else:
                self.in_flight = None
            frame = self.in_flight
            should_poll = frame is None and self.native_pending

        try:
            if frame is not None:
                if before_submit_arrived is not None:
                    before_submit_arrived.set()
                if allow_submit is not None and not allow_submit.wait(2.0):
                    raise TimeoutError("submit latch was not released")
                with self._lock:
                    if self.discard_requested:
                        self.in_flight = None
                        self._close_owned_locked(frame)
                        frame = None
                if frame is not None:
                    frame.submission_count += 1
                    submission = submit(frame)
                    status = submission.status
                    with self._lock:
                        self.in_flight = None
                        if status is SubmissionStatus.ACCEPTED_PENDING:
                            if not submission.completion_pending:
                                raise AssertionError("ACCEPTED_PENDING requires a native tail")
                            self._close_owned_locked(frame)
                            self.native_pending = True
                        elif status is SubmissionStatus.ACCEPTED_COMPLETE:
                            if submission.completion_pending:
                                raise AssertionError("ACCEPTED_COMPLETE cannot retain a native tail")
                            self._close_owned_locked(frame)
                        elif status is SubmissionStatus.RETRY_PENDING:
                            self.native_pending = submission.completion_pending
                            if self.discard_requested:
                                self._close_owned_locked(frame)
                            else:
                                self.retry_frame = frame
                        elif status is SubmissionStatus.RETRY_PREVIEW_BACKPRESSURE:
                            if submission.completion_pending:
                                raise AssertionError(
                                    "preview-only backpressure cannot retain a native tail"
                                )
                            self.native_pending = False
                            if self.discard_requested:
                                self._close_owned_locked(frame)
                            else:
                                self.retry_frame = frame
                        else:
                            self._close_owned_locked(frame)
                            self.failure_requested = True
                            self.accepting = False
                            self.discard_requested = True
            elif should_poll:
                completed = poll_native()
                with self._lock:
                    if completed:
                        self.native_pending = False

            with self._lock:
                if self.discard_requested:
                    self._discard_unsubmitted_locked()
                if self._can_finish_locked():
                    self._finish_locked()
        finally:
            if before_handoff_arrived is not None:
                before_handoff_arrived.set()
            if allow_handoff is not None and not allow_handoff.wait(2.0):
                raise TimeoutError("handoff latch was not released")
            # Clearing the scheduling token and deciding whether terminal work remains are one
            # locked handoff. A concurrent stop cannot lose its wake-up in this interval.
            with self._lock:
                self.worker_running = False
                self.worker_scheduled = False
                if self._can_finish_locked():
                    self._finish_locked()
                if self._work_required_locked():
                    self.worker_scheduled = True
                self._publish_destroy_if_quiescent_locked()
