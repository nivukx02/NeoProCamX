package com.neoproc.neoproccamx.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.capture.CaptureEvent
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.ai.EnhancementEngine
import com.neoproc.neoproccamx.ai.SceneType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class HighEndPhotoCaptureInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA
    )

    @Test
    fun largePhotoEnhancement_keepsNativeResolutionAndHasNoTileBoundaryJump() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 4_100
        val height = 320
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val gray = 32 + (x * 192 / (width - 1))
            (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        val (output, metrics) = EnhancementEngine(context).enhance(
            bitmap = input,
            sceneType = SceneType.LANDSCAPE,
            config = EnhancementEngine.EnhancementConfig(
                denoiseStrength = 0f,
                sharpenStrength = 0.10f,
                microContrastStrength = 0f,
                adaptiveByScene = false
            )
        )
        try {
            assertEquals(width, output.width)
            assertEquals(height, output.height)
            assertFalse("native-resolution photo must never report resampling", metrics.resolutionScaleApplied)
            val y = height / 2
            listOf(1_024, 2_048, 3_072, 4_096).filter { it < width }.forEach { seamX ->
                val left = output.getPixel(seamX - 1, y) and 0xFF
                val right = output.getPixel(seamX, y) and 0xFF
                assertTrue("tile boundary jump at x=$seamX: $left->$right", kotlin.math.abs(right - left) <= 2)
            }
        } finally {
            if (!output.isRecycled) output.recycle()
            if (!input.isRecycled) input.recycle()
        }
    }

    @Test
    fun highQualityFast_savesFullJpegAndKeepsPreviewLive() {
        runBlocking {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCondition(30_000L, "Camera did not start") {
                vm.cameraState.value == CameraState.Streaming
            }

            scenario.onActivity {
                vm.setMaximumPhotoResolutionEnabled(false)
                vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                vm.setColorProfile("Natural")
                vm.setLutEnabled(false)
                vm.setDebugValidationLabel("e2e_photo_hq_fast")
            }
            awaitCondition(10_000L, "High-quality photo session did not settle") {
                vm.cameraState.value == CameraState.Streaming &&
                    !vm.camera.sessionRefreshPending.get() &&
                    vm.camera.hasRecentPreviewActivity()
            }

            val reader = requireNotNull(vm.camera.jpegReader)
            val expectedWidth = reader.width
            val expectedHeight = reader.height
            assertEquals(100, vm.camera.jpegQuality)

            val previewTimesMs = CopyOnWriteArrayList<Long>()
            val previewCollector = launch(Dispatchers.Default) {
                vm.previewFrameTimestamps.collect {
                    previewTimesMs += SystemClock.elapsedRealtime()
                }
            }
            awaitCondition(5_000L, "Preview cadence was not observable before capture") {
                previewTimesMs.size >= 5
            }

            val terminalEvent = async(
                context = Dispatchers.Default,
                start = CoroutineStart.UNDISPATCHED
            ) {
                withTimeout(15_000L) {
                    vm.captureEvents.first { event ->
                        event is CaptureEvent.Saved || event is CaptureEvent.Error
                    }
                }
            }
            val captureStartMs = SystemClock.elapsedRealtime()
            var accepted = false
            scenario.onActivity { accepted = vm.captureHighQualityPhoto() }
            assertTrue("High-quality shutter request was rejected", accepted)

            val event = terminalEvent.await()
            assertTrue(
                "Photo capture failed: ${(event as? CaptureEvent.Error)?.msg}",
                event is CaptureEvent.Saved
            )
            val savedMs = SystemClock.elapsedRealtime()
            val captureLatencyMs = savedMs - captureStartMs
            val previewCountAtSave = previewTimesMs.size
            awaitCondition(5_000L, "Preview did not continue after photo save") {
                previewTimesMs.size >= previewCountAtSave + 5 && vm.camera.hasRecentPreviewActivity()
            }
            previewCollector.cancelAndJoin()

            val saved = event as CaptureEvent.Saved
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val uri = Uri.parse(saved.uri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val fileBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: -1L
            val mimeType = context.contentResolver.getType(uri)
            val maxPreviewGapMs = previewTimesMs.zipWithNext { first, second -> second - first }
                .maxOrNull() ?: Long.MAX_VALUE

            assertEquals("image/jpeg", mimeType)
            val nativeDimensionsPreserved =
                (options.outWidth == expectedWidth && options.outHeight == expectedHeight) ||
                    (options.outWidth == expectedHeight && options.outHeight == expectedWidth)
            assertTrue(
                "Saved JPEG dimensions ${options.outWidth}x${options.outHeight} do not preserve " +
                    "the native ${expectedWidth}x${expectedHeight} capture (including rotation)",
                nativeDimensionsPreserved
            )
            assertTrue("Saved JPEG is unexpectedly small: $fileBytes bytes", fileBytes > 100_000L)
            assertTrue("High-quality fast capture took ${captureLatencyMs}ms", captureLatencyMs < 8_000L)
            assertTrue("Preview stalled for ${maxPreviewGapMs}ms", maxPreviewGapMs < 1_500L)
            assertTrue("Preview did not produce frames during capture", previewCountAtSave > 5)

            Log.i(
                "HighEndPhotoCaptureTest",
                "PHOTO_METRICS latencyMs=$captureLatencyMs maxPreviewGapMs=$maxPreviewGapMs " +
                    "frames=${previewTimesMs.size} jpeg=${options.outWidth}x${options.outHeight} " +
                    "bytes=$fileBytes quality=${vm.camera.jpegQuality} uri=$uri"
            )
            }
        }
    }

    private fun awaitCondition(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }
}
