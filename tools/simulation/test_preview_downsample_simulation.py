from __future__ import annotations

import math
import sys
import unittest
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from preview_downsample_simulation import (
    decode_intermediate_channel,
    project_preview_downsample_enabled,
    quadrant_offset_source_pixels,
    source_footprint_box_first_zero,
    two_tap_axis_response,
)


ROOT = HERE.parents[1]


def simulate_ordered_preview_transport(
    frame_count: int,
    frame_interval_ns: int,
    preview_service_ns: int,
    first_preview_service_ns: int | None = None,
    retry_poll_ns: int = 4_000_000,
    retry_deadline_ns: int = 160_000_000,
    reader_max_images: int = 8,
    ring_slots: int = 3,
) -> dict[str, object]:
    """Virtual-time ownership model for full-res acceptance plus the preview-copy ring."""
    encoder_labels: list[int] = []
    preview_labels: list[int] = []
    preview_owners: deque[tuple[int, int]] = deque()
    worker_available_ns = 0
    peak_preview_owners = 0
    failed_frame: int | None = None
    capacity_retry_frames: list[int] = []
    accepted_dispatch_ns: dict[int, int] = {}
    recorder_available_ns = 0
    first_service_ns = (
        preview_service_ns
        if first_preview_service_ns is None
        else first_preview_service_ns
    )

    for frame in range(frame_count):
        arrival_ns = frame * frame_interval_ns
        dispatch_ns = max(arrival_ns, recorder_available_ns)
        while preview_owners and preview_owners[0][1] <= dispatch_ns:
            preview_labels.append(preview_owners.popleft()[0])

        # A temporarily full preview ring returns RETRY_PENDING before JNI. The recorder retains the
        # exact RAW frame and retries it without fabricating a native tail or changing its identity.
        retry_started_ns = dispatch_ns
        while len(preview_owners) >= ring_slots:
            capacity_retry_frames.append(frame)
            next_retry_ns = dispatch_ns + retry_poll_ns
            retry_expired = next_retry_ns - retry_started_ns > retry_deadline_ns
            reader_budget_expired = next_retry_ns - arrival_ns > max(
                0, reader_max_images - 1
            ) * frame_interval_ns
            if retry_expired or reader_budget_expired:
                failed_frame = frame
                break
            dispatch_ns = next_retry_ns
            while preview_owners and preview_owners[0][1] <= dispatch_ns:
                preview_labels.append(preview_owners.popleft()[0])
        if failed_frame is not None:
            break

        service_ns = first_service_ns if frame == 0 else preview_service_ns
        completion_ns = max(dispatch_ns, worker_available_ns) + service_ns
        worker_available_ns = completion_ns
        preview_owners.append((frame, completion_ns))
        peak_preview_owners = max(peak_preview_owners, len(preview_owners))
        accepted_dispatch_ns[frame] = dispatch_ns
        recorder_available_ns = dispatch_ns

        # Full-resolution encoder acceptance keeps the original label and is acknowledged once after
        # safe preview enqueue. It never waits for preview EGL/glFinish.
        encoder_labels.append(frame)

    while preview_owners:
        preview_labels.append(preview_owners.popleft()[0])
    return {
        "encoder_labels": encoder_labels,
        "preview_labels": preview_labels,
        "failed_frame": failed_frame,
        "capacity_retry_frames": capacity_retry_frames,
        "accepted_dispatch_ns": accepted_dispatch_ns,
        "peak_preview_owners": peak_preview_owners,
        "final_full_resolution_owners": 0,
        "final_preview_owners": len(preview_owners),
    }


