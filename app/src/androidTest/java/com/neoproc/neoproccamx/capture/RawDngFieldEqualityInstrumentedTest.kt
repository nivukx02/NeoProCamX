package com.neoproc.neoproccamx.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CP-143 native DNG acceptance test.
 *
 * One real RAW_SENSOR frame is snapshotted before [DngCreator] sees it, written through Android's
 * native DNG implementation, and then independently decoded. The test retains all colour-tag
 * field-equality checks and additionally requires every 16-bit CFA sample to be bit-identical.
 * Machine-readable evidence is atomically published in the target app's private files directory.
 */
@RunWith(AndroidJUnit4::class)
class RawDngFieldEqualityInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Before
    fun setUp() {
        val worker = HandlerThread("raw-dng-field-equality").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
    }

    /** Last-resort idempotent cleanup if setup or evidence publication itself throws. */
    @After
    fun tearDown() {
        stopHandlerThread(mutableListOf())
    }

    @SuppressLint("MissingPermission")
    @Test
    fun dngCreator_emitsColourTags_andBitExactRawPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val resources = CaptureResources()
        val imageReference = AtomicReference<Image?>()
        val cleanupErrors = mutableListOf<String>()

        var reader: ImageReader? = null
        var source: DngRawPixelFidelityOracle.CanonicalRaw? = null
        var comparison: DngRawPixelFidelityOracle.Comparison? = null
        var sourceRowStride: Int? = null
        var sourcePixelStride: Int? = null
        var failure: Throwable? = null
        var testStatus = "FAIL"
        var skipReason: String? = null
        var listenerDetached = false
        var listenerDrained = false
        var imageClosed = false
        var readerClosed = false
        var sessionClosed = false
        var deviceClosed = false
        var handlerThreadStopped = false

        try {
            val raw = findRawCamera(manager)
            if (raw == null) skipReason = "no RAW-capable camera on this device"
            assumeTrue(skipReason ?: "RAW camera available", raw != null)
            val (cameraId, chars) = requireNotNull(raw)

            val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (rawSize == null) skipReason = "no RAW_SENSOR output size"
            assumeTrue(skipReason ?: "RAW_SENSOR size available", rawSize != null)
            val selectedSize = requireNotNull(rawSize)

            val activeReader = ImageReader.newInstance(
                selectedSize.width,
                selectedSize.height,
                ImageFormat.RAW_SENSOR,
                IMAGE_READER_DEPTH,
            )
            reader = activeReader
            val imageLatch = CountDownLatch(1)
            activeReader.setOnImageAvailableListener({ availableReader ->
                val next = availableReader.acquireNextImage()
                if (next != null) {
                    if (imageReference.compareAndSet(null, next)) {
                        imageLatch.countDown()
                    } else {
                        next.close()
                    }
                }
            }, requireHandler())

            val device = openCamera(manager, cameraId, resources)
            val result = captureSingleRaw(device, activeReader, resources)
            assertTrue("RAW image did not arrive", imageLatch.await(IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val image = imageReference.get() ?: throw IllegalStateException("no RAW image")
            assertEquals("RAW_SENSOR must expose exactly one plane", 1, image.planes.size)
            val plane = image.planes.single()
            sourceRowStride = plane.rowStride
            sourcePixelStride = plane.pixelStride

            // Snapshot before DngCreator writes; duplicate-buffer reads do not mutate Image state.
            source = DngRawPixelFidelityOracle.canonicalizeRaw16(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )

            val dngBytes = ByteArrayOutputStream().use { output ->
                DngCreator(chars, result).use { dng -> dng.writeImage(output, image) }
                output.toByteArray()
            }
            comparison = DngRawPixelFidelityOracle.compare(requireNotNull(source), dngBytes)
            val pixelResult = requireNotNull(comparison)

            // Pixel identity: dimensions/count, every sample, maximum error, and canonical digest.
            assertEquals("DNG width mismatch", source.width, pixelResult.dng.width)
            assertEquals("DNG height mismatch", source.height, pixelResult.dng.height)
            assertEquals("DNG sample-count mismatch", source.sampleCount, pixelResult.dng.sampleCount)
            assertEquals("RAW/DNG pixel mismatch count", 0, pixelResult.mismatchCount)
            assertEquals("RAW/DNG maximum absolute error", 0, pixelResult.maxAbs)
            assertEquals("RAW/DNG canonical SHA-256 mismatch", source.sha256, pixelResult.dng.sha256)

            val tags = DngTiffReader.read(dngBytes)

            // Self-describing: every required colour tag is present.
            val missing = RawDngMetadataContract.REQUIRED - tags.presentTags()
            assertTrue("DNG is missing required colour tags: $missing", missing.isEmpty())

            // Field equality against capture metadata (unambiguous scalars exact).
            chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { white ->
                assertEquals("WhiteLevel mismatch", white.toLong(), tags.whiteLevel)
            }
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { illuminant ->
                assertEquals("CalibrationIlluminant1 mismatch", illuminant, tags.calibrationIlluminant1)
            }

            // ForwardMatrix1 equality (order-independent multiset, within rational rounding).
            chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { transform ->
                val forwardMatrix = tags.forwardMatrix1
                assertNotNull("DNG ForwardMatrix1 missing", forwardMatrix)
                assertEquals(9, forwardMatrix!!.size)
                val expected = DoubleArray(9) { index ->
                    val element = transform.getElement(index % 3, index / 3)
                    element.numerator.toDouble() / element.denominator.toDouble()
                }.sorted()
                val actual = forwardMatrix.sorted()
                for (index in 0 until 9) {
                    assertEquals("ForwardMatrix1[$index]", expected[index], actual[index], 1e-3)
                }
            }
            testStatus = "PASS"
        } catch (caught: Throwable) {
            failure = caught
            if (skipReason != null) testStatus = "SKIP"
        } finally {
            resources.beginCleanup().also { owned ->
                owned.session?.let { session ->
                    runCleanup("session.close", cleanupErrors) { session.close() }
                    sessionClosed = awaitCleanupLatch(
                        resources.sessionClosed,
                        "session.onClosed",
                        cleanupErrors,
                    )
                } ?: run { sessionClosed = true }

                owned.device?.let { device ->
                    runCleanup("device.close", cleanupErrors) { device.close() }
                    deviceClosed = awaitCleanupLatch(
                        resources.deviceClosed,
                        "device.onClosed",
                        cleanupErrors,
                    )
                } ?: run { deviceClosed = true }
            }

            reader?.let { activeReader ->
                listenerDetached = runCleanup("reader.listener.detach", cleanupErrors) {
                    activeReader.setOnImageAvailableListener(null, null)
                }
                listenerDrained = drainHandler(cleanupErrors)
            } ?: run {
                listenerDetached = true
                listenerDrained = true
            }

            imageReference.getAndSet(null)?.let { image ->
                imageClosed = runCleanup("image.close", cleanupErrors) { image.close() }
            } ?: run { imageClosed = true }
            reader?.let { activeReader ->
                readerClosed = runCleanup("reader.close", cleanupErrors) { activeReader.close() }
            } ?: run { readerClosed = true }

            handlerThreadStopped = stopHandlerThread(cleanupErrors)
        }

        val cleanupPassed = listOf(
            listenerDetached,
            listenerDrained,
            imageClosed,
            readerClosed,
            sessionClosed,
            deviceClosed,
            handlerThreadStopped,
        ).all { it } && cleanupErrors.isEmpty()
        if (!cleanupPassed && failure == null) {
            failure = AssertionError("CP-143 cleanup failed: ${cleanupErrors.joinToString()}")
            testStatus = "FAIL"
        }

        val evidence = buildEvidence(
            status = testStatus,
            source = source,
            comparison = comparison,
            rowStride = sourceRowStride,
            pixelStride = sourcePixelStride,
            cleanupPassed = cleanupPassed,
            cleanupErrors = cleanupErrors,
            listenerDetached = listenerDetached,
            listenerDrained = listenerDrained,
            imageClosed = imageClosed,
            readerClosed = readerClosed,
            sessionClosed = sessionClosed,
            deviceClosed = deviceClosed,
            handlerThreadStopped = handlerThreadStopped,
            failure = failure,
        )
        try {
            val evidenceFile = writeEvidenceAtomically(context, evidence)
            evidence.put("evidencePath", evidenceFile.absolutePath)
            Log.i(LOG_TAG, "$LOG_PREFIX${evidence}")
        } catch (writeFailure: Throwable) {
            Log.e(LOG_TAG, "CP-143 evidence publication failed", writeFailure)
            failure?.addSuppressed(writeFailure) ?: run { failure = writeFailure }
        }

        failure?.let { throw it }
    }

    private fun findRawCamera(manager: CameraManager): Pair<String, CameraCharacteristics>? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                return id to chars
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(
        manager: CameraManager,
        id: String,
        resources: CaptureResources,
    ): CameraDevice {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var errorCode: Int? = null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (resources.registerDevice(camera)) {
                    opened = camera
                } else {
                    camera.close()
                }
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                errorCode = error
                camera.close()
                latch.countDown()
            }

            override fun onClosed(camera: CameraDevice) {
                resources.deviceClosed.countDown()
            }
        }, requireHandler())
        assertTrue("camera open timed out", latch.await(CAMERA_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("camera open error", null, errorCode)
        return opened ?: throw IllegalStateException("camera failed to open")
    }

    private fun captureSingleRaw(
        device: CameraDevice,
        reader: ImageReader,
        resources: CaptureResources,
    ): TotalCaptureResult {
        val sessionLatch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var configFailed = false
        val executor = Executor { command -> requireHandler().post(command) }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(reader.surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (resources.registerSession(configured)) {
                        session = configured
                    } else {
                        configured.close()
                    }
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    if (!resources.registerSession(failed)) failed.close()
                    configFailed = true
                    sessionLatch.countDown()
                }

                override fun onClosed(closed: CameraCaptureSession) {
                    resources.sessionClosed.countDown()
                }
            },
        )
        device.createCaptureSession(sessionConfig)
        assertTrue(
            "session configure timed out",
            sessionLatch.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertFalse("session configuration failed", configFailed)
        val active = session ?: throw IllegalStateException("no capture session")

        val resultLatch = CountDownLatch(1)
        var result: TotalCaptureResult? = null
        var captureFailure: CaptureFailure? = null
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }.build()
        active.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                captureResult: TotalCaptureResult,
            ) {
                result = captureResult
                resultLatch.countDown()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                captureFailure = failure
                resultLatch.countDown()
            }
        }, requireHandler())
        assertTrue("capture result timed out", resultLatch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return result ?: throw IllegalStateException(
            "capture produced no result; reason=${captureFailure?.reason}",
        )
    }

    private fun buildEvidence(
        status: String,
        source: DngRawPixelFidelityOracle.CanonicalRaw?,
        comparison: DngRawPixelFidelityOracle.Comparison?,
        rowStride: Int?,
        pixelStride: Int?,
        cleanupPassed: Boolean,
        cleanupErrors: List<String>,
        listenerDetached: Boolean,
        listenerDrained: Boolean,
        imageClosed: Boolean,
        readerClosed: Boolean,
        sessionClosed: Boolean,
        deviceClosed: Boolean,
        handlerThreadStopped: Boolean,
        failure: Throwable?,
    ): JSONObject {
        val dng = comparison?.dng
        return JSONObject()
            .put("checkpointId", "CP-143")
            .put("status", status)
            .put("sourceSha256", source?.sha256 ?: JSONObject.NULL)
            .put("dngSha256", dng?.sha256 ?: JSONObject.NULL)
            .put("width", source?.width ?: JSONObject.NULL)
            .put("height", source?.height ?: JSONObject.NULL)
            .put("sampleCount", source?.sampleCount ?: JSONObject.NULL)
            .put("sourceRowStride", rowStride ?: JSONObject.NULL)
            .put("sourcePixelStride", pixelStride ?: JSONObject.NULL)
            .put("dngIfdOffset", dng?.ifdOffset ?: JSONObject.NULL)
            .put("dngLayout", dng?.layout ?: JSONObject.NULL)
            .put("dngSegmentCount", dng?.segmentCount ?: JSONObject.NULL)
            .put("dngCompression", dng?.compression ?: JSONObject.NULL)
            .put("dngBitsPerSample", dng?.bitsPerSample ?: JSONObject.NULL)
            .put("dngByteOrder", dng?.byteOrder ?: JSONObject.NULL)
            .put("mismatchCount", comparison?.mismatchCount ?: JSONObject.NULL)
            .put("maxAbs", comparison?.maxAbs ?: JSONObject.NULL)
            .put(
                "cleanup",
                JSONObject()
                    .put("passed", cleanupPassed)
                    .put("listenerDetached", listenerDetached)
                    .put("listenerDrained", listenerDrained)
                    .put("imageClosed", imageClosed)
                    .put("readerClosed", readerClosed)
                    .put("sessionClosed", sessionClosed)
                    .put("deviceClosed", deviceClosed)
                    .put("handlerThreadStopped", handlerThreadStopped)
                    .put("errors", JSONArray(cleanupErrors)),
            )
            .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL)
            .put("failureMessage", failure?.message ?: JSONObject.NULL)
    }

    private fun writeEvidenceAtomically(context: Context, evidence: JSONObject): File {
        val directory = File(context.filesDir, "neo-qa/cp-143")
        check(directory.isDirectory || directory.mkdirs()) {
            "could not create CP-143 evidence directory: $directory"
        }
        val destination = File(directory, "raw-dng-pixel-fidelity.json")
        val temporary = File.createTempFile("raw-dng-pixel-fidelity-", ".tmp", directory)
        try {
            val payload = evidence.toString(2).toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            check(destination.readBytes().contentEquals(payload)) {
                "CP-143 evidence verification mismatch"
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun runCleanup(
        label: String,
        errors: MutableList<String>,
        action: () -> Unit,
    ): Boolean = try {
        action()
        true
    } catch (cleanupFailure: Throwable) {
        errors += "$label:${cleanupFailure.javaClass.simpleName}:${cleanupFailure.message}"
        false
    }

    private fun awaitCleanupLatch(
        latch: CountDownLatch,
        label: String,
        errors: MutableList<String>,
    ): Boolean = try {
        val completed = latch.await(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) errors += "$label:timeout"
        completed
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        errors += "$label:interrupted"
        false
    }

    private fun drainHandler(errors: MutableList<String>): Boolean {
        val activeHandler = handler ?: return true
        val barrier = CountDownLatch(1)
        if (!activeHandler.post { barrier.countDown() }) {
            errors += "handler.drain:post-rejected"
            return false
        }
        return awaitCleanupLatch(barrier, "handler.drain", errors)
    }

    private fun stopHandlerThread(errors: MutableList<String>): Boolean {
        val worker = thread ?: return true
        handler?.removeCallbacksAndMessages(null)
        worker.quitSafely()
        return try {
            worker.join(TimeUnit.SECONDS.toMillis(CLEANUP_TIMEOUT_SECONDS))
            val stopped = !worker.isAlive
            if (!stopped) errors += "handlerThread.stop:timeout"
            if (stopped) {
                thread = null
                handler = null
            }
            stopped
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            errors += "handlerThread.stop:interrupted"
            false
        }
    }

    private fun requireHandler(): Handler = requireNotNull(handler) { "handler thread is not active" }

    private class CaptureResources {
        private val lock = Any()
        private var cleaning = false
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null

        val deviceClosed = CountDownLatch(1)
        val sessionClosed = CountDownLatch(1)

        fun registerDevice(candidate: CameraDevice): Boolean = synchronized(lock) {
            if (cleaning) false else {
                device = candidate
                true
            }
        }

        fun registerSession(candidate: CameraCaptureSession): Boolean = synchronized(lock) {
            if (cleaning) false else {
                session = candidate
                true
            }
        }

        fun beginCleanup(): OwnedResources = synchronized(lock) {
            cleaning = true
            OwnedResources(session, device)
        }
    }

    private data class OwnedResources(
        val session: CameraCaptureSession?,
        val device: CameraDevice?,
    )

    private companion object {
        const val LOG_TAG = "NeoQaCp143"
        const val LOG_PREFIX = "NEO_QA_CP143_JSON="
        const val IMAGE_READER_DEPTH = 2
        const val CAMERA_TIMEOUT_SECONDS = 8L
        const val SESSION_TIMEOUT_SECONDS = 8L
        const val CAPTURE_TIMEOUT_SECONDS = 10L
        const val IMAGE_TIMEOUT_SECONDS = 10L
        const val CLEANUP_TIMEOUT_SECONDS = 5L
    }
}
