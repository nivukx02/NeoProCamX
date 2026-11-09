"""Deterministic lifecycle model for NPV10's two-worker Image-owned P010 packing stage.

This model does not simulate image quality. It exhaustively checks the concurrency invariants that
are otherwise scheduler-dependent on device: ingress ordering, tombstones, fail-stop writing, and
exactly-once ownership retirement across pause/finalization races.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class CrcPath(Enum):
    CAMERA_PLANE_FUSED_NATIVE = True
    CANONICAL_PACKET_NATIVE = True
    JAVA_PACKET_FALLBACK = False

    @property
    def native_fused(self) -> bool:
        return bool(self.value)


@dataclass
class FrameOwnership:
    sequence: int
    image_closes: int = 0
    packet_releases: int = 0
    permit_releases: int = 0
    pending_retires: int = 0
    packet_owned: bool = False
    packet_pool_release_raises: bool = False

    def close_image(self) -> None:
        if self.image_closes:
            raise AssertionError(f"image {self.sequence} closed more than once")
        self.image_closes += 1

    def release_packet(self) -> None:
        if not self.packet_owned:
            return
        if self.packet_releases:
            raise AssertionError(f"packet {self.sequence} released more than once")
        self.packet_releases += 1
        self.packet_owned = False
        if self.packet_pool_release_raises:
            raise RuntimeError("injected packet-pool return failure")

    def release_permit(self) -> None:
        if self.permit_releases:
            raise AssertionError(f"permit {self.sequence} released more than once")
        self.permit_releases += 1

    def retire_pending(self) -> None:
        if self.pending_retires:
            raise AssertionError(f"pending frame {self.sequence} retired more than once")
        self.pending_retires += 1


@dataclass(frozen=True)
class Completion:
    sequence: int
    preparation_failed: bool


class AsyncPackingLifecycle:
    """Small fail-closed reference for the production packing/writer coordinator."""

    def __init__(self) -> None:
        self.next_ingress = 0
        self.next_commit = 0
        self.packing_in_flight = 0
        self.pending_frames = 0
        self.paused = False
        self.finalizing = False
        self.writer_poisoned = False
        self.completions: dict[int, Completion] = {}
        self.writer_queue: list[Completion | str] = []
        self.ownership: dict[int, FrameOwnership] = {}
        self.written_sequences: list[int] = []
        self.dropped_sequences: list[int] = []
        self.events: list[str] = []

    def admit(self, *, packet_pool_release_raises: bool = False) -> FrameOwnership:
        if self.paused or self.finalizing:
            raise RuntimeError("ingress is closed")
        sequence = self.next_ingress
        self.next_ingress += 1
        self.packing_in_flight += 1
        self.pending_frames += 1
        owner = FrameOwnership(
            sequence=sequence,
            packet_pool_release_raises=packet_pool_release_raises,
        )
        self.ownership[sequence] = owner
        self.events.append(f"admit:{sequence}")
        return owner

    def complete_packing(
        self,
        sequence: int,
        *,
        preparation_failed: bool = False,
        completion_construction_raises: bool = False,
    ) -> None:
        owner = self.ownership[sequence]
        try:
            if not preparation_failed:
                owner.packet_owned = True
            if completion_construction_raises:
                # A packet that was prepared but could not be transferred into a completion is
                # returned immediately. A failure tombstone still owns permit/pending retirement.
                try:
                    owner.release_packet()
                finally:
                    preparation_failed = True
            self._publish(Completion(sequence, preparation_failed))
        finally:
            owner.close_image()
            self.packing_in_flight -= 1
            if self.packing_in_flight < 0:
                raise AssertionError("packing in-flight underflow")
            self.events.append(f"packing-finished:{sequence}")

    def reject_packing_dispatch(self, sequence: int) -> None:
        owner = self.ownership[sequence]
        try:
            owner.close_image()
            self._publish(Completion(sequence, preparation_failed=True))
        finally:
            self.packing_in_flight -= 1
            self.events.append(f"packing-rejected:{sequence}")

    def _publish(self, completion: Completion) -> None:
        if completion.sequence < self.next_commit or completion.sequence in self.completions:
            raise AssertionError(f"duplicate completion {completion.sequence}")
        self.completions[completion.sequence] = completion
        while self.next_commit in self.completions:
            ready = self.completions.pop(self.next_commit)
            self.writer_queue.append(ready)
            self.events.append(f"writer-enqueue:{ready.sequence}")
            self.next_commit += 1

    def pause(self) -> None:
        self.paused = True
        self.events.append("pause")

    def resume_after_packing_drain(self) -> bool:
        if self.packing_in_flight or self.completions or self.next_commit != self.next_ingress:
            return False
        self.writer_queue.append("cadence-marker")
        self.events.append("cadence-marker-enqueue")
        self.paused = False
        return True

    def begin_finalization(self) -> None:
        self.paused = True
        self.finalizing = True
        self.events.append("finalize-request")

    def close_after_full_drain(self) -> bool:
        if self.packing_in_flight or self.completions or self.writer_queue or self.pending_frames:
            return False
        if self.next_commit != self.next_ingress:
            raise AssertionError("finalization observed an ingress sequence gap")
        self.events.append("writer-close")
        return True

    def writer_step(self, *, partial_write_failure: bool = False) -> None:
        item = self.writer_queue.pop(0)
        if item == "cadence-marker":
            self.events.append("cadence-marker-commit")
            return
        assert isinstance(item, Completion)
        owner = self.ownership[item.sequence]
        try:
            if item.preparation_failed or self.writer_poisoned:
                self.dropped_sequences.append(item.sequence)
            elif partial_write_failure:
                self.writer_poisoned = True
                self.dropped_sequences.append(item.sequence)
                self.events.append(f"writer-poison:{item.sequence}")
            else:
                self.written_sequences.append(item.sequence)
                self.events.append(f"writer-commit:{item.sequence}")
        finally:
            packet_failure: BaseException | None = None
            try:
                owner.release_packet()
            except BaseException as failure:  # cleanup must continue even when pool return fails
                packet_failure = failure
                self.writer_poisoned = True
            finally:
                try:
                    owner.retire_pending()
                    self.pending_frames -= 1
                finally:
                    owner.release_permit()
            if packet_failure is not None:
                self.events.append(f"packet-release-failure:{item.sequence}")

    def drain_writer(self) -> None:
        while self.writer_queue:
            self.writer_step()


def resolve_crc_path(*, camera_plane_crc: bool, canonical_packet_crc: bool) -> CrcPath:
    if camera_plane_crc:
        return CrcPath.CAMERA_PLANE_FUSED_NATIVE
    if canonical_packet_crc:
        return CrcPath.CANONICAL_PACKET_NATIVE
    return CrcPath.JAVA_PACKET_FALLBACK
