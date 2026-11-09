from __future__ import annotations

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from elite_video_player_simulation import (  # noqa: E402
    DOUBLE_TAP_SEEK_MS,
    DecodeScenario,
    PlaybackFamily,
    PlaybackRoute,
    aspect_fit,
    relative_seek,
    resolve_playback_route,
    simulate_decode_deadlines,
    sustainable_matrix,
    timeline_fraction,
    verify_seek_sequence,
)


class EliteVideoPlayerSimulationTest(unittest.TestCase):
    def test_aspect_fit_never_stretches_or_crops(self) -> None:
        for content, viewport in (
            ((3840, 2160), (1080, 2400)),
            ((1080, 1920), (2400, 1080)),
            ((4096, 1716), (2400, 1080)),
            ((1920, 1080), (1920, 1080)),
        ):
            result = aspect_fit(*content, *viewport)
            self.assertLessEqual(result.right, viewport[0])
            self.assertLessEqual(result.bottom, viewport[1])
            self.assertAlmostEqual(
                content[0] / content[1],
                result.width / result.height,
                delta=0.003,
            )

    def test_double_tap_is_exact_and_clamped(self) -> None:
        self.assertEqual(0, relative_seek(3_000, 70_000, -DOUBLE_TAP_SEEK_MS))
        self.assertEqual(20_000, relative_seek(10_000, 70_000, DOUBLE_TAP_SEEK_MS))
        self.assertEqual(70_000, relative_seek(65_000, 70_000, DOUBLE_TAP_SEEK_MS))

    def test_long_seek_stress_never_escapes_timeline(self) -> None:
        deltas = [DOUBLE_TAP_SEEK_MS] * 100 + [-DOUBLE_TAP_SEEK_MS] * 150
        final_position = verify_seek_sequence(90_000, 45_000, deltas)
        self.assertEqual(0, final_position)
        self.assertEqual(0.0, timeline_fraction(final_position, 90_000))

    def test_all_app_video_families_have_truthful_routes(self) -> None:
        routes = {family: resolve_playback_route(family) for family in PlaybackFamily}
        self.assertEqual(PlaybackRoute.NIVUKX_EXTRACTOR_HARDWARE, routes[PlaybackFamily.NIVUKX])
        self.assertEqual(PlaybackRoute.DIRECT_PRIVATE_MASTER_DEADLINE, routes[PlaybackFamily.NPV10])
        self.assertEqual(PlaybackRoute.DIRECT_PRIVATE_MASTER_DEADLINE, routes[PlaybackFamily.NPRAW])
        self.assertEqual(PlaybackRoute.DEVICE_CODEC_REQUIRED, routes[PlaybackFamily.UNCOMPRESSED_R210_MOV])
        self.assertEqual(PlaybackRoute.DEVICE_CODEC_REQUIRED, routes[PlaybackFamily.UNCOMPRESSED_V210_MOV])

    def test_sustainable_24_to_120_fps_matrix_has_no_late_frames(self) -> None:
        for scenario in sustainable_matrix():
            result = simulate_decode_deadlines(scenario)
            self.assertTrue(result.zero_drop, result)
            self.assertEqual(result.target_fps, result.achieved_fps)

    def test_impossible_decode_budget_is_reported_not_hidden(self) -> None:
        result = simulate_decode_deadlines(
            DecodeScenario(
                name="60fps-over-budget",
                fps=60.0,
                duration_seconds=10.0,
                base_decode_ms=22.0,
                initial_buffer_ms=100.0,
            )
        )
        self.assertFalse(result.zero_drop)
        self.assertGreater(result.late_frames, 0)
        self.assertLess(result.achieved_fps, result.target_fps)


class VideoPlayerSourceContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_android_player_uses_aspect_fit_not_zoom_or_fill(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/MediaPreviewActivity.kt"
        )
        self.assertIn("resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT", source)
        self.assertNotIn("resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM", source)

    def test_android_double_tap_waits_for_confirmation_and_seeks_ten_seconds(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/EliteVideoPlaybackControls.kt"
        )
        self.assertIn("override fun onSingleTapUp", source)
        self.assertIn(
            "postDelayed(confirmedClick, ViewConfiguration.getDoubleTapTimeout().toLong())",
            source,
        )
        double_tap = source.split("override fun onDoubleTap", 1)[1].split(
            "override fun onFling", 1
        )[0]
        self.assertIn("cancelPendingSingleTap()", double_tap)
        self.assertIn("performClick()", source)
        self.assertIn("const val DOUBLE_TAP_SEEK_MS = 10_000L", source)
        self.assertIn("if (e.x < width / 2f) onDoubleTapLeft() else onDoubleTapRight()", source)

    def test_timeline_commits_one_decoder_seek_per_scrub(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/EliteVideoPlaybackControls.kt"
        )
        timeline = source.split("private class ElitePlaybackTimelineView", 1)[1]
        move = timeline.split("MotionEvent.ACTION_MOVE", 1)[1].split("MotionEvent.ACTION_UP", 1)[0]
        up = timeline.split("MotionEvent.ACTION_UP", 1)[1].split("MotionEvent.ACTION_CANCEL", 1)[0]
        self.assertNotIn("onSeekCommitted", move)
        self.assertEqual(1, up.count("onSeekCommitted(positionMs)"))

    def test_converter_thumbnail_has_playback_action_separate_from_row_actions(self) -> None:
        ui = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsUiFactory.kt"
        )
        factory = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        )
        self.assertIn("onThumbnailClick: (() -> Unit)?", ui)
        self.assertIn("playRawClipFromThumbnail", factory)
        self.assertIn("playNpv10ClipFromThumbnail", factory)
        self.assertIn("playRecordDevelopClipFromThumbnail", factory)

    def test_no_new_raw_or_npv10_thumbnail_generator_is_used_for_playback(self) -> None:
        factory = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        )
        playback_block = factory.split("private fun playRawClipFromThumbnail", 1)[1].split(
            "private fun showRecordDevelopActions", 1
        )[0]
        self.assertNotIn("Npv10ThumbnailRenderer", playback_block)
        self.assertNotIn("Raw12ThumbnailRenderer", playback_block)

    def test_thumbnail_callbacks_never_invoke_or_publish_a_conversion(self) -> None:
        factory = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        )
        playback_block = factory.split("private fun playRawClipFromThumbnail", 1)[1].split(
            "private fun showRecordDevelopActions", 1
        )[0]
        self.assertNotIn("convertRawClipToMov", playback_block)
        self.assertNotIn("convertNpv10MasterToMov", playback_block)
        self.assertNotIn("runConversionWithProgress", playback_block)
        self.assertIn("EXTRA_PRIVATE_MASTER_TYPE", playback_block)

    def test_every_expensive_converter_has_explicit_final_confirmation(self) -> None:
        factory = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        )
        self.assertIn("confirmRawLogConversion", factory)
        self.assertIn("confirmRawDngExport", factory)
        self.assertIn("confirmNpv10Conversion", factory)
        self.assertIn("confirmRecordDevelopConversion", factory)
        self.assertGreaterEqual(factory.count('SettingsActionChoice("Start conversion")'), 3)
        self.assertIn('SettingsActionChoice("Start export")', factory)

    def test_private_master_player_is_bounded_and_latest_frame_driven(self) -> None:
        player = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/PrivateMasterPlaybackController.kt"
        )
        self.assertRegex(
            player,
            r"ScheduledThreadPoolExecutor\s*\(\s*1\s*,",
            "private-master decode must remain a single-worker scheduled executor",
        )
        self.assertIn("private const val MAX_BITMAPS = 3", player)
        self.assertIn("AtomicReference<PendingDisplayFrame?>", player)
        self.assertIn("PendingDisplayFrame(bitmap, ptsUs)", player)
        self.assertIn("latestFrame.getAndSet(pending)", player)
        self.assertIn("releaseBitmap(replaced.bitmap)", player)
        self.assertIn("NprawFrameCodec.readFrame(activeInput)", player)
        self.assertIn("NprawFrameCodec.StreamFrameSkipper()", player)
        self.assertNotIn("convertRawClipToMov", player)
        self.assertNotIn("convertNpv10MasterToMov", player)


if __name__ == "__main__":
    unittest.main(verbosity=2)
