from __future__ import annotations

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from runtime_feature_simulation import (  # noqa: E402
    CameraOpenGateModel,
    ModeMutation,
    OverflowPolicy,
    coalesce_mode_mutations,
    estimate_conversion_pipeline,
    estimate_scaled_processing_ms,
    raw_retained_image_budget,
    resolve_raw_preview_extent,
    resolve_safe_raw_queue_capacity,
    resolve_thermal_plan,
    simulate_muxer,
    simulate_stage_queue,
)


class RuntimeFeatureSimulationTest(unittest.TestCase):
    def test_preview_latest_policy_stays_current_without_unbounded_backlog(self) -> None:
        result = simulate_stage_queue(
            source_fps=25.0,
            service_time_ms=55.0,
            duration_seconds=4.0,
            queue_capacity=1,
            overflow_policy=OverflowPolicy.PREVIEW_LATEST,
        )
        self.assertFalse(result.terminal_failure)
        self.assertLessEqual(result.max_queue_depth, 1)
        self.assertGreater(result.replaced_or_rejected, 0)
        self.assertLess(result.max_latency_ms, 120.0)

    def test_recording_overflow_fails_closed_instead_of_hiding_a_drop(self) -> None:
        result = simulate_stage_queue(
            source_fps=25.0,
            service_time_ms=60.0,
            duration_seconds=12.0,
            queue_capacity=8,
            overflow_policy=OverflowPolicy.RECORDING_FAIL_CLOSED,
        )
        self.assertTrue(result.terminal_failure)
        self.assertEqual(1, result.replaced_or_rejected)

    def test_realtime_stage_meets_25fps_without_replacement(self) -> None:
        result = simulate_stage_queue(
            source_fps=25.0,
            service_time_ms=32.0,
            duration_seconds=12.0,
            queue_capacity=1,
            overflow_policy=OverflowPolicy.PREVIEW_LATEST,
        )
        self.assertFalse(result.terminal_failure)
        self.assertEqual(0, result.replaced_or_rejected)
        self.assertGreaterEqual(result.achieved_fps, 24.9)

    def test_raw_queue_capacity_never_consumes_reader_reserve(self) -> None:
        for strict in (False, True):
            for reader_images in range(2, 17):
                for active_writes in range(0, 3):
                    try:
                        capacity = resolve_safe_raw_queue_capacity(
                            requested_queue_capacity=32,
                            reader_max_images=reader_images,
                            reserved_active_writes=active_writes,
                            strict_high_end=strict,
                        )
                    except ValueError:
                        continue
                    retained_budget = raw_retained_image_budget(reader_images, strict)
                    self.assertLessEqual(capacity + active_writes, retained_budget)
                    self.assertLess(capacity + active_writes, reader_images)

    def test_small_reader_uses_one_queue_slot_not_an_impossible_minimum_of_two(self) -> None:
        self.assertEqual(
            1,
            resolve_safe_raw_queue_capacity(
                requested_queue_capacity=5,
                reader_max_images=3,
                reserved_active_writes=1,
                strict_high_end=False,
            ),
        )

    def test_three_slot_strict_reader_preserves_minimum_functional_ownership(self) -> None:
        self.assertEqual(
            1,
            resolve_safe_raw_queue_capacity(
                requested_queue_capacity=5,
                reader_max_images=3,
                reserved_active_writes=1,
                strict_high_end=True,
            ),
        )
        with self.assertRaises(ValueError):
            resolve_safe_raw_queue_capacity(
                requested_queue_capacity=5,
                reader_max_images=2,
                reserved_active_writes=1,
                strict_high_end=True,
            )

    def test_idle_raw_extent_is_display_sized_but_recording_is_full_sensor(self) -> None:
        idle = resolve_raw_preview_extent(4096, 3072, recording_active=False)
        recording = resolve_raw_preview_extent(4096, 3072, recording_active=True)
        self.assertEqual((1364, 1024, 3), (idle.width, idle.height, idle.decimate))
        self.assertEqual((4096, 3072, 1), (recording.width, recording.height, recording.decimate))
        self.assertTrue(recording.recording_full_sensor)

    def test_bounded_idle_raw_estimate_fits_25fps_budget(self) -> None:
        idle = resolve_raw_preview_extent(4096, 3072, recording_active=False)
        estimated_ms = estimate_scaled_processing_ms(
            full_width=4096,
            full_height=3072,
            full_processing_ms=235.0,
            extent=idle,
        )
        self.assertLess(estimated_ms, 40.0)

    def test_thermal_policy_is_monotonic_and_emergency_endpoint_is_reachable(self) -> None:
        idle = [resolve_thermal_plan(level, recording_active=False) for level in range(6)]
        recording = [resolve_thermal_plan(level, recording_active=True) for level in range(6)]
        self.assertEqual(sorted((p.analysis_fps_cap for p in idle), reverse=True), [p.analysis_fps_cap for p in idle])
        self.assertEqual(sorted((p.preview_long_edge for p in idle), reverse=True), [p.preview_long_edge for p in idle])
        self.assertEqual(854, idle[-1].preview_long_edge)
        self.assertEqual(5, idle[-1].analysis_fps_cap)
        self.assertTrue(recording[-1].stop_recording)

    def test_stale_camera_open_callback_cannot_release_new_attempt_permit(self) -> None:
        gate = CameraOpenGateModel()
        first_generation = gate.begin_lifecycle()
        first = gate.new_attempt(first_generation)
        self.assertIsNotNone(first)
        self.assertTrue(gate.claim(first))
        gate.invalidate()
        self.assertTrue(gate.release(first))
        second_generation = gate.begin_lifecycle()
        second = gate.new_attempt(second_generation)
        self.assertIsNotNone(second)
        self.assertTrue(gate.claim(second))
        self.assertFalse(gate.release(first))
        self.assertTrue(gate.release(second))

    def test_mode_switch_burst_flushes_one_strongest_transaction(self) -> None:
        resolved = coalesce_mode_mutations(
            (
                ModeMutation("GEOMETRY", "STANDARD"),
                ModeMutation("NONE", "FORCE_SESSION"),
                ModeMutation("LUT", "STANDARD"),
            )
        )
        self.assertEqual(ModeMutation("LUT", "FORCE_SESSION"), resolved)

    def test_muxer_keeps_up_when_storage_exceeds_encoded_payload(self) -> None:
        result = simulate_muxer(
            bitrate_bps=240_000_000,
            fps=25.0,
            storage_megabytes_per_second=40.0,
            duration_seconds=30.0,
        )
        self.assertFalse(result.terminal_failure)
        self.assertEqual(0, result.replaced_or_rejected)

    def test_muxer_reports_terminal_failure_when_storage_cannot_sustain_clip(self) -> None:
        result = simulate_muxer(
            bitrate_bps=240_000_000,
            fps=25.0,
            storage_megabytes_per_second=20.0,
            duration_seconds=30.0,
        )
        self.assertTrue(result.terminal_failure)
        self.assertEqual(1, result.replaced_or_rejected)

    def test_parallel_conversion_reduces_wall_time_without_changing_frame_count(self) -> None:
        estimate = estimate_conversion_pipeline(
            frames=300,
            decode_ms=8.0,
            process_ms=22.0,
            encode_ms=10.0,
            process_workers=2,
        )
        self.assertGreater(estimate.speedup, 2.5)
        self.assertLess(estimate.pipelined_ms, estimate.sequential_ms)


class RuntimeProductionContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_native_idle_raw_and_ndr_are_bounded_while_recording_is_full_sensor(self) -> None:
        source = self.read("app/src/main/cpp/vulkan_bridge.cpp")
        extent = source.split("const auto resolveProcessingExtent", 1)[1].split(
            "resolveProcessingExtent();", 1
        )[0]
        self.assertIn("if (state->rawRecordingDemosaicActive) return", extent)
        self.assertIn("kRawIdlePreviewLongEdge", extent)
        self.assertIn("previewTargetLongEdge = kRawIdlePreviewLongEdge", extent)

    def test_raw_queue_source_has_no_two_slot_floor_that_breaks_reader_budget(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoBackpressurePolicy.kt"
        )
        capacity = source.split("fun resolveSafeQueueCapacity", 1)[1].split(
            "fun targetHeadroomSlots", 1
        )[0]
        self.assertIn("retainedBudget - safeWriteReserve", capacity)
        self.assertIn("require(maxQueuedImages >= 1)", capacity)
        self.assertNotIn("coerceAtLeast(ABSOLUTE_MIN_QUEUE_CAPACITY)", capacity)

    def test_thermal_emergency_uses_emergency_preview_and_analysis_limits(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/ThermalRuntimePolicy.kt"
        )
        fps = source.split("private fun baseFpsForSeverity", 1)[1].split(
            "private fun previewEdgeForSeverity", 1
        )[0]
        edge = source.split("private fun previewEdgeForSeverity", 1)[1].split(
            "private fun labelForSeverity", 1
        )[0]
        self.assertIn("3 -> ANALYSIS_FPS_THROTTLED", fps)
        self.assertIn("5 -> ANALYSIS_FPS_EMERGENCY", fps)
        self.assertIn("3 -> PREVIEW_EDGE_THROTTLED", edge)
        self.assertIn("5 -> PREVIEW_EDGE_EMERGENCY", edge)

    def test_active_strict_recording_stops_instead_of_downgrading_at_emergency(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        policy = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/ThermalRuntimePolicy.kt"
        )
        block = source.split("private fun maybeApplyThermalGuard", 1)[1].split(
            "private fun applyThermalBitrateReduction", 1
        )[0]
        self.assertIn(
            "strictHighEnd && recordingActive && severity >= 5",
            policy,
        )
        self.assertIn("recordingStopRestartLifecycle.isStarting", block)
        self.assertIn("gpuRecordState.value.isRecordingSessionActive()", block)
        self.assertIn("if (thermalDecision.shouldStopStrictHighEndRecording)", block)
        self.assertIn("stopRecording()", block)

    def test_oversized_muxer_packet_is_rejected_before_direct_buffer_allocation(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMuxerSampleQueue.kt"
        )
        enqueue = source.split("fun enqueue(\n        trackIndex: Int,\n        source: ByteBuffer,\n        offset", 1)[1].split(
            "/** Recycle a packet buffer", 1
        )[0]
        bounds_index = enqueue.index("size.toLong() > pendingBufferByteCapacity")
        allocation_index = enqueue.index("obtainBuffer(size)")
        self.assertGreaterEqual(bounds_index, 0)
        self.assertLess(bounds_index, allocation_index)

    def test_mode_switch_and_camera_open_generation_ownership_remain_present(self) -> None:
        mode = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/ModeSwitchBatchFlushScheduler.kt"
        )
        camera = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/camera/CameraOpenAttemptGate.kt"
        )
        self.assertIn("if (expectedGeneration != generation)", mode)
        self.assertIn("permitOwnerAttemptId.compareAndSet(attempt.id, NO_ATTEMPT)", camera)

    def test_muxer_strict_overflow_is_terminal_not_a_hidden_drop(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMuxerSampleQueue.kt"
        )
        self.assertIn("STRICT_BACKPRESSURE_TIMEOUT", source)
        self.assertIn("queueState = QueueState.FAILED", source)
        self.assertIn("totalStrictBackpressureTimeouts.incrementAndGet()", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