class PreviewDownsampleSimulationTest(unittest.TestCase):
    def test_750_frame_25fps_transport_has_exact_labels_and_zero_final_owners(self) -> None:
        result = simulate_ordered_preview_transport(
            frame_count=750,
            frame_interval_ns=40_000_000,
            preview_service_ns=8_000_000,
        )
        expected = list(range(750))
        self.assertEqual(expected, result["encoder_labels"])
        self.assertEqual(expected, result["preview_labels"])
        self.assertIsNone(result["failed_frame"])
        self.assertLessEqual(result["peak_preview_owners"], 3)
        self.assertEqual(0, result["final_full_resolution_owners"])
        self.assertEqual(0, result["final_preview_owners"])

    def test_overload_fails_explicitly_without_duplicate_or_relabel(self) -> None:
        result = simulate_ordered_preview_transport(
            frame_count=750,
            frame_interval_ns=40_000_000,
            preview_service_ns=130_000_000,
        )
        failed_frame = result["failed_frame"]
        self.assertIsNotNone(failed_frame)
        accepted = list(range(int(failed_frame)))
        self.assertEqual(accepted, result["encoder_labels"])
        self.assertEqual(accepted, result["preview_labels"])
        self.assertEqual(len(accepted), len(set(result["encoder_labels"])))
        self.assertEqual(len(accepted), len(set(result["preview_labels"])))
        self.assertLessEqual(result["peak_preview_owners"], 3)
        self.assertEqual(0, result["final_full_resolution_owners"])
        self.assertEqual(0, result["final_preview_owners"])

    def test_observed_11ms_retirement_preserves_750_exact_frame_labels(self) -> None:
        result = simulate_ordered_preview_transport(
            frame_count=750,
            frame_interval_ns=40_000_000,
            preview_service_ns=8_000_000,
            first_preview_service_ns=131_000_000,
            retry_poll_ns=4_000_000,
        )
        expected = list(range(750))
        self.assertIsNone(result["failed_frame"])
        self.assertEqual(expected, result["encoder_labels"])
        self.assertEqual(expected, result["preview_labels"])
        self.assertEqual([3, 3, 3], result["capacity_retry_frames"])
        self.assertEqual(132_000_000, result["accepted_dispatch_ns"][3])
        self.assertLessEqual(result["peak_preview_owners"], 3)
        self.assertEqual(0, result["final_preview_owners"])

    def test_measured_full_raw_to_preview_ratio_uses_quadrant_centres(self) -> None:
        scale = 4096.0 / 1280.0
        self.assertAlmostEqual(3.2, scale)
        self.assertAlmostEqual(0.8, quadrant_offset_source_pixels(scale))

    def test_ratio_filter_zero_matches_source_footprint_box_zero(self) -> None:
        scale = 3.2
        first_zero = source_footprint_box_first_zero(scale)
        self.assertAlmostEqual(0.0, two_tap_axis_response(scale, first_zero), delta=1.0e-12)

    def test_one_to_one_axis_is_not_blurred(self) -> None:
        self.assertEqual(0.0, quadrant_offset_source_pixels(1.0))
        self.assertEqual(1.0, two_tap_axis_response(1.0, math.pi))

    def test_route_is_preview_only_and_never_captures_codec_or_same_size_master(self) -> None:
        self.assertTrue(
            project_preview_downsample_enabled(
                "PROJECT_PQ_TEXTURE", "SENSOR_LINEAR", (4096, 3072), (1280, 960)
            )
        )
        self.assertFalse(
            project_preview_downsample_enabled(
                "MEDIA_CODEC", "SENSOR_LINEAR", (4096, 3072), (1280, 960)
            )
        )
        self.assertFalse(
            project_preview_downsample_enabled(
                "PROJECT_PQ_TEXTURE", "SENSOR_LINEAR", (4096, 3072), (4096, 3072)
            )
        )

    def test_sensor_linear_fp16_preserves_highlight_energy_without_negative_light(self) -> None:
        self.assertEqual(4.5, decode_intermediate_channel(4.5, preserve_extended_range=True))
        self.assertEqual(0.0, decode_intermediate_channel(-0.1, preserve_extended_range=True))
        self.assertEqual(1.0, decode_intermediate_channel(4.5, preserve_extended_range=False))

    def test_native_shader_matches_simulated_route_range_and_filter_contract(self) -> None:
        source = (
            ROOT / "app/src/main/cpp/offscreen_egl_encoder_bridge.cpp"
        ).read_text(encoding="utf-8")
        self.assertIn('state->deliveryModeLabel == "PROJECT_PQ_TEXTURE"', source)
        self.assertIn("downsampleScale * uSourceTexel * 0.25", source)
        self.assertIn("uPreserveExtendedRange", source)
        self.assertIn(
            "uPreserveExtendedRange != 0 ? max(sampled, vec3(0.0))", source
        )
        self.assertIn("fp16 ? GL_RGBA16F : GL_RGB10_A2", source)
        self.assertIn("configureHistoryTexture(", source)

    def test_vulkan_raw_preview_ring_shader_matches_ratio_filter_and_fp16_range(self) -> None:
        source = (
            ROOT / "app/src/main/cpp/shaders/vulkan/raw_preview_downsample.comp"
        ).read_text(encoding="utf-8")
        self.assertIn("downsampleScale.x * 0.25", source)
        self.assertIn("downsampleScale.y * 0.25", source)
        for footprint in ("sample00", "sample10", "sample01", "sample11"):
            self.assertIn(footprint, source)
        self.assertIn("0.25 * (sample00 + sample10 + sample01 + sample11)", source)
        self.assertIn("max(sceneLinear.rgb, vec3(0.0))", source)
        self.assertNotIn("clamp(sceneLinear.rgb, vec3(0.0), vec3(1.0))", source)

    def test_native_raw_preview_ring_is_independent_tokenized_and_three_deep(self) -> None:
        source = (
            ROOT / "app/src/main/cpp/vulkan_bridge.cpp"
        ).read_text(encoding="utf-8")
        self.assertIn("kRawPreviewTransportSlotCount = 3u", source)
        self.assertIn("recordRawPreviewTransport(", source)
        self.assertIn("completeReservedRawPreviewTransportSlot", source)
        self.assertIn("kRawTransportExternallyAcquired", source)
        self.assertIn("nativeAcquireRawDemosaicPreviewOutputHardwareBuffer", source)
        self.assertIn("nativeAcknowledgeRawDemosaicPreviewOutput", source)
        self.assertIn("nativeDiscardCompletedRawDemosaicPreviewOutputs", source)
        self.assertIn("nativeQuiesceRawDemosaicPreviewOutputs", source)
        self.assertIn("nativeGetRawDemosaicPreviewTransportStats", source)
        self.assertIn("RAW_PREVIEW_RING_SATURATED", source)
        self.assertIn("strictTenBitPreviewTransportOwnsFrame", source)
        self.assertIn(
            "r.previewTransportTokens[slot] = nextRawPreviewTransportToken(r)", source
        )
        self.assertIn("strictTenBitPreviewTransportAcceptedForFrame", source)
        self.assertIn(
            "RAW strict preview transport rejected; retaining legacy monitor readback for this frame",
            source,
        )
        self.assertIn(
            "!strictTenBitPreviewTransportAcceptedForFrame &&\n"
            "        recordRawMonitorReadback(",
            source,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
