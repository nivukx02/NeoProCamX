package com.neoproc.neoproccamx.recording

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.niv99.Niv99RawConversionOptions
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import com.neoproc.neoproccamx.rendering.VulkanBridge
import com.neoproc.neoproccamx.ui.ConversionExecutionPowerGuard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawNiv99GpuInstrumentedTest {
    private var bridge: VulkanBridge? = null

    @After
    fun cleanup() {
        RawDevelopGpuAccelerator.attach(null)
        bridge?.close()
        bridge = null
    }

    @Test
    fun converterDeliversEveryAcceptedFrameThroughGpuNiv99() {
        attachSupportedBridge()
        val width = 320
        val height = 180
        val frameCount = 6
        val metadata = metadata(width, height)
        val stream = ByteArrayOutputStream().apply {
            repeat(frameCount) { frame ->
                write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = rawFrame(width, height, frame),
                        width = width,
                        height = height,
                        rowStride = width * Short.SIZE_BYTES,
                        pixelStride = Short.SIZE_BYTES,
                        timestamp = SENSOR_TIMESTAMP_ORIGIN_NS + frame * FRAME_DURATION_NS,
                        originalSize = width * height * Short.SIZE_BYTES,
                        sensorWhiteLevel = metadata.sensorWhiteLevel
                    ).bytes
                )
            }
        }.toByteArray()

        for (delivery in RawToNeoLogMovConverter.DeliveryProfile.entries) {
            val outputSizes = ArrayList<Int>(frameCount)
            val result = RawToNeoLogMovConverter().convertStreamDirectP010Timed(
                input = ByteArrayInputStream(stream),
                meta = metadata,
                options = RawToNeoLogMovConverter.Options(
                    format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420,
                    deliveryProfile = delivery
                ),
                telemetry = RawConversionStageTelemetry("instrumented-${delivery.name}")
            ) { p010, _, _ ->
                assertP010(p010)
                outputSizes += p010.remaining()
            }
            val evidence = requireNotNull(result.niv99Evidence) {
                "$delivery conversion did not publish NIV99 evidence"
            }
            assertEquals(frameCount, result.framesConverted)
            assertEquals(List(frameCount) { width * height * 3 }, outputSizes)
            assertEquals(frameCount.toLong(), evidence.processedFrames)
            assertEquals(frameCount.toLong(), evidence.gpuProcessedFrames)
            assertEquals(0L, evidence.cpuProcessedFrames)
            assertEquals(width.toLong() * height * frameCount, evidence.fullFrameSamples)
            Log.i(
                TAG,
                "RAW_GPU_NIV99_CONVERTER_PASS delivery=${delivery.name} " +
                    "frames=${evidence.processedFrames} gpuFrames=${evidence.gpuProcessedFrames} " +
                    "cpuFrames=${evidence.cpuProcessedFrames} avgMs=${evidence.averageProcessingMs}"
            )
        }
    }

    @Test
    fun transformedRawProfilesMeetEightyPercentGpuStageShare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ConversionExecutionPowerGuard(context).run {
            verifyTransformedRawProfilesMeetEightyPercentGpuStageShare()
        }
    }

    private fun verifyTransformedRawProfilesMeetEightyPercentGpuStageShare() {
        attachSupportedBridge()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val powerManager = context.getSystemService(PowerManager::class.java)
        val width = 1920
        val height = 1080
        val metadata = metadata(width, height)
        val frames = List(PROFILE_FRAME_COUNT) { rawFrame(width, height, it) }

        for (delivery in RawToNeoLogMovConverter.DeliveryProfile.entries) {
            val plan = Niv99OfflineConversionPolicy.forRawDelivery(delivery.name)
            var cpuNanos = 0L
            var gpuNanos = 0L
            var copyWaitNanos = 0L
            repeat(PROFILE_FRAME_COUNT) { frame ->
                val result = requireNotNull(
                    RawDevelopGpuAccelerator.withDevelopedP010Direct(
                        rawSamples = frames[frame],
                        meta = metadata,
                        width = width,
                        height = height,
                        variant = com.neoproc.neoproccamx.core.NeoLogCurve.Variant.BASE,
                        deliveryProfile = delivery,
                        exposureStops = 0f,
                        niv99Plan = plan,
                        niv99Options = Niv99RawConversionOptions.HIGH_QUALITY,
                        frameNumber = frame.toLong(),
                        sensorTimestampNs = SENSOR_TIMESTAMP_ORIGIN_NS + frame * FRAME_DURATION_NS
                    ) { p010 ->
                        assertP010(p010)
                        p010.remaining()
                    }
                ) { "$delivery Vulkan conversion returned no mapped result" }
                assertEquals(width * height * 3, result.value)
                val evidence = requireNotNull(result.niv99Evidence) {
                    "$delivery Vulkan conversion returned no NIV99 evidence"
                }
                assertEquals(width * height, evidence.fullFrameSamples)
                cpuNanos = Math.addExact(cpuNanos, result.cpuNanos)
                gpuNanos = Math.addExact(gpuNanos, result.gpuNanos)
                copyWaitNanos = Math.addExact(copyWaitNanos, result.copyWaitNanos)
            }
            val measuredNanos = cpuNanos + gpuNanos + copyWaitNanos
            val gpuStagePercent = if (measuredNanos == 0L) {
                0.0
            } else {
                gpuNanos.toDouble() * 100.0 / measuredNanos.toDouble()
            }
            val result =
                "delivery=${delivery.name} frames=$PROFILE_FRAME_COUNT " +
                    "gpuPct=$gpuStagePercent cpuMs=${cpuNanos / 1_000_000.0} " +
                    "gpuMs=${gpuNanos / 1_000_000.0} copyWaitMs=${copyWaitNanos / 1_000_000.0} " +
                    "thermalStatus=${powerManager?.currentThermalStatus ?: -1}"
            Log.i(TAG, "RAW_GPU_NIV99_STAGE_SHARE $result")
            InstrumentationRegistry.getInstrumentation().sendStatus(
                EVIDENCE_STATUS_CODE,
                Bundle().apply { putString("rawGpuNiv99StageShare", result) }
            )
            assertTrue(
                "$delivery transformed RAW GPU-stage share was $gpuStagePercent%, expected >=80%",
                gpuStagePercent >= 80.0
            )
        }
    }

    private fun attachSupportedBridge() {
        val active = VulkanBridge()
        bridge = active
        assertTrue("Device Vulkan bridge is unavailable", active.isSupported())
        RawDevelopGpuAccelerator.setDevelopGpuEnabled(true)
        RawDevelopGpuAccelerator.attach(active)
        assertTrue("RAW Vulkan accelerator was not attached", RawDevelopGpuAccelerator.isAvailable())
        assertTrue(
            "RAW Vulkan conversion pipelines did not become ready within the bounded admission window",
            RawDevelopGpuAccelerator.awaitRawConversionPipelineReady()
        )
    }

    private fun assertP010(source: ByteBuffer) {
        val sample = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(sample.remaining() > 0)
        val words = sample.remaining() / Short.SIZE_BYTES
        var nonZero = false
        var index = 0
        while (index < words) {
            val word = sample.getShort(index * Short.SIZE_BYTES).toInt() and 0xFFFF
            assertEquals("P010 must be MSB-justified", 0, word and 0x3F)
            if (word != 0) nonZero = true
            index += 4093
        }
        assertTrue("GPU output collapsed to all-zero samples", nonZero)
    }

    private fun rawFrame(width: Int, height: Int, frame: Int): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (BLACK_LEVEL + 96 + (x * 7 + y * 11 + frame * 3) % 3600)
                .coerceAtMost(WHITE_LEVEL)
        }

    private fun metadata(width: Int, height: Int): RawDevelopMetadata = RawDevelopMetadata(
        width = width,
        height = height,
        storageBitDepth = 12,
        sensorWhiteLevel = WHITE_LEVEL,
        blackLevel = BLACK_LEVEL,
        whiteLevel = WHITE_LEVEL,
        bayerPatternValue = 0,
        whiteBalanceR = 2.05f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1f,
        whiteBalanceB = 1.72f,
        exposureTimeNs = FRAME_DURATION_NS,
        analogGain = 2f,
        digitalGain = 1f,
        noiseS = 0.00008f,
        noiseO = 0.000004f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
        fps = FPS,
        colorProfileTag = "NIV99-GPU-DEVICE",
        blackLevelRggb = floatArrayOf(
            BLACK_LEVEL.toFloat(),
            BLACK_LEVEL.toFloat(),
            BLACK_LEVEL.toFloat(),
            BLACK_LEVEL.toFloat()
        )
    )

    private companion object {
        const val TAG = "RawNiv99GpuDeviceTest"
        const val FPS = 25
        const val FRAME_DURATION_NS = 1_000_000_000L / FPS
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 7_000_000_000L
        const val BLACK_LEVEL = 64
        const val WHITE_LEVEL = 4095
        const val PROFILE_FRAME_COUNT = 8
        const val EVIDENCE_STATUS_CODE = 2
    }
}
