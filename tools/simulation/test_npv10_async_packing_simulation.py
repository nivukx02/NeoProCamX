import itertools
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from npv10_async_packing_simulation import (
    AsyncPackingLifecycle,
    CrcPath,
    resolve_crc_path,
)


ROOT = HERE.parents[1]


class Npv10AsyncPackingSimulationTest(unittest.TestCase):
    def test_every_four_frame_completion_permutation_commits_in_ingress_order(self) -> None:
        for order in itertools.permutations(range(4)):
            model = AsyncPackingLifecycle()
            for _ in range(4):
                model.admit()
            for sequence in order:
                model.complete_packing(sequence)
            model.drain_writer()
            self.assertEqual([0, 1, 2, 3], model.written_sequences, order)
            self.assertEqual(0, model.pending_frames, order)

    def test_packing_rejection_publishes_tombstone_and_does_not_strand_later_frame(self) -> None:
        model = AsyncPackingLifecycle()
        first = model.admit()
        second = model.admit()
        model.complete_packing(second.sequence)
        model.reject_packing_dispatch(first.sequence)
        model.drain_writer()

        self.assertEqual([second.sequence], model.written_sequences)
        self.assertEqual([first.sequence], model.dropped_sequences)
        self.assertEqual(1, first.image_closes)
        self.assertEqual(1, first.permit_releases)
        self.assertEqual(1, second.packet_releases)

    def test_completion_construction_failure_releases_packet_and_keeps_sequence_contiguous(self) -> None:
        model = AsyncPackingLifecycle()
        failed = model.admit()
        following = model.admit()
        model.complete_packing(
            failed.sequence,
            completion_construction_raises=True,
        )
        model.complete_packing(following.sequence)
        model.drain_writer()

        self.assertEqual(1, failed.image_closes)
        self.assertEqual(1, failed.packet_releases)
        self.assertEqual(1, failed.permit_releases)
        self.assertEqual([following.sequence], model.written_sequences)

    def test_pause_resume_marker_is_queued_after_all_pre_pause_completions(self) -> None:
        model = AsyncPackingLifecycle()
        first = model.admit()
        second = model.admit()
        model.pause()
        self.assertFalse(model.resume_after_packing_drain())
        model.complete_packing(second.sequence)
        self.assertFalse(model.resume_after_packing_drain())
        model.complete_packing(first.sequence)
        self.assertTrue(model.resume_after_packing_drain())
        model.drain_writer()

        self.assertEqual([first.sequence, second.sequence], model.written_sequences)
        self.assertGreater(
            model.events.index("cadence-marker-commit"),
            model.events.index(f"writer-commit:{second.sequence}"),
        )

    def test_stop_waits_for_packing_and_writer_retirement_before_close(self) -> None:
        model = AsyncPackingLifecycle()
        first = model.admit()
        second = model.admit()
        model.begin_finalization()
        self.assertFalse(model.close_after_full_drain())
        model.complete_packing(second.sequence)
        self.assertFalse(model.close_after_full_drain())
        model.complete_packing(first.sequence)
        self.assertFalse(model.close_after_full_drain())
        model.drain_writer()
        self.assertTrue(model.close_after_full_drain())

    def test_partial_writer_failure_poison_prevents_every_later_commit(self) -> None:
        model = AsyncPackingLifecycle()
        for _ in range(3):
            model.admit()
        for sequence in range(3):
            model.complete_packing(sequence)
        model.writer_step(partial_write_failure=True)
        model.drain_writer()

        self.assertTrue(model.writer_poisoned)
        self.assertEqual([], model.written_sequences)
        self.assertEqual([0, 1, 2], model.dropped_sequences)
        self.assertEqual(0, model.pending_frames)

    def test_packet_pool_return_failure_cannot_skip_pending_or_permit_cleanup(self) -> None:
        model = AsyncPackingLifecycle()
        owner = model.admit(packet_pool_release_raises=True)
        model.complete_packing(owner.sequence)
        model.drain_writer()

        self.assertEqual(1, owner.packet_releases)
        self.assertEqual(1, owner.pending_retires)
        self.assertEqual(1, owner.permit_releases)
        self.assertEqual(0, model.pending_frames)
        self.assertTrue(model.writer_poisoned)

    def test_crc_provenance_distinguishes_both_native_paths_from_java_fallback(self) -> None:
        self.assertEqual(
            CrcPath.CAMERA_PLANE_FUSED_NATIVE,
            resolve_crc_path(camera_plane_crc=True, canonical_packet_crc=False),
        )
        self.assertEqual(
            CrcPath.CANONICAL_PACKET_NATIVE,
            resolve_crc_path(camera_plane_crc=False, canonical_packet_crc=True),
        )
        self.assertEqual(
            CrcPath.JAVA_PACKET_FALLBACK,
            resolve_crc_path(camera_plane_crc=False, canonical_packet_crc=False),
        )
        self.assertTrue(CrcPath.CAMERA_PLANE_FUSED_NATIVE.native_fused)
        self.assertTrue(CrcPath.CANONICAL_PACKET_NATIVE.native_fused)
        self.assertFalse(CrcPath.JAVA_PACKET_FALLBACK.native_fused)

    def test_production_lifecycle_has_all_three_packing_drain_barriers(self) -> None:
        source = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).read_text(encoding="utf-8")
        resume = source.split("fun resume(): Boolean", 1)[1].split(
            "fun beginFinalization(): Boolean", 1
        )[0]
        accepted_drain = source.split("fun awaitAcceptedFrameDrain(): Boolean", 1)[1].split(
            "fun stop(): File?", 1
        )[0]
        stop = source.split("fun stop(): File?", 1)[1].split(
            "fun resetCancelledStartError", 1
        )[0]

        self.assertLess(
            resume.index("awaitP010PackingDrain()"),
            resume.index("markTimestampCadenceDiscontinuity()"),
        )
        self.assertLess(
            accepted_drain.index("awaitP010PackingDrain()"),
            accepted_drain.index("writerExecutor.submit<Boolean>"),
        )
        self.assertLess(
            stop.index("awaitP010PackingDrain()"),
            stop.index("writerExecutor.submit<Boolean>"),
        )


if __name__ == "__main__":
    unittest.main()
