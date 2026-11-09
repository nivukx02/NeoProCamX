package com.neoproc.neoproccamx.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Instrumentation
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Device diagnostic for the Vivo P010 dataspace ambiguity observed by the NPV10 startup gate.
 *
 * The lanes isolate the public legacy P010 reader, legacy P010 plus an advertised SRGB session, and
 * the API-33 builder P010 plus explicit BT.709 limited dataspace. Every lane owns a fresh CameraDevice,
 * session and reader, so a previous HAL configuration cannot contaminate its result. Each result joins
 * Image.timestamp to CaptureResult.SENSOR_TIMESTAMP before reporting pixel metadata. This test
 * diagnoses framework/HAL behavior; it does not relax production's fail-closed pixel-authority policy.
 */
@RunWith(AndroidJUnit4::class)
class Npv10P010SessionColorContractDiagnosticInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun p010SingleOutput_comparesLegacySrgbAndBuilderBt709Contracts() {
        assumeTrue(
            "SessionConfiguration color-space selection requires API 34+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val source = findDiagnosticSource(manager)
        assumeTrue("No back Camera2 P010 + SRGB/STANDARD source is advertised", source != null)

        val cameraThread = HandlerThread("npv10-p010-color-contract").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        val resolvedSource = requireNotNull(source)
        val evidence = ArrayList<VariantEvidence>(Variant.entries.size)
        try {
            Variant.entries.forEach { variant ->
                evidence += runDeviceLane(manager, cameraHandler, resolvedSource, variant)
            }
        } finally {
            cameraThread.quitSafely()
            cameraThread.join(THREAD_JOIN_TIMEOUT_MS)
        }

        val identityLine = "NPV10_P010_CONTRACT_DEVICE camera=${resolvedSource.cameraId} " +
            "size=${resolvedSource.size.width}x${resolvedSource.size.height} " +
            "srgbStandardAdvertised=${resolvedSource.srgbStandardAdvertised} sdk=${Build.VERSION.SDK_INT}"
        val evidenceLines = listOf(identityLine) + evidence.map(VariantEvidence::toEvidenceLine)
        evidenceLines.forEach { line ->
            Log.i(TAG, line)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$line")
                }
            )
        }
        val evidenceDirectory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        File(evidenceDirectory, EVIDENCE_FILE_NAME).writeText(evidenceLines.joinToString("\n", postfix = "\n"))

        val legacyBaseline = evidence.first { it.variant == Variant.LEGACY_DEFAULT.label }
        assertTrue(
            "Advertised legacy P010 baseline failed: ${legacyBaseline.toEvidenceLine()}",
            legacyBaseline.sessionConfigured
        )
        assertTrue("Legacy baseline capture was not submitted", legacyBaseline.captureSubmitted)
        val baselineImage = requireNotNull(legacyBaseline.imageEvidence) {
            "Legacy baseline delivered no P010 image: ${legacyBaseline.toEvidenceLine()}"
        }
        assertEquals(ImageFormat.YCBCR_P010, baselineImage.imageFormat)
        assertEquals(HardwareBuffer.YCBCR_P010, baselineImage.hardwareBufferFormat)
        assertTrue("Legacy baseline P010 planes were inaccessible", baselineImage.planes.isNotEmpty())
        assertTrue(
            "Legacy baseline Image/CaptureResult timestamps did not join exactly",
            legacyBaseline.exactTimestampJoin
        )
        evidence.forEach { result ->
            assertEquals("CameraDevice must be closed exactly once for ${result.variant}", 1, result.cameraCloseClaims)
            assertTrue("CameraDevice.onClosed missing for ${result.variant}", result.cameraClosedObserved)
            if (result.readerCreated) {
                assertEquals("ImageReader must be closed exactly once for ${result.variant}", 1, result.readerCloseClaims)
            }
            if (result.sessionCreated) {
                assertEquals(
                    "CameraCaptureSession must be closed exactly once for ${result.variant}",
                    1,
                    result.sessionCloseClaims
                )
                assertTrue("CameraCaptureSession.onClosed missing for ${result.variant}", result.sessionClosedObserved)
            }
        }
    }

    private fun runDeviceLane(
        manager: CameraManager,
        cameraHandler: Handler,
        source: DiagnosticSource,
        variant: Variant
    ): VariantEvidence {
        val opened = openCamera(manager, source.cameraId, cameraHandler)
        var evidence: VariantEvidence? = null
        var cameraClosedObserved = false
        try {
            evidence = runVariant(opened.device, cameraHandler, source, variant)
        } finally {
            opened.closeOnce()
            cameraClosedObserved = opened.closed.await(
                CAMERA_CLOSE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        }
        return requireNotNull(evidence).copy(
            cameraCloseClaims = opened.closeClaims,
            cameraClosedObserved = cameraClosedObserved
        )
    }

    private fun runVariant(
        device: CameraDevice,
        cameraHandler: Handler,
        source: DiagnosticSource,
        variant: Variant
    ): VariantEvidence {
        val readerResult = runCatching { createReader(source.size, variant) }
        val reader = readerResult.getOrNull() ?: return VariantEvidence.readerConstructionFailure(
            variant = variant,
            error = requireNotNull(readerResult.exceptionOrNull()).toDiagnosticString()
        )
        val readerCloseClaimed = AtomicBoolean(false)
        val readerCloseClaims = AtomicInteger(0)
        val readerImageFormat = reader.imageFormat
        val readerHardwareBufferFormat = reader.hardwareBufferFormat
        val readerDataSpace = reader.dataSpace
        val sessionReference = AtomicReference<CameraCaptureSession?>()
        val sessionCloseClaimed = AtomicBoolean(false)
        val sessionCloseClaims = AtomicInteger(0)
        val sessionClosed = CountDownLatch(1)
        val sessionFinished = CountDownLatch(1)
        val sessionConfigured = AtomicBoolean(false)
        val sessionConfigureFailed = AtomicBoolean(false)
        val sessionFailure = AtomicReference<String?>()
        val sessionConfigLatencyNs = AtomicLong(0L)
        val imageEvidence = AtomicReference<ImageEvidence?>()
        val imageCallbackError = AtomicReference<String?>()
        val firstImageElapsedNs = AtomicLong(0L)
        val imageCloseClaims = AtomicInteger(0)
        val imageByTimestamp = ConcurrentHashMap<Long, ImageEvidence>()
        val captureResultByTimestamp = ConcurrentHashMap<Long, Long>()
        val exactJoin = AtomicBoolean(false)
        val joinedImageTimestampNs = AtomicLong(0L)
        val joinedCaptureTimestampNs = AtomicLong(0L)
        val joined = CountDownLatch(1)
        val captureFailure = AtomicReference<String?>()
        val sessionCreateStartedNs = AtomicLong(0L)
        var captureSubmitted = false
        var captureSubmittedElapsedNs = 0L
        var sessionClosedObserved = true

        fun tryJoin(timestampNs: Long) {
            val image = imageByTimestamp[timestampNs] ?: return
            val captureTimestamp = captureResultByTimestamp[timestampNs] ?: return
            if (exactJoin.compareAndSet(false, image.timestampNs == captureTimestamp)) {
                joinedImageTimestampNs.set(image.timestampNs)
                joinedCaptureTimestampNs.set(captureTimestamp)
                joined.countDown()
            }
        }

        reader.setOnImageAvailableListener({ available ->
            val image = runCatching { available.acquireNextImage() }
                .onFailure { imageCallbackError.compareAndSet(null, it.toDiagnosticString()) }
                .getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                firstImageElapsedNs.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
                val snapshot = ImageEvidence(
                    width = image.width,
                    height = image.height,
                    imageFormat = image.format,
                    hardwareBufferFormat = image.hardwareBuffer?.format ?: FORMAT_UNAVAILABLE,
                    dataSpace = image.dataSpace,
                    timestampNs = image.timestamp,
                    planes = image.planes.map { plane ->
                        PlaneEvidence(
                            rowStride = plane.rowStride,
                            pixelStride = plane.pixelStride,
                            remainingBytes = plane.buffer.remaining()
                        )
                    }
                )
                imageEvidence.compareAndSet(null, snapshot)
                imageByTimestamp[snapshot.timestampNs] = snapshot
                tryJoin(snapshot.timestampNs)
            } catch (error: Throwable) {
                imageCallbackError.compareAndSet(null, error.toDiagnosticString())
            } finally {
                image.close()
                imageCloseClaims.incrementAndGet()
            }
        }, cameraHandler)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionReference.set(session)
                sessionConfigured.set(true)
                sessionConfigLatencyNs.compareAndSet(
                    0L,
                    SystemClock.elapsedRealtimeNanos() - sessionCreateStartedNs.get()
                )
                sessionFinished.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionReference.set(session)
                sessionConfigureFailed.set(true)
                sessionFailure.compareAndSet(null, "SESSION_CONFIGURE_FAILED_CALLBACK")
                sessionConfigLatencyNs.compareAndSet(
                    0L,
                    SystemClock.elapsedRealtimeNanos() - sessionCreateStartedNs.get()
                )
                sessionFinished.countDown()
            }

            override fun onClosed(session: CameraCaptureSession) {
                sessionClosed.countDown()
            }
        }

        try {
            val output = OutputConfiguration(reader.surface).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)
            }
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                { command -> cameraHandler.post(command) },
                callback
            )
            if (variant.explicitSrgb) {
                configuration.setColorSpace(ColorSpace.Named.SRGB)
            }
            sessionCreateStartedNs.set(SystemClock.elapsedRealtimeNanos())
            runCatching { device.createCaptureSession(configuration) }
                .onFailure { error ->
                    sessionFailure.set(error.toDiagnosticString())
                    sessionFinished.countDown()
                }
            if (!sessionFinished.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                sessionFailure.compareAndSet(null, "SESSION_CONFIGURATION_TIMEOUT")
            }

            val session = sessionReference.get()
            if (sessionConfigured.get() && session != null) {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                captureSubmittedElapsedNs = SystemClock.elapsedRealtimeNanos()
                runCatching {
                    session.capture(
                        request,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
                                if (timestampNs == null || timestampNs <= 0L) {
                                    captureFailure.compareAndSet(null, "MISSING_SENSOR_TIMESTAMP")
                                    return
                                }
                                captureResultByTimestamp[timestampNs] = timestampNs
                                tryJoin(timestampNs)
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                captureFailure.compareAndSet(
                                    null,
                                    "CAPTURE_FAILED reason=${failure.reason} frame=${failure.frameNumber}"
                                )
                            }
                        },
                        cameraHandler
                    )
                }.onSuccess {
                    captureSubmitted = true
                }.onFailure { error ->
                    captureFailure.compareAndSet(null, error.toDiagnosticString())
                }
                if (!joined.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    captureFailure.compareAndSet(null, "FRAME_OR_TIMESTAMP_JOIN_TIMEOUT")
                }
            }
        } finally {
            reader.setOnImageAvailableListener(null, null)
            val session = sessionReference.get()
            if (session != null && sessionCloseClaimed.compareAndSet(false, true)) {
                sessionCloseClaims.incrementAndGet()
                runCatching { session.close() }
            }
            if (session != null) {
                sessionClosedObserved = sessionClosed.await(
                    SESSION_CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
            if (readerCloseClaimed.compareAndSet(false, true)) {
                readerCloseClaims.incrementAndGet()
                reader.close()
            }
        }

        val snapshot = imageEvidence.get()
        val firstFrameLatencyNs = if (captureSubmittedElapsedNs > 0L && firstImageElapsedNs.get() > 0L) {
            firstImageElapsedNs.get() - captureSubmittedElapsedNs
        } else {
            0L
        }
        return VariantEvidence(
            variant = variant.label,
            explicitSrgb = variant.explicitSrgb,
            readerCreated = true,
            readerConstructionFailure = null,
            readerImageFormat = readerImageFormat,
            readerHardwareBufferFormat = readerHardwareBufferFormat,
            readerDataSpace = readerDataSpace,
            sessionCreated = sessionReference.get() != null,
            sessionConfigured = sessionConfigured.get(),
            sessionConfigureFailed = sessionConfigureFailed.get(),
            sessionFailure = sessionFailure.get(),
            sessionConfigLatencyNs = sessionConfigLatencyNs.get(),
            captureSubmitted = captureSubmitted,
            firstFrameLatencyNs = firstFrameLatencyNs,
            imageEvidence = snapshot,
            imageCallbackError = imageCallbackError.get(),
            captureFailure = captureFailure.get(),
            exactTimestampJoin = exactJoin.get(),
            joinedImageTimestampNs = joinedImageTimestampNs.get(),
            joinedCaptureTimestampNs = joinedCaptureTimestampNs.get(),
            imageCloseClaims = imageCloseClaims.get(),
            sessionCloseClaims = sessionCloseClaims.get(),
            readerCloseClaims = readerCloseClaims.get(),
            sessionClosedObserved = sessionClosedObserved
        )
    }

    private fun createReader(size: Size, variant: Variant): ImageReader = when (variant.readerContract) {
        ReaderContract.LEGACY -> ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YCBCR_P010,
            READER_MAX_IMAGES
        )
        ReaderContract.BUILDER_BT709 -> ImageReader.Builder(size.width, size.height)
            .setMaxImages(READER_MAX_IMAGES)
            .setUsage(HardwareBuffer.USAGE_CPU_READ_OFTEN)
            .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
            .setDefaultDataSpace(DataSpace.DATASPACE_BT709)
            .build()
    }

    private fun findDiagnosticSource(manager: CameraManager): DiagnosticSource? =
        manager.cameraIdList.asSequence().mapNotNull { cameraId ->
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return@mapNotNull null
            }
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val size = map.getOutputSizes(ImageFormat.YCBCR_P010)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return@mapNotNull null
            val profiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
            )
            val srgbAdvertised = profiles
                ?.getSupportedColorSpacesForDynamicRange(
                    ImageFormat.YCBCR_P010,
                    DynamicRangeProfiles.STANDARD
                )
                ?.contains(ColorSpace.Named.SRGB) == true
            DiagnosticSource(cameraId, size, srgbAdvertised)
        }.filter(DiagnosticSource::srgbStandardAdvertised)
            .maxByOrNull { it.size.width.toLong() * it.size.height.toLong() }

    @SuppressLint("MissingPermission")
    private fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler
    ): OpenedCamera {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val device = AtomicReference<CameraDevice?>()
        val failure = AtomicReference<String?>()
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device.set(camera)
                opened.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                failure.set("CAMERA_DISCONNECTED")
                camera.close()
                opened.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                failure.set("CAMERA_OPEN_ERROR_$error")
                camera.close()
                opened.countDown()
            }

            override fun onClosed(camera: CameraDevice) {
                closed.countDown()
            }
        }, handler)
        assertTrue("Timed out opening Camera2 source $cameraId", opened.await(CAMERA_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        check(failure.get() == null) { requireNotNull(failure.get()) }
        return OpenedCamera(requireNotNull(device.get()), closed)
    }

    private enum class ReaderContract {
        LEGACY,
        BUILDER_BT709
    }

    private enum class Variant(
        val label: String,
        val explicitSrgb: Boolean,
        val readerContract: ReaderContract
    ) {
        LEGACY_DEFAULT("LEGACY_P010_STANDARD_DEFAULT_COLOR", false, ReaderContract.LEGACY),
        LEGACY_SRGB("LEGACY_P010_STANDARD_SRGB", true, ReaderContract.LEGACY),
        BUILDER_BT709("BUILDER_P010_STANDARD_BT709", false, ReaderContract.BUILDER_BT709)
    }

    private data class DiagnosticSource(
        val cameraId: String,
        val size: Size,
        val srgbStandardAdvertised: Boolean
    )

    private data class PlaneEvidence(
        val rowStride: Int,
        val pixelStride: Int,
        val remainingBytes: Int
    ) {
        fun compact(): String = "$rowStride:$pixelStride:$remainingBytes"
    }

    private data class ImageEvidence(
        val width: Int,
        val height: Int,
        val imageFormat: Int,
        val hardwareBufferFormat: Int,
        val dataSpace: Int,
        val timestampNs: Long,
        val planes: List<PlaneEvidence>
    )

    private data class VariantEvidence(
        val variant: String,
        val explicitSrgb: Boolean,
        val readerCreated: Boolean,
        val readerConstructionFailure: String?,
        val readerImageFormat: Int,
        val readerHardwareBufferFormat: Int,
        val readerDataSpace: Int,
        val sessionCreated: Boolean,
        val sessionConfigured: Boolean,
        val sessionConfigureFailed: Boolean,
        val sessionFailure: String?,
        val sessionConfigLatencyNs: Long,
        val captureSubmitted: Boolean,
        val firstFrameLatencyNs: Long,
        val imageEvidence: ImageEvidence?,
        val imageCallbackError: String?,
        val captureFailure: String?,
        val exactTimestampJoin: Boolean,
        val joinedImageTimestampNs: Long,
        val joinedCaptureTimestampNs: Long,
        val imageCloseClaims: Int,
        val sessionCloseClaims: Int,
        val readerCloseClaims: Int,
        val sessionClosedObserved: Boolean,
        val cameraCloseClaims: Int = 0,
        val cameraClosedObserved: Boolean = false
    ) {
        fun toEvidenceLine(): String {
            val image = imageEvidence
            return "NPV10_P010_CONTRACT_RESULT variant=$variant explicitSrgb=$explicitSrgb " +
                "readerCreated=$readerCreated " +
                "readerConstructionFailure=${readerConstructionFailure.safeToken()} " +
                "readerFormat=$readerImageFormat readerHardwareFormat=$readerHardwareBufferFormat " +
                "readerDataSpace=$readerDataSpace sessionCreated=$sessionCreated " +
                "sessionConfigured=$sessionConfigured configureFailed=$sessionConfigureFailed " +
                "sessionConfigMs=${sessionConfigLatencyNs.toMilliseconds()} " +
                "captureSubmitted=$captureSubmitted firstFrameMs=${firstFrameLatencyNs.toMilliseconds()} " +
                "imageSize=${image?.width ?: 0}x${image?.height ?: 0} " +
                "imageFormat=${image?.imageFormat ?: FORMAT_UNAVAILABLE} " +
                "hardwareBufferFormat=${image?.hardwareBufferFormat ?: FORMAT_UNAVAILABLE} " +
                "imageDataSpace=${image?.dataSpace ?: DATA_SPACE_UNAVAILABLE} " +
                "planes=${image?.planes?.joinToString(",") { it.compact() } ?: "none"} " +
                "imageTimestampNs=${image?.timestampNs ?: 0L} " +
                "captureTimestampNs=$joinedCaptureTimestampNs exactTimestampJoin=$exactTimestampJoin " +
                "joinedImageTimestampNs=$joinedImageTimestampNs " +
                "imageCloses=$imageCloseClaims sessionCloses=$sessionCloseClaims " +
                "sessionClosedObserved=$sessionClosedObserved readerCloses=$readerCloseClaims " +
                "cameraCloses=$cameraCloseClaims cameraClosedObserved=$cameraClosedObserved " +
                "sessionFailure=${sessionFailure.safeToken()} " +
                "imageError=${imageCallbackError.safeToken()} captureFailure=${captureFailure.safeToken()}"
        }

        private fun String?.safeToken(): String =
            this?.replace(Regex("\\s+"), "_") ?: "none"

        private fun Long.toMilliseconds(): Double = this.toDouble() / NANOS_PER_MILLISECOND

        companion object {
            fun readerConstructionFailure(variant: Variant, error: String): VariantEvidence =
                VariantEvidence(
                    variant = variant.label,
                    explicitSrgb = variant.explicitSrgb,
                    readerCreated = false,
                    readerConstructionFailure = error,
                    readerImageFormat = FORMAT_UNAVAILABLE,
                    readerHardwareBufferFormat = FORMAT_UNAVAILABLE,
                    readerDataSpace = DATA_SPACE_UNAVAILABLE,
                    sessionCreated = false,
                    sessionConfigured = false,
                    sessionConfigureFailed = false,
                    sessionFailure = null,
                    sessionConfigLatencyNs = 0L,
                    captureSubmitted = false,
                    firstFrameLatencyNs = 0L,
                    imageEvidence = null,
                    imageCallbackError = null,
                    captureFailure = null,
                    exactTimestampJoin = false,
                    joinedImageTimestampNs = 0L,
                    joinedCaptureTimestampNs = 0L,
                    imageCloseClaims = 0,
                    sessionCloseClaims = 0,
                    readerCloseClaims = 0,
                    sessionClosedObserved = true
                )
        }
    }

    private class OpenedCamera(
        val device: CameraDevice,
        val closed: CountDownLatch
    ) {
        private val closeClaimed = AtomicBoolean(false)
        private val closeClaimCount = AtomicInteger(0)

        val closeClaims: Int
            get() = closeClaimCount.get()

        fun closeOnce() {
            if (closeClaimed.compareAndSet(false, true)) {
                closeClaimCount.incrementAndGet()
                device.close()
            }
        }
    }

    private fun Throwable.toDiagnosticString(): String =
        "${javaClass.simpleName}:${message.orEmpty()}".replace(Regex("\\s+"), "_")

    private companion object {
        private const val TAG = "Npv10P010ContractDiag"
        private const val EVIDENCE_FILE_NAME = "npv10_p010_session_color_contract.txt"
        private const val READER_MAX_IMAGES = 3
        private const val FORMAT_UNAVAILABLE = -1
        private const val DATA_SPACE_UNAVAILABLE = -1
        private const val CAMERA_OPEN_TIMEOUT_SECONDS = 15L
        private const val SESSION_TIMEOUT_SECONDS = 15L
        private const val FRAME_TIMEOUT_SECONDS = 15L
        private const val SESSION_CLOSE_TIMEOUT_SECONDS = 5L
        private const val CAMERA_CLOSE_TIMEOUT_SECONDS = 5L
        private const val THREAD_JOIN_TIMEOUT_MS = 5_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
