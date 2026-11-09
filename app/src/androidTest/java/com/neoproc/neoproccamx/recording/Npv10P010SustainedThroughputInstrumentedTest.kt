package com.neoproc.neoproccamx.recording

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraIntRange
import com.neoproc.neoproccamx.camera.CameraStreamBasedFpsOverridePlan
import com.neoproc.neoproccamx.camera.CameraStreamBasedFpsPolicy
import com.neoproc.neoproccamx.camera.CameraStreamBasedFpsRequestMode
import com.neoproc.neoproccamx.camera.CameraStreamSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * CP-169 physical-device ingress/packing sub-gate.
 *
 * This test deliberately keeps the production 4096x3072 P010 input and packed-ten-bit NPV10
 * payload sizes. It does not create a synthetic 35 GiB file: every real Camera2 Image is fused,
 * CRC-checked, and written into a worker-owned native packet buffer that is reused only after the
 * previous Image is closed. That measures the complete hardware-producer and production packing
 * path for 1,500 measured frames while avoiding a storage-capacity result masquerading as a
 * transport result. It does not, by itself, prove 60 seconds of durable sequential-file writes.
 * It is therefore never sufficient to pass canonical CP-169; the production writer/endurance gate
 * remains mandatory and independently measures ordered commit, storage, fsync, and publication.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class Npv10P010SustainedThroughputInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraClosedLatch: CountDownLatch? = null
    private val packingWorkersMayOwnImages = AtomicBoolean(false)

    @Before
    fun setUp() {
        cameraThread = HandlerThread("cp169-p010-sustained-camera").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
    }

    @After
    fun tearDown() {
        val cleanupFailures = mutableListOf<String>()
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        val cameraClosed = runCatching {
            cameraClosedLatch?.await(CAMERA_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: true
        }.getOrDefault(false)
        if (!cameraClosed) {
            cleanupFailures += "Camera2 onClosed was not observed before callback shutdown"
        }
        val workersRetainedOwnership = packingWorkersMayOwnImages.get()
        if (workersRetainedOwnership || !cameraClosed) {
            cleanupFailures += if (workersRetainedOwnership) {
                "ImageReader quarantined because a packing worker may still own an Image"
            } else {
                "ImageReader quarantined because Camera2 closure was not confirmed"
            }
        } else {
            runCatching { imageReader?.close() }
                .onFailure { cleanupFailures += "ImageReader close failed: ${it.message}" }
        }
        cameraThread.quitSafely()
        cameraThread.join(CAMERA_THREAD_JOIN_TIMEOUT_MS)
        if (cameraThread.isAlive) {
            cleanupFailures += "Camera callback thread did not terminate"
        }
        check(cleanupFailures.isEmpty()) {
            "CP-169 cleanup failed: ${cleanupFailures.joinToString(separator = "; ")}"
        }
    }

    @Test
    fun fullSensor4096x3072P010_sustains25FpsFor60SecondsWithoutOwnershipExhaustion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val source = findExactP010Source(manager)
        assumeTrue(
            "Device does not expose a back-camera 4096x3072 Camera2 P010 output",
            source != null
        )
        val admitted = requireNotNull(source)
        assertEquals(EXPECTED_SIZE, admitted.size)
        assertEquals(
            "CP-169 production admission must resolve an exact 25-25 FPS request; available=" +
                admitted.availableTargetRanges.joinToString() +
                " plan=${admitted.fpsPlan}",
            EXACT_TARGET_FPS_RANGE,
            admitted.targetRange
        )
        val exactTargetRange = requireNotNull(admitted.targetRange) {
            "Production FPS policy did not admit an exact 25-25 request"
        }
        if (!admitted.exactPublicRangeAdvertised) {
            assertTrue(
                "A non-advertised 25-25 range requires the production vendor FPS path: " +
                    "${admitted.fpsPlan}",
                admitted.fpsPlan.enabled &&
                    admitted.fpsPlan.requestMode != CameraStreamBasedFpsRequestMode.NONE &&
                    admitted.fpsPlan.appliedFps == TARGET_FPS
            )
        }

        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            EXPECTED_WIDTH,
            EXPECTED_HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        val storedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        assertEquals(EXPECTED_PACKED_BYTES_PER_FRAME, storedBytes)
        assertEquals(REQUIRED_PAYLOAD_BYTES_PER_SECOND, storedBytes.toLong() * TARGET_FPS)

        val pendingCapacity = Npv10InFlightBufferPolicy.pendingFrames(EXPECTED_WIDTH, EXPECTED_HEIGHT)
        val readerMaxImages = Npv10InFlightBufferPolicy.readerMaxImages(EXPECTED_WIDTH, EXPECTED_HEIGHT)
        assertEquals(
            Npv10InFlightBufferPolicy.READER_RESERVED_IMAGES,
            readerMaxImages - pendingCapacity
        )
        val reader = ImageReader.newInstance(
            EXPECTED_WIDTH,
            EXPECTED_HEIGHT,
            ImageFormat.YCBCR_P010,
            readerMaxImages
        )
        imageReader = reader

        val device = openCamera(manager, admitted.cameraId)
        cameraDevice = device
        val configuredSession = createSession(device, reader, admitted)
        val session = configuredSession.session
        captureSession = session
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, exactTargetRange)
        }
        val vendorRequestApplied = applyProductionVendorFpsKeys(
            builder = requestBuilder,
            plan = admitted.fpsPlan,
            keysByName = admitted.requestKeysByName
        )
        if (!admitted.exactPublicRangeAdvertised) {
            assertTrue(
                "The production vendor FPS contract was not applied to either the session or request",
                configuredSession.vendorFpsApplied || vendorRequestApplied
            )
        }
        val request = requestBuilder.build()
        assertEquals(
            "Built Camera2 request did not retain exact 25-25 FPS",
            EXACT_TARGET_FPS_RANGE,
            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        )
        val telemetry = captureSustainedFrames(
            session = session,
            request = request,
            reader = reader,
            storedBytes = storedBytes,
            pendingCapacity = pendingCapacity
        )

        assertEquals(TOTAL_FRAMES, telemetry.acquired)
        assertEquals(TOTAL_FRAMES, telemetry.submitted)
        assertEquals(TOTAL_FRAMES, telemetry.completed)
        assertEquals(TOTAL_FRAMES, telemetry.closed)
        assertEquals(0, telemetry.inFlightAtEnd)
        assertEquals(pendingCapacity, telemetry.availablePermitsAtEnd)
        assertTrue(
            "P010 ownership reached ${telemetry.maxInFlight}; admitted pending ceiling is $pendingCapacity",
            telemetry.maxInFlight <= pendingCapacity
        )
        assertTrue(
            "P010 executor queue reached ${telemetry.maxQueueDepth}; bounded ceiling is $MAX_QUEUE_DEPTH",
            telemetry.maxQueueDepth <= MAX_QUEUE_DEPTH
        )
        assertEquals(
            "Packing-worker accounting did not cover every submitted real P010 frame",
            TOTAL_FRAMES,
            telemetry.workerFrames.values.sum()
        )
        assertTrue(
            "A real P010 frame was attributed to an unregistered packing worker",
            telemetry.workerFrames.keys.all(telemetry.workerBufferIdentities::containsKey)
        )
        assertEquals(
            "Prewarm did not retain one packet buffer for every packing worker",
            PACKING_WORKERS,
            telemetry.workerBufferIdentities.size
        )
        assertEquals(
            "Each worker must retain an independent native packet buffer",
            PACKING_WORKERS,
            telemetry.workerBufferIdentities.values.toSet().size
        )
        assertTrue("The fused pack CRC accumulator was not populated", telemetry.crcObservations > 0)
        assertTrue(
            "Camera2 produced no capture result for exact FPS verification",
            telemetry.captureResultsObserved > 0
        )
        assertTrue(
            "Camera2 never reported CONTROL_AE_TARGET_FPS_RANGE",
            telemetry.captureResultsWithTargetRange > 0
        )
        assertEquals(
            "Camera2 did not apply the requested exact 25-25 FPS range",
            EXACT_TARGET_FPS_RANGE,
            telemetry.appliedTargetRange
        )
        assertEquals(
            "Camera2 changed the applied FPS range during the sustained run",
            0,
            telemetry.mismatchedTargetRangeResults
        )
        assertEquals(
            "Camera2 returned a capture result without SENSOR_TIMESTAMP",
            0,
            telemetry.captureResultsMissingSensorTimestamp
        )
        assertEquals(
            "Camera2 returned a capture result without CONTROL_AE_TARGET_FPS_RANGE",
            0,
            telemetry.captureResultsMissingTargetRange
        )

        val measuredSensor = telemetry.sensorTimestampsNs.copyOfRange(WARMUP_FRAMES, TOTAL_FRAMES)
        val measuredArrivals = telemetry.callbackArrivalsNs.copyOfRange(WARMUP_FRAMES, TOTAL_FRAMES)
        val measuredCompletions = telemetry.completionTimestampsNs.copyOfRange(WARMUP_FRAMES, TOTAL_FRAMES)
        assertEquals(MEASURED_FRAMES, measuredSensor.size)
        val missingAppliedResults = measuredSensor.filter { timestampNs ->
            !telemetry.appliedTargetRangesBySensorTimestamp.containsKey(timestampNs)
        }
        assertTrue(
            "Camera2 did not return an FPS result for measured Image timestamps: " +
                missingAppliedResults.take(MAX_REPORTED_MISSING_TIMESTAMPS),
            missingAppliedResults.isEmpty()
        )
        val nonExactAppliedResults = measuredSensor.asSequence().mapNotNull { timestampNs ->
            telemetry.appliedTargetRangesBySensorTimestamp[timestampNs]
                ?.takeIf { it != EXACT_TARGET_FPS_RANGE }
                ?.let { timestampNs to it }
        }.toList()
        assertTrue(
            "Camera2 did not apply exact 25-25 FPS to measured Image timestamps: " +
                nonExactAppliedResults.take(MAX_REPORTED_MISSING_TIMESTAMPS),
            nonExactAppliedResults.isEmpty()
        )
        assertStrictlyIncreasing("sensor timestamp", measuredSensor)
        assertStrictlyIncreasing("callback arrival", measuredArrivals)
        assertTrue("Every measured packet completion timestamp must be populated", measuredCompletions.all { it > 0L })

        val sensorElapsedNs = measuredSensor.last() - measuredSensor.first()
        val arrivalElapsedNs = measuredArrivals.last() - measuredArrivals.first()
        val sensorFps = (MEASURED_FRAMES - 1).toDouble() * NANOS_PER_SECOND / sensorElapsedNs
        val arrivalFps = (MEASURED_FRAMES - 1).toDouble() * NANOS_PER_SECOND / arrivalElapsedNs
        val payloadThroughput = storedBytes.toDouble() * (MEASURED_FRAMES - 1).toDouble() *
            NANOS_PER_SECOND / sensorElapsedNs.toDouble()
        val medianSensorPeriodNs = medianPositiveDelta(measuredSensor)
        val sensorWindowNs = sensorElapsedNs + medianSensorPeriodNs
        val maxSensorGapNs = maxPositiveDelta(measuredSensor)
        val maxCompletionLagNs = measuredCompletions.indices.maxOf { index ->
            (measuredCompletions[index] - measuredArrivals[index]).coerceAtLeast(0L)
        }

        Log.i(
            TAG,
            "CP169_SUSTAINED_RESULT camera=${admitted.cameraId} size=${EXPECTED_WIDTH}x${EXPECTED_HEIGHT} " +
                "requestedRange=${admitted.targetRange} measuredFrames=$MEASURED_FRAMES " +
                "sensorWindowSec=${format(sensorWindowNs / NANOS_PER_SECOND)} " +
                "sensorFps=${format(sensorFps)} arrivalFps=${format(arrivalFps)} " +
                "medianSensorPeriodMs=${format(medianSensorPeriodNs / NANOS_PER_MILLISECOND)} " +
                "appliedRange=${telemetry.appliedTargetRange} " +
                "packedBytesPerFrame=$storedBytes payloadBytesPerSec=${format(payloadThroughput)} " +
                "requiredBytesPerSec=$REQUIRED_PAYLOAD_BYTES_PER_SECOND maxSensorGapMs=" +
                "${format(maxSensorGapNs / NANOS_PER_MILLISECOND)} maxCompletionLagMs=" +
                "${format(maxCompletionLagNs / NANOS_PER_MILLISECOND)} maxInFlight=" +
                "${telemetry.maxInFlight} maxQueueDepth=${telemetry.maxQueueDepth} " +
                "ignoredAfterTarget=${telemetry.ignoredAfterTarget} workers=${telemetry.workerFrames.toSortedMap()} " +
                "crcAccumulator=${telemetry.crcAccumulator}"
        )

        assertTrue(
            "Measured window ${format(sensorWindowNs / NANOS_PER_SECOND)}s is shorter than 60s",
            sensorWindowNs >= REQUIRED_WINDOW_NS
        )
        assertTrue(
            "Sustained packed payload ${format(payloadThroughput)} B/s is below " +
                "$REQUIRED_PAYLOAD_BYTES_PER_SECOND B/s",
            payloadThroughput >= REQUIRED_PAYLOAD_BYTES_PER_SECOND.toDouble()
        )
        assertTrue(
            "Sensor cadence ${format(sensorFps)} fps is below $TARGET_FPS fps",
            sensorFps >= TARGET_FPS.toDouble()
        )
        assertTrue(
            "Sensor cadence ${format(sensorFps)} fps is not tightly centered on $TARGET_FPS fps",
            abs(sensorFps - TARGET_FPS.toDouble()) <= MAX_SENSOR_FPS_DEVIATION
        )
        assertTrue(
            "Median sensor period ${format(medianSensorPeriodNs / NANOS_PER_MILLISECOND)}ms " +
                "is not centered on ${format(TARGET_SENSOR_PERIOD_NS / NANOS_PER_MILLISECOND)}ms",
            abs(medianSensorPeriodNs - TARGET_SENSOR_PERIOD_NS) <= MAX_MEDIAN_SENSOR_PERIOD_DEVIATION_NS
        )
        assertTrue(
            "Callback cadence ${format(arrivalFps)} fps is below the ${format(MIN_CALLBACK_FPS)} fps floor",
            arrivalFps >= MIN_CALLBACK_FPS
        )
        assertTrue(
            "Sensor gap ${format(maxSensorGapNs / NANOS_PER_MILLISECOND)}ms indicates a dropped frame",
            maxSensorGapNs <= MAX_SENSOR_GAP_NS
        )
        assertTrue(
            "Callback-to-packed completion lag ${format(maxCompletionLagNs / NANOS_PER_MILLISECOND)}ms " +
                "exceeds the bounded ownership budget",
            maxCompletionLagNs <= MAX_COMPLETION_LAG_NS
        )
    }

    private fun captureSustainedFrames(
        session: CameraCaptureSession,
        request: CaptureRequest,
        reader: ImageReader,
        storedBytes: Int,
        pendingCapacity: Int
    ): SustainedTelemetry {
        val finished = CountDownLatch(TOTAL_FRAMES)
        val callbackReturned = CountDownLatch(TOTAL_FRAMES)
        val accepting = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val sequence = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val submitted = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val ignored = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val maxQueueDepth = AtomicInteger(0)
        val crcAccumulator = AtomicLong(0L)
        val crcObservations = AtomicInteger(0)
        val captureResultsObserved = AtomicInteger(0)
        val captureResultsWithTargetRange = AtomicInteger(0)
        val captureResultsMissingSensorTimestamp = AtomicInteger(0)
        val captureResultsMissingTargetRange = AtomicInteger(0)
        val mismatchedTargetRangeResults = AtomicInteger(0)
        val appliedTargetRange = AtomicReference<Range<Int>?>(null)
        val appliedTargetRangesBySensorTimestamp = ConcurrentHashMap<Long, Range<Int>>()
        val sensorTimestamps = LongArray(TOTAL_FRAMES)
        val callbackArrivals = LongArray(TOTAL_FRAMES)
        val completionTimestamps = LongArray(TOTAL_FRAMES)
        val permits = Semaphore(pendingCapacity, true)
        val workerIndex = AtomicInteger(0)
        val workerFrames = ConcurrentHashMap<String, AtomicInteger>()
        val workerBuffers = ConcurrentHashMap<String, Int>()
        val workerLeases = ConcurrentHashMap<String, Npv10NativeFrameBuffer>()
        val outstanding = ConcurrentHashMap<Int, TrackedImage>()
        val workerLease = ThreadLocal.withInitial {
            val name = Thread.currentThread().name
            val allocated = allocatePrefaultedNativeBuffer(storedBytes)
            val existing = workerLeases.putIfAbsent(name, allocated)
            if (existing == null) allocated else existing.also { allocated.close() }
        }
        val executor = Executors.newFixedThreadPool(PACKING_WORKERS) { runnable ->
            object : Thread(runnable, "CP169-P010-Pack-${workerIndex.incrementAndGet()}") {
                init {
                    priority = Thread.NORM_PRIORITY + 2
                    isDaemon = true
                }

                override fun run() {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                    super.run()
                }
            }
        } as ThreadPoolExecutor

        lateinit var drain: Runnable
        fun releasePermit() {
            permits.release()
            if (accepting.get()) cameraHandler.post(drain)
        }
        drain = Runnable {
            if (!accepting.get() || !permits.tryAcquire()) return@Runnable
            val callbackStartedNs = SystemClock.elapsedRealtimeNanos()
            val image = runCatching { reader.acquireNextImage() }
                .onFailure { failure.compareAndSet(null, it) }
                .getOrNull()
            if (image == null) {
                permits.release()
                return@Runnable
            }
            val index = sequence.getAndIncrement()
            if (index >= TOTAL_FRAMES) {
                image.close()
                ignored.incrementAndGet()
                releasePermit()
                return@Runnable
            }
            acquired.incrementAndGet()
            callbackArrivals[index] = callbackStartedNs
            sensorTimestamps[index] = image.timestamp
            val ownership = TrackedImage(image) {
                closed.incrementAndGet()
                inFlight.decrementAndGet()
                releasePermit()
            }
            outstanding[index] = ownership
            maxInFlight.updateMax(inFlight.incrementAndGet())
            try {
                executor.execute {
                    try {
                        val workerName = Thread.currentThread().name
                        val lease = requireNotNull(workerLease.get()) {
                            "Packing worker $workerName did not retain a native packet buffer"
                        }
                        val bufferIdentity = System.identityHashCode(lease.buffer)
                        check(workerBuffers[workerName] == bufferIdentity) {
                            "Packing worker $workerName changed its prewarmed packet buffer"
                        }
                        workerFrames.computeIfAbsent(workerName) { AtomicInteger() }
                            .incrementAndGet()
                        lease.buffer.clear()
                        val result = Npv10FramePacker.packP010ImageToPackedTenBitBufferWithCrcResult(
                            image,
                            lease.buffer
                        )
                        check(result.path == Npv10FramePacker.P010CopyPath.NATIVE_DIRECT)
                        check(result.bytesCopied == storedBytes)
                        val crc = requireNotNull(result.crc32)
                        crcAccumulator.getAndUpdate { current -> current xor crc.toLong() }
                        crcObservations.incrementAndGet()
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        completionTimestamps[index] = SystemClock.elapsedRealtimeNanos()
                        completed.incrementAndGet()
                        outstanding.remove(index, ownership)
                        ownership.closeExactlyOnce()
                        finished.countDown()
                    }
                }
                submitted.incrementAndGet()
                maxQueueDepth.updateMax(executor.queue.size)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                completionTimestamps[index] = SystemClock.elapsedRealtimeNanos()
                outstanding.remove(index, ownership)
                ownership.closeExactlyOnce()
                finished.countDown()
            } finally {
                callbackReturned.countDown()
            }
        }
        var allFinished = false
        var allCallbacksReturned = false
        var callbackThreadQuiesced = false
        var workersTerminated = false
        try {
            val prewarmReady = CountDownLatch(PACKING_WORKERS)
            val prewarmRelease = CountDownLatch(1)
            val prewarmDone = CountDownLatch(PACKING_WORKERS)
            repeat(PACKING_WORKERS) {
                executor.execute {
                    try {
                        val workerName = Thread.currentThread().name
                        val lease = requireNotNull(workerLease.get()) {
                            "Prewarm worker $workerName did not allocate a native packet buffer"
                        }
                        workerBuffers[workerName] = System.identityHashCode(lease.buffer)
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        prewarmReady.countDown()
                        runCatching {
                            prewarmRelease.await(PREWARM_RELEASE_SECONDS, TimeUnit.SECONDS)
                        }.onFailure { failure.compareAndSet(null, it) }
                        prewarmDone.countDown()
                    }
                }
            }
            val allWorkersReady = try {
                prewarmReady.await(PREWARM_READY_SECONDS, TimeUnit.SECONDS)
            } finally {
                prewarmRelease.countDown()
            }
            val allWorkersReleased = prewarmDone.await(PREWARM_RELEASE_SECONDS, TimeUnit.SECONDS)
            assertTrue("Not all $PACKING_WORKERS packing workers prewarmed", allWorkersReady)
            assertTrue("Prewarmed packing workers did not release", allWorkersReleased)
            failure.get()?.let { throw AssertionError("P010 worker prewarm failed", it) }
            assertEquals(
                "Prewarm did not allocate one independent packet buffer per worker",
                PACKING_WORKERS,
                workerBuffers.values.toSet().size
            )

            packingWorkersMayOwnImages.set(true)
            reader.setOnImageAvailableListener({ drain.run() }, cameraHandler)
            session.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        captureResultsObserved.incrementAndGet()
                        val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (sensorTimestampNs == null) {
                            captureResultsMissingSensorTimestamp.incrementAndGet()
                            return
                        }
                        val reported = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                        if (reported == null) {
                            captureResultsMissingTargetRange.incrementAndGet()
                            return
                        }
                        captureResultsWithTargetRange.incrementAndGet()
                        val previousForTimestamp = appliedTargetRangesBySensorTimestamp.putIfAbsent(
                            sensorTimestampNs,
                            reported
                        )
                        val first = appliedTargetRange.get()
                        if (first == null) {
                            appliedTargetRange.compareAndSet(null, reported)
                        }
                        if (
                            reported != EXACT_TARGET_FPS_RANGE ||
                            (first != null && first != reported) ||
                            (previousForTimestamp != null && previousForTimestamp != reported)
                        ) {
                            mismatchedTargetRangeResults.incrementAndGet()
                        }
                    }
                },
                cameraHandler
            )
            allFinished = finished.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            allCallbacksReturned = callbackReturned.await(
                CALLBACK_DRAIN_SECONDS,
                TimeUnit.SECONDS
            )
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        } finally {
            accepting.set(false)
            runCatching { session.stopRepeating() }
                .onFailure { failure.compareAndSet(null, it) }
            runCatching { session.abortCaptures() }
                .onFailure { failure.compareAndSet(null, it) }
            runCatching { reader.setOnImageAvailableListener(null, null) }
                .onFailure { failure.compareAndSet(null, it) }

            val callbacksQuiesced = CountDownLatch(1)
            runCatching { cameraHandler.post { callbacksQuiesced.countDown() } }
                .onFailure { failure.compareAndSet(null, it) }
            callbackThreadQuiesced = runCatching {
                callbacksQuiesced.await(CALLBACK_DRAIN_SECONDS, TimeUnit.SECONDS)
            }.onFailure { failure.compareAndSet(null, it) }.getOrDefault(false)

            executor.shutdown()
            workersTerminated = runCatching {
                executor.awaitTermination(WORKER_DRAIN_SECONDS, TimeUnit.SECONDS)
            }.onFailure { failure.compareAndSet(null, it) }.getOrDefault(false)
            if (!workersTerminated) {
                executor.shutdownNow()
                workersTerminated = runCatching {
                    executor.awaitTermination(WORKER_FORCE_STOP_SECONDS, TimeUnit.SECONDS)
                }.onFailure { failure.compareAndSet(null, it) }.getOrDefault(false)
            }

            if (workersTerminated) {
                // No worker can still read an Image after termination, so rejected/cancelled tasks
                // may now be closed without racing the native packer.
                outstanding.values.forEach(TrackedImage::closeExactlyOnce)
                outstanding.clear()
                workerLeases.values.forEach { lease ->
                    runCatching { lease.close() }
                        .onFailure { failure.compareAndSet(null, it) }
                }
                workerLeases.clear()
                packingWorkersMayOwnImages.set(false)
            } else {
                failure.compareAndSet(
                    null,
                    IllegalStateException(
                        "Packing workers did not terminate; ImageReader retained to prevent " +
                            "force-closing worker-owned Images"
                    )
                )
            }
        }

        failure.get()?.let { throw AssertionError("Sustained P010 packing failed", it) }
        assertTrue("Did not pack $TOTAL_FRAMES real P010 frames before timeout", allFinished)
        assertTrue("Not all Camera2 ownership callbacks returned", allCallbacksReturned)
        assertTrue("Camera callback thread did not quiesce", callbackThreadQuiesced)
        assertTrue("Packing workers did not terminate", workersTerminated)
        assertTrue("Missing sensor timestamps", sensorTimestamps.all { it > 0L })
        assertTrue("Missing callback arrival timestamps", callbackArrivals.all { it > 0L })
        assertTrue("Missing completion timestamps", completionTimestamps.all { it > 0L })

        return SustainedTelemetry(
            acquired = acquired.get(),
            submitted = submitted.get(),
            completed = completed.get(),
            closed = closed.get(),
            ignoredAfterTarget = ignored.get(),
            maxInFlight = maxInFlight.get(),
            maxQueueDepth = maxQueueDepth.get(),
            inFlightAtEnd = inFlight.get(),
            availablePermitsAtEnd = permits.availablePermits(),
            sensorTimestampsNs = sensorTimestamps,
            callbackArrivalsNs = callbackArrivals,
            completionTimestampsNs = completionTimestamps,
            workerFrames = workerFrames.mapValues { it.value.get() },
            workerBufferIdentities = workerBuffers.toMap(),
            crcAccumulator = crcAccumulator.get(),
            crcObservations = crcObservations.get(),
            captureResultsObserved = captureResultsObserved.get(),
            captureResultsWithTargetRange = captureResultsWithTargetRange.get(),
            captureResultsMissingSensorTimestamp = captureResultsMissingSensorTimestamp.get(),
            captureResultsMissingTargetRange = captureResultsMissingTargetRange.get(),
            appliedTargetRange = appliedTargetRange.get(),
            appliedTargetRangesBySensorTimestamp = appliedTargetRangesBySensorTimestamp.toMap(),
            mismatchedTargetRangeResults = mismatchedTargetRangeResults.get()
        )
    }

    private fun findExactP010Source(manager: CameraManager): P010Source? {
        val candidates = manager.cameraIdList.asSequence().mapNotNull { cameraId ->
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) return@mapNotNull null
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YCBCR_P010)
                .orEmpty()
            if (EXPECTED_SIZE !in sizes) return@mapNotNull null
            val ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ).orEmpty()
            val requestKeys = runCatching {
                characteristics.availableCaptureRequestKeys.associateBy { it.name }
            }.getOrDefault(emptyMap())
            val sessionKeys = runCatching {
                characteristics.availableSessionKeys?.associateBy { it.name }.orEmpty()
            }.getOrDefault(emptyMap())
            val fpsPlan = CameraStreamBasedFpsPolicy.resolve(
                requestedSize = CameraStreamSize(EXPECTED_WIDTH, EXPECTED_HEIGHT),
                requestedFps = TARGET_FPS,
                publicAeRanges = ranges.map { CameraIntRange(it.lower, it.upper) },
                streamBasedEntries = CameraStreamBasedFpsPolicy.parseStreamBasedTable(
                    readStreamBasedFpsTable(characteristics)
                ),
                requestKeyNames = requestKeys.keys,
                sessionKeyNames = sessionKeys.keys
            )
            val exactPublicRange = ranges.firstOrNull { it == EXACT_TARGET_FPS_RANGE }
            val productionTargetRange = exactPublicRange ?: fpsPlan.publicAeRange?.let { range ->
                Range(range.lower, range.upper)
            }
            P010Source(
                cameraId = cameraId,
                size = EXPECTED_SIZE,
                targetRange = productionTargetRange,
                exactPublicRangeAdvertised = exactPublicRange != null,
                availableTargetRanges = ranges.sortedWith(compareBy({ it.lower }, { it.upper })),
                fpsPlan = fpsPlan,
                requestKeysByName = requestKeys,
                sessionKeysByName = sessionKeys
            )
        }.toList()
        return candidates.firstOrNull {
            it.exactPublicRangeAdvertised && it.targetRange == EXACT_TARGET_FPS_RANGE
        } ?: candidates.firstOrNull {
            it.targetRange == EXACT_TARGET_FPS_RANGE &&
                it.fpsPlan.enabled &&
                it.fpsPlan.appliedFps == TARGET_FPS
        }
            ?: candidates.firstOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStreamBasedFpsTable(characteristics: CameraCharacteristics): IntArray? {
        val key = characteristics.keys.firstOrNull {
            it.name == CameraStreamBasedFpsPolicy.STREAM_TABLE_KEY_NAME
        } ?: return null
        return runCatching {
            characteristics.get(key as CameraCharacteristics.Key<IntArray>)
        }.getOrNull()
    }

    private fun openCamera(manager: CameraManager, cameraId: String): CameraDevice {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        cameraClosedLatch = closed
        val callbackGate = Any()
        var acceptingResult = true
        var device: CameraDevice? = null
        var error: String? = null
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                val accepted = synchronized(callbackGate) {
                    if (!acceptingResult) {
                        false
                    } else {
                        acceptingResult = false
                        device = camera
                        true
                    }
                }
                if (accepted) opened.countDown() else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                val accepted = synchronized(callbackGate) {
                    if (!acceptingResult) {
                        false
                    } else {
                        acceptingResult = false
                        error = "Camera disconnected while opening"
                        true
                    }
                }
                camera.close()
                if (accepted) opened.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                val accepted = synchronized(callbackGate) {
                    if (!acceptingResult) {
                        false
                    } else {
                        acceptingResult = false
                        error = "Camera open failed error=$code"
                        true
                    }
                }
                camera.close()
                if (accepted) opened.countDown()
            }

            override fun onClosed(camera: CameraDevice) {
                closed.countDown()
            }
        }, cameraHandler)
        val openedInTime = opened.await(15L, TimeUnit.SECONDS)
        if (!openedInTime) {
            val lateAcceptedDevice = synchronized(callbackGate) {
                acceptingResult = false
                device.also { device = null }
            }
            lateAcceptedDevice?.close()
        }
        assertTrue("Timed out opening Camera2 source", openedInTime)
        check(error == null) { requireNotNull(error) }
        return requireNotNull(device)
    }

    private fun createSession(
        device: CameraDevice,
        reader: ImageReader,
        source: P010Source
    ): ConfiguredSession {
        val configured = CountDownLatch(1)
        val callbackGate = Any()
        var acceptingResult = true
        var session: CameraCaptureSession? = null
        var failed = false
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                val accepted = synchronized(callbackGate) {
                    if (!acceptingResult) {
                        false
                    } else {
                        acceptingResult = false
                        session = value
                        true
                    }
                }
                if (accepted) configured.countDown() else value.close()
            }

            override fun onConfigureFailed(value: CameraCaptureSession) {
                val accepted = synchronized(callbackGate) {
                    if (!acceptingResult) {
                        false
                    } else {
                        acceptingResult = false
                        failed = true
                        true
                    }
                }
                value.close()
                if (accepted) configured.countDown()
            }
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(reader.surface)),
            { command -> cameraHandler.post(command) },
            callback
        )
        val sessionParameterBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        val vendorFpsApplied = applyProductionVendorFpsKeys(
            builder = sessionParameterBuilder,
            plan = source.fpsPlan,
            keysByName = source.sessionKeysByName
        )
        if (vendorFpsApplied) {
            configuration.setSessionParameters(sessionParameterBuilder.build())
        }
        device.createCaptureSession(configuration)
        val configuredInTime = configured.await(15L, TimeUnit.SECONDS)
        if (!configuredInTime) {
            val lateAcceptedSession = synchronized(callbackGate) {
                acceptingResult = false
                session.also { session = null }
            }
            lateAcceptedSession?.close()
        }
        assertTrue("Timed out configuring exact P010 session", configuredInTime)
        assertTrue("HAL rejected its advertised 4096x3072 P010 stream", !failed)
        return ConfiguredSession(
            session = requireNotNull(session),
            vendorFpsApplied = vendorFpsApplied
        )
    }

    private fun applyProductionVendorFpsKeys(
        builder: CaptureRequest.Builder,
        plan: CameraStreamBasedFpsOverridePlan,
        keysByName: Map<String, CaptureRequest.Key<*>>
    ): Boolean {
        if (!plan.enabled) return false

        fun setNumeric(name: String, value: Float): Boolean {
            val key = keysByName[name] ?: return false
            val candidates = when (name) {
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME ->
                    listOf("floatArray", "intArray", "float", "int")
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME ->
                    listOf("intArray", "floatArray", "int", "float")
                else -> listOf("int", "intArray", "float", "floatArray")
            }
            return candidates.any { candidate ->
                runCatching {
                    applyNumericCaptureRequestKey(builder, key, candidate, value)
                }.isSuccess
            }
        }

        return when (plan.requestMode) {
            CameraStreamBasedFpsRequestMode.STREAM_PREVIEW_VIDEO -> {
                val previewApplied = setNumeric(
                    CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME,
                    plan.previewFps.toFloat()
                )
                val videoApplied = setNumeric(
                    CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME,
                    plan.videoFps.toFloat()
                )
                previewApplied || videoApplied
            }
            CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS -> {
                val dynamicApplied = plan.dynamicFpsConfig?.let { value ->
                    setNumeric(CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME, value)
                } ?: false
                val videoApplied = plan.vendorVideoFrameRate?.let { value ->
                    setNumeric(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME, value.toFloat())
                } ?: false
                dynamicApplied || videoApplied
            }
            CameraStreamBasedFpsRequestMode.NONE -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyNumericCaptureRequestKey(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<*>,
        candidate: String,
        value: Float
    ) {
        when (candidate) {
            "int" -> builder.set(key as CaptureRequest.Key<Int>, value.toInt())
            "float" -> builder.set(key as CaptureRequest.Key<Float>, value)
            "intArray" -> builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value.toInt()))
            "floatArray" -> builder.set(key as CaptureRequest.Key<FloatArray>, floatArrayOf(value))
            else -> error("Unsupported vendor FPS value candidate: $candidate")
        }
    }

    private fun allocatePrefaultedNativeBuffer(capacity: Int): Npv10NativeFrameBuffer =
        Npv10NativeFrameBuffer.allocate(capacity).also { lease ->
            var offset = 0
            while (offset < capacity) {
                lease.buffer.put(offset, 0.toByte())
                offset += MEMORY_PAGE_BYTES
            }
            lease.buffer.put(capacity - 1, 0.toByte())
            lease.buffer.clear()
            lease.buffer.limit(capacity)
        }

    private fun assertStrictlyIncreasing(label: String, values: LongArray) {
        val invalid = (1 until values.size).firstOrNull { values[it] <= values[it - 1] }
        assertTrue(
            "$label is not strictly increasing at index=$invalid",
            invalid == null
        )
    }

    private fun medianPositiveDelta(values: LongArray): Long {
        val deltas = values.asList().zipWithNext { left, right -> right - left }
            .filter { it > 0L }
            .sorted()
        require(deltas.isNotEmpty())
        return deltas[deltas.size / 2]
    }

    private fun maxPositiveDelta(values: LongArray): Long =
        values.asList().zipWithNext { left, right -> right - left }.maxOrNull() ?: 0L

    private fun AtomicInteger.updateMax(value: Int) {
        var current = get()
        while (value > current && !compareAndSet(current, value)) current = get()
    }

    private class TrackedImage(
        private val image: Image,
        private val onClose: () -> Unit
    ) {
        private val closed = AtomicBoolean(false)

        fun closeExactlyOnce() {
            if (!closed.compareAndSet(false, true)) return
            try {
                image.close()
            } finally {
                onClose()
            }
        }
    }

    private data class ConfiguredSession(
        val session: CameraCaptureSession,
        val vendorFpsApplied: Boolean
    )

    private data class P010Source(
        val cameraId: String,
        val size: Size,
        val targetRange: Range<Int>?,
        val exactPublicRangeAdvertised: Boolean,
        val availableTargetRanges: List<Range<Int>>,
        val fpsPlan: CameraStreamBasedFpsOverridePlan,
        val requestKeysByName: Map<String, CaptureRequest.Key<*>>,
        val sessionKeysByName: Map<String, CaptureRequest.Key<*>>
    )

    private data class SustainedTelemetry(
        val acquired: Int,
        val submitted: Int,
        val completed: Int,
        val closed: Int,
        val ignoredAfterTarget: Int,
        val maxInFlight: Int,
        val maxQueueDepth: Int,
        val inFlightAtEnd: Int,
        val availablePermitsAtEnd: Int,
        val sensorTimestampsNs: LongArray,
        val callbackArrivalsNs: LongArray,
        val completionTimestampsNs: LongArray,
        val workerFrames: Map<String, Int>,
        val workerBufferIdentities: Map<String, Int>,
        val crcAccumulator: Long,
        val crcObservations: Int,
        val captureResultsObserved: Int,
        val captureResultsWithTargetRange: Int,
        val captureResultsMissingSensorTimestamp: Int,
        val captureResultsMissingTargetRange: Int,
        val appliedTargetRange: Range<Int>?,
        val appliedTargetRangesBySensorTimestamp: Map<Long, Range<Int>>,
        val mismatchedTargetRangeResults: Int
    )

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private companion object {
        const val TAG = "CP169P010Sustained"
        const val EXPECTED_WIDTH = 4096
        const val EXPECTED_HEIGHT = 3072
        val EXPECTED_SIZE = Size(EXPECTED_WIDTH, EXPECTED_HEIGHT)
        const val TARGET_FPS = 25
        val EXACT_TARGET_FPS_RANGE = Range(TARGET_FPS, TARGET_FPS)
        const val WARMUP_FRAMES = 4
        // A count of exactly 1,500 frames is not a 60-second proof when the admitted hardware
        // cadence is slightly above nominal (25.016 FPS on the iQOO target produced 59.963 s).
        // Capture enough samples to exceed 60 seconds even at the allowed 25.25 FPS ceiling. This
        // strengthens the duration gate without relaxing cadence, resolution, ownership, or payload
        // throughput thresholds.
        const val MEASURED_FRAMES = 1_516
        const val TOTAL_FRAMES = WARMUP_FRAMES + MEASURED_FRAMES
        const val PACKING_WORKERS = 3
        const val EXPECTED_PACKED_BYTES_PER_FRAME = 23_592_960
        const val REQUIRED_PAYLOAD_BYTES_PER_SECOND = 589_824_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val REQUIRED_WINDOW_NS = 60_000_000_000L
        const val MAX_SENSOR_GAP_NS = 60_000_000L
        const val TARGET_SENSOR_PERIOD_NS = 40_000_000L
        const val MAX_MEDIAN_SENSOR_PERIOD_DEVIATION_NS = 1_000_000L
        const val MAX_SENSOR_FPS_DEVIATION = 0.25
        const val MAX_COMPLETION_LAG_NS = 120_000_000L
        const val MIN_CALLBACK_FPS = 24.75
        const val MAX_QUEUE_DEPTH = 2
        const val CAPTURE_TIMEOUT_SECONDS = 90L
        const val CALLBACK_DRAIN_SECONDS = 10L
        const val PREWARM_READY_SECONDS = 20L
        const val PREWARM_RELEASE_SECONDS = 10L
        const val WORKER_DRAIN_SECONDS = 15L
        const val WORKER_FORCE_STOP_SECONDS = 5L
        const val CAMERA_CLOSE_TIMEOUT_SECONDS = 5L
        const val CAMERA_THREAD_JOIN_TIMEOUT_MS = 5_000L
        const val MEMORY_PAGE_BYTES = 4 * 1024
        const val MAX_REPORTED_MISSING_TIMESTAMPS = 8
    }
}
