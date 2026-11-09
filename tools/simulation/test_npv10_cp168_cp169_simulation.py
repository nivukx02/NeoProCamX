import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from npv10_cp168_cp169_simulation import (
    FULL_SENSOR_HEIGHT,
    FULL_SENSOR_WIDTH,
    MAX_PENDING_FRAMES,
    RapidRestartCoordinator,
    STORAGE_BIT_DEPTH,
    TARGET_DURATION_SECONDS,
    TARGET_FPS,
    packed_p010_10_bytes,
    simulate_sustained_full_sensor_pipeline,
)


class Npv10Cp168Cp169SimulationTest(unittest.TestCase):
    def test_cp168_twenty_generations_retire_exactly_before_restart(self) -> None:
        coordinator = RapidRestartCoordinator()
        for cycle in range(20):
            generation = coordinator.start()
            # Include the zero-frame cancel case and increasingly interleaved short captures.
            for _ in range(cycle % 5):
                generation.admit()
            while generation.pending_frames:
                generation.retire()
            generation.establish_boundary()
            coordinator.finish(generation)

        self.assertIsNone(coordinator.active)
        self.assertEqual(list(range(1, 21)), coordinator.completed_generations)

    def test_cp168_stale_generation_cannot_retire_fresh_session(self) -> None:
        coordinator = RapidRestartCoordinator()
        old = coordinator.start()
        old.establish_boundary()
        coordinator.finish(old)
        fresh = coordinator.start()

        with self.assertRaisesRegex(RuntimeError, "stale generation"):
            coordinator.finish(old)

        self.assertIs(fresh, coordinator.active)

    def test_cp168_restart_is_blocked_until_pending_ownership_retires(self) -> None:
        coordinator = RapidRestartCoordinator()
        generation = coordinator.start()
        generation.admit()

        with self.assertRaisesRegex(RuntimeError, "cannot overtake"):
            generation.establish_boundary()
        with self.assertRaisesRegex(RuntimeError, "not fully retired"):
            coordinator.start()

        generation.retire()
        generation.establish_boundary()
        coordinator.finish(generation)
        self.assertIsNotNone(coordinator.start())

    def test_cp169_exact_full_sensor_contract_is_not_reduced(self) -> None:
        stored_bytes = packed_p010_10_bytes(FULL_SENSOR_WIDTH, FULL_SENSOR_HEIGHT)
        self.assertEqual(23_592_960, stored_bytes)
        self.assertEqual(589_824_000, stored_bytes * TARGET_FPS)
        self.assertEqual(10, STORAGE_BIT_DEPTH)
        self.assertEqual(60, TARGET_DURATION_SECONDS)
        self.assertEqual(4, MAX_PENDING_FRAMES)

    def test_cp169_later_measured_service_times_are_stable_for_sixty_seconds(self) -> None:
        result = simulate_sustained_full_sensor_pipeline(
            packing_time_ms=10.671230697819315,
            writer_time_ms=19.823759386292835,
        )

        self.assertTrue(result.stable)
        self.assertEqual(1500, result.frames_requested)
        self.assertEqual(1500, result.frames_admitted)
        self.assertEqual(0, result.rejected_frames)
        self.assertIsNone(result.first_rejected_frame)
        self.assertLessEqual(result.maximum_pending_frames, MAX_PENDING_FRAMES)
        self.assertGreater(result.writer_capacity_bytes_per_second, 1_000_000_000.0)

    def test_cp169_historical_under_rate_writer_fails_strict_capacity(self) -> None:
        result = simulate_sustained_full_sensor_pipeline(
            packing_time_ms=10.671230697819315,
            writer_time_ms=41.67551164705882,
        )

        self.assertFalse(result.stable)
        self.assertLess(
            result.writer_capacity_bytes_per_second,
            result.required_bytes_per_second,
        )
        self.assertIsNotNone(result.first_rejected_frame)
        self.assertGreater(result.rejected_frames, 0)

    def test_cp169_non_finite_or_non_positive_stage_measurements_fail_closed(self) -> None:
        for invalid in (float("nan"), float("inf"), float("-inf"), 0.0, -1.0):
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(ValueError, "stage times must be positive"):
                    simulate_sustained_full_sensor_pipeline(
                        packing_time_ms=invalid,
                        writer_time_ms=19.823759386292835,
                    )
                with self.assertRaisesRegex(ValueError, "stage times must be positive"):
                    simulate_sustained_full_sensor_pipeline(
                        packing_time_ms=10.671230697819315,
                        writer_time_ms=invalid,
                    )

    def test_production_source_preserves_native_row_dispatch_and_exact_start_stop_barriers(self) -> None:
        source = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).read_text(encoding="utf-8")
        policy = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10InFlightBufferPolicy.kt"
        ).read_text(encoding="utf-8")
        native = (ROOT / "app/src/main/cpp/npv10_raw_native.cpp").read_text(encoding="utf-8")
        dispatch = source.split("private val p010PackingExecutor = ThreadPoolExecutor(", 1)[1].split(
            "private val reservationExecutor", 1
        )[0]
        start = source.split("fun start(config: Npv10SessionConfig): File", 1)[1].split(
            "fun preparePhysicalReservationAsync", 1
        )[0]
        stop = source.split("fun stop(): File?", 1)[1].split(
            "fun resetCancelledStartError", 1
        )[0]
        retirement = source.split("private fun retireStoppedSessionOwnership(", 1)[1].split(
            "fun resetCancelledStartError", 1
        )[0]

        # Native row workers now provide the parallelism. Multiple Java submitters would only
        # contend for the shared native engine; keep its prestarted, single-owner dispatch lane.
        self.assertIn("const val P010_PACKING_DISPATCH_WORKERS = 1", source)
        self.assertEqual(2, dispatch.count("P010_PACKING_DISPATCH_WORKERS"))
        self.assertIn("prestartAllCoreThreads()", dispatch)
        self.assertIn("kCaptureSafeHelperThreadCount = 5u", native)
        self.assertIn("submissionLock(submissionMutex_, std::defer_lock)", native)
        self.assertIn("return completedHelpers_ == activeHelperCount;", native)
        self.assertIn("check(p010PackingInFlight.get() == 0)", start)
        self.assertIn("check(preparedP010Commits.isEmpty())", start)
        self.assertLess(start.index("check(p010PackingInFlight.get() == 0)"), start.index("nextP010IngressSequence = 0L"))
        self.assertLess(stop.index("awaitP010PackingDrain()"), stop.index("activeWriter.closePreservingPhysicalReservationWhenEmpty()"))
        normal_return = stop.rindex("return activeOutput")
        finally_index = stop.index("} finally {", normal_return)
        retirement_call = stop.index("retireStoppedSessionOwnership(", finally_index)
        self.assertLess(normal_return, finally_index)
        self.assertLess(finally_index, retirement_call)
        self.assertIn("if (ownsWriter) writer = null", retirement)
        self.assertIn("if (ownsOutput) outputFile = null", retirement)
        self.assertIn(
            "producerBoundaryGateRef.compareAndSet(expectedProducerBoundaryGate, null)",
            retirement,
        )
        self.assertIn("currentWriter !== expectedWriter", retirement)
        self.assertIn("currentOutput !== expectedOutput", retirement)
        # Preserve twelve full-sensor packet leases, bounded canonical fallbacks, and NLC2 framing
        # headroom. The separately bounded NIV99 path retains all four canonical buffers.
        self.assertIn("MAX_PENDING_FRAMES = 12", policy)
        self.assertIn("READER_RESERVED_IMAGES = 6", policy)
        self.assertIn("MAX_DIRECT_BUFFER_BYTES = 343L * 1024L * 1024L", policy)
        self.assertIn("NIV99_MAX_DIRECT_BUFFER_BYTES = 256L * 1024L * 1024L", policy)
        self.assertIn("NIV99_CANONICAL_BUFFER_COUNT = 4", policy)
        self.assertIn("MAX_CONCURRENT_CANONICAL_FALLBACKS = 2", policy)


if __name__ == "__main__":
    unittest.main()
