package com.neoproc.neoproccamx.recording

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.Locale

/**
 * Physical Camera2 proof for the fused P010-plane -> packed-10 NPV10 recording path.
 *
 * The optimized path is accepted only when it is byte-identical to the canonical direct-plane copy
 * followed by [Npv10PacketCodecEngine]. The decoded packet is also compared with the original P010
 * words, so a matching but incorrectly ordered encoder/decoder pair cannot hide data corruption.
 * Fresh-frame performance is measured through the same prestarted urgent dispatch topology used by
 * production. The Camera2 callback transfers Image ownership without doing full-frame work, and the
 * dispatcher fans each packet across the persistent native row helpers. Capacity, callback
 * occupancy, queue delay, and service time are gated independently.
 */
@RunWith(AndroidJUnit4::class)
class Npv10FusedP010PackInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraClosedLatch: CountDownLatch? = null

    @Before
    fun setUp() {
        cameraThread = HandlerThread("npv10-fused-p010-parity").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
    }

    @After
    fun tearDown() {
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        val cameraClosed = runCatching {
            cameraClosedLatch?.await(CAMERA_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: true
        }.getOrDefault(false)
        if (!cameraClosed) {
            Log.w(TAG, "Timed out waiting for Camera2 onClosed before stopping callback thread")
        }
        runCatching { imageReader?.close() }
        cameraThread.quitSafely()
        cameraThread.join(5_000L)
    }

    @Test
    fun cameraP010FusedPacket_isByteIdenticalAndRoundTripsEveryWord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val source = findP010Camera(manager)
        assumeTrue("No Camera2 P010 output is available", source != null)
        val (cameraId, size) = requireNotNull(source)
        val pendingFrameCapacity = Npv10InFlightBufferPolicy.pendingFrames(size.width, size.height)
        val readerMaxImages = Npv10InFlightBufferPolicy.readerMaxImages(size.width, size.height)
        assertEquals(
            "Physical P010 proof must retain production's two ImageReader reserve slots",
            Npv10InFlightBufferPolicy.READER_RESERVED_IMAGES,
            readerMaxImages - pendingFrameCapacity
        )

        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YCBCR_P010,
            readerMaxImages
        )
        imageReader = reader
        val imageLatch = CountDownLatch(1)
        var capturedImage: Image? = null
        reader.setOnImageAvailableListener({ available ->
            val latest = runCatching { available.acquireLatestImage() }.getOrNull()
            if (latest != null) {
                capturedImage?.close()
                capturedImage = latest
                imageLatch.countDown()
            }
        }, cameraHandler)

        val device = openCamera(manager, cameraId)
        cameraDevice = device
        val session = createSession(device, reader)
        captureSession = session
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {}, cameraHandler)
        assertTrue("Camera2 did not deliver a P010 validation frame", imageLatch.await(15L, TimeUnit.SECONDS))

        requireNotNull(capturedImage).use { image ->
            val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                image.width,
                image.height,
                Npv10PayloadFormat.P010_LE
            ).toInt()
            val storedBytes = Npv10PacketCodecEngine.storedBytes(
                decodedBytes.toLong(),
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            ).toInt()
            val parityBuffers = ArrayList<Npv10NativeFrameBuffer>(6)
            try {
                parityBuffers += allocatePrefaultedNativeBuffer(decodedBytes)
                parityBuffers += allocatePrefaultedNativeBuffer(storedBytes)
                parityBuffers += allocatePrefaultedNativeBuffer(storedBytes)
                parityBuffers += allocatePrefaultedNativeBuffer(decodedBytes)
                parityBuffers += allocatePrefaultedNativeBuffer(storedBytes)
                parityBuffers += allocatePrefaultedNativeBuffer(storedBytes)
                val canonicalP010 = parityBuffers[0].buffer
                val canonicalPacket = parityBuffers[1].buffer
                val fusedPacket = parityBuffers[2].buffer
                val restoredP010 = parityBuffers[3].buffer
                val niv99IdentityPacket = parityBuffers[4].buffer
                val niv99EffectPacket = parityBuffers[5].buffer
                var directMappedPackNs = 0L

                val copyStartedNs = SystemClock.elapsedRealtimeNanos()
            val copyResult = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(
                image,
                canonicalP010
            )
            val copyNs = SystemClock.elapsedRealtimeNanos() - copyStartedNs
            repeat(ENCODE_WARMUP_RUNS) {
                Npv10PacketCodecEngine.encodeWithCrc32(
                    decoded = canonicalP010,
                    decodedBytes = decodedBytes,
                    codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                    target = canonicalPacket
                )
            }
            val encodeSamplesNs = LongArray(ENCODE_MEASURED_RUNS)
            lateinit var canonicalEncode: Npv10PacketEncodeResult
            repeat(ENCODE_MEASURED_RUNS) { run ->
                val encodeStartedNs = SystemClock.elapsedRealtimeNanos()
                canonicalEncode = Npv10PacketCodecEngine.encodeWithCrc32(
                    decoded = canonicalP010,
                    decodedBytes = decodedBytes,
                    codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                    target = canonicalPacket
                )
                encodeSamplesNs[run] = SystemClock.elapsedRealtimeNanos() - encodeStartedNs
            }
            val sortedEncodeNs = encodeSamplesNs.sorted()
            val encodeMedianNs = sortedEncodeNs[sortedEncodeNs.size / 2]
            val encodeP95Ns = percentile(sortedEncodeNs, 0.95)
            val encodeAverageNs = encodeSamplesNs.average()
            val encodeMaxNs = encodeSamplesNs.max()

            val fusedStartedNs = SystemClock.elapsedRealtimeNanos()
            val fusedResult = Npv10FramePacker.packP010ImageToPackedTenBitBufferWithCrcResult(
                image,
                fusedPacket
            )
            val fusedNs = SystemClock.elapsedRealtimeNanos() - fusedStartedNs

            assertEquals(decodedBytes, copyResult.bytesCopied)
            assertEquals(Npv10FramePacker.P010CopyPath.NATIVE_DIRECT, copyResult.path)
            assertEquals(storedBytes, canonicalEncode.encodedBytes)
            val nativeCrc = requireNotNull(canonicalEncode.crc32) {
                "Full-sensor direct P010 packet did not use native fused CRC"
            }
            val referenceCrc = CRC32().apply { update(canonicalPacket.duplicate()) }.value.toInt()
            assertEquals("Native cache-hot CRC differs from reference CRC32", referenceCrc, nativeCrc)
            assertEquals(storedBytes, fusedResult.bytesCopied)
            assertEquals(Npv10FramePacker.P010CopyPath.NATIVE_DIRECT, fusedResult.path)
            assertEquals(
                "Direct Camera2 plane packet CRC differs from canonical packet CRC",
                referenceCrc,
                requireNotNull(fusedResult.crc32) {
                    "Standard Camera2 P010 planes did not use the fused native CRC path"
                }
            )
            assertBuffersEqual("Fused packet differs from canonical packed P010", canonicalPacket, fusedPacket)

            // Production recording now performs this exact Camera2-plane -> packed-10 -> DMC
            // traversal. Prove it against the independent canonical copy+codec reference before
            // the Image is closed, including the CRC that is committed into the frame header.
            val dmcFile = java.io.File(
                context.cacheDir,
                "npv10-camera-dmc-parity-${System.nanoTime()}.bin"
            )
            val dmcDataStart = 4_096L
            val dmcFrameHeaderBytes = 28
            val dmcReservedBytes = dmcDataStart + dmcFrameHeaderBytes + storedBytes
            try {
                RandomAccessFile(dmcFile, "rw").use { output ->
                    Os.posix_fallocate(output.fd, 0L, dmcReservedBytes)
                }
                Npv10DirectMappedCommitEngine.open(
                    file = dmcFile,
                    dataStartOffset = dmcDataStart,
                    frameHeaderBytes = dmcFrameHeaderBytes,
                    payloadBytes = storedBytes,
                    slotCount = 1,
                    reservedBytes = dmcReservedBytes
                ).use { engine ->
                    engine.beginFrame(frameIndex = 0L, reservedBytes = dmcReservedBytes)
                    val dmcStartedNs = SystemClock.elapsedRealtimeNanos()
                    val directMappedResult = requireNotNull(
                        Npv10FramePacker.packP010ImageToDirectMappedTenBitWithCrc(
                            image = image,
                            directMappedCommit = engine,
                            frameIndex = 0L
                        )
                    )
                    directMappedPackNs = SystemClock.elapsedRealtimeNanos() - dmcStartedNs
                    assertEquals(
                        "Production direct-mapped Camera2 packet CRC differs from canonical packet CRC",
                        referenceCrc.toLong() and 0xffff_ffffL,
                        directMappedResult.crc32.toLong() and 0xffff_ffffL
                    )
                    assertEquals(storedBytes, directMappedResult.encodedBytes)
                    engine.commitFrame(0L, directMappedResult.crc32)
                    val telemetry = engine.snapshot()
                    assertEquals(1L, telemetry.framesMapped)
                    assertEquals(1L, telemetry.framesCommitted)
                    assertEquals(storedBytes.toLong(), telemetry.directMappedPayloadBytes)
                }
                val directMappedPacket = ByteArray(storedBytes)
                RandomAccessFile(dmcFile, "r").use { input ->
                    input.seek(dmcDataStart + dmcFrameHeaderBytes)
                    input.readFully(directMappedPacket)
                }
                val expectedPacket = ByteArray(storedBytes).also { bytes ->
                    canonicalPacket.duplicate().apply {
                        position(0)
                        limit(storedBytes)
                    }.get(bytes)
                }
                assertArrayEquals(
                    "Production direct-mapped Camera2 packet differs from canonical packed P010",
                    expectedPacket,
                    directMappedPacket
                )
            } finally {
                assertTrue(!dmcFile.exists() || dmcFile.delete())
            }

            val restoredBytes = Npv10PacketCodecEngine.decodeInto(
                encoded = fusedPacket,
                encodedBytes = storedBytes,
                decodedBytes = decodedBytes,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                target = restoredP010
            )
            assertEquals(decodedBytes, restoredBytes)
            assertBuffersEqual("Fused packet does not restore the original P010 words", canonicalP010, restoredP010)

            val encoding = Npv10Niv99ColorEncoding(
                matrix = Npv10Niv99ColorMatrix.BT709,
                transfer = Npv10Niv99Transfer.BT709
            )
            val identityCrc = Npv10FramePacker.processNiv99P010ImageToPackedTenBitBufferWithCrc(
                image = image,
                target = niv99IdentityPacket,
                encoding = encoding,
                redCorrection = 1f,
                greenCorrection = 1f,
                blueCorrection = 1f,
                spatialDenoise = 0f,
                chromaDenoise = 0f,
                sharpeningCeiling = 0f,
                confidence = 1f
            )
            assertEquals(
                "Zero-effect NIV99 must be bit-exact with the canonical packed-10 input",
                referenceCrc.toLong() and 0xffff_ffffL,
                identityCrc
            )
            assertBuffersEqual(
                "Zero-effect NIV99 changed P010 pixels",
                canonicalPacket,
                niv99IdentityPacket
            )

            val sourcePlaneEvidence = image.planes.take(2).joinToString(separator = ";") { plane ->
                "row=${plane.rowStride},pixel=${plane.pixelStride},bytes=${plane.buffer.remaining()}"
            }
            image.close()
            val copiedIdentityCrc =
                Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                    canonicalP010 = canonicalP010,
                    width = size.width,
                    height = size.height,
                    target = niv99EffectPacket,
                    encoding = encoding,
                    redCorrection = 1f,
                    greenCorrection = 1f,
                    blueCorrection = 1f,
                    spatialDenoise = 0f,
                    chromaDenoise = 0f,
                    sharpeningCeiling = 0f,
                    confidence = 1f
                )
            assertEquals(
                "Image-independent canonical NIV99 CRC differs from direct Camera2 processing",
                identityCrc,
                copiedIdentityCrc
            )
            assertBuffersEqual(
                "Image-independent canonical NIV99 changed the zero-effect output",
                niv99IdentityPacket,
                niv99EffectPacket
            )

            fun runNiv99Effect(): Long =
                Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                    canonicalP010 = canonicalP010,
                    width = size.width,
                    height = size.height,
                    target = niv99EffectPacket,
                    encoding = encoding,
                    redCorrection = 1.06f,
                    greenCorrection = 1f,
                    blueCorrection = 0.94f,
                    spatialDenoise = 0.42f,
                    chromaDenoise = 0.64f,
                    sharpeningCeiling = 0.38f,
                    confidence = 1f
                )
            repeat(NIV99_WARMUP_RUNS) {
                assertTrue("NIV99 fused warmup processing failed", runNiv99Effect() >= 0L)
            }
            val niv99SamplesNs = LongArray(NIV99_MEASURED_RUNS)
            var effectCrc = -1L
            repeat(NIV99_MEASURED_RUNS) { run ->
                val niv99StartedNs = SystemClock.elapsedRealtimeNanos()
                effectCrc = runNiv99Effect()
                niv99SamplesNs[run] = SystemClock.elapsedRealtimeNanos() - niv99StartedNs
                assertTrue("NIV99 fused measured processing failed at run=$run", effectCrc >= 0L)
            }
            val niv99Stats = timingStats(niv99SamplesNs)
            assertTrue("NIV99 fused processing failed", effectCrc >= 0L)
            assertEquals(
                "NIV99 returned a CRC that does not describe its packed output",
                CRC32().apply { update(niv99EffectPacket.duplicate()) }.value,
                effectCrc
            )
            val changedBytes = countDifferingBytes(canonicalPacket, niv99EffectPacket)
            assertTrue(
                "Non-identity NIV99 settings produced no pixel-domain change",
                changedBytes > 0
            )
            val niv99RestoredBytes = Npv10PacketCodecEngine.decodeInto(
                encoded = niv99EffectPacket,
                encodedBytes = storedBytes,
                decodedBytes = decodedBytes,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                target = restoredP010
            )
            assertEquals(decodedBytes, niv99RestoredBytes)
            assertP010PaddingIsZero(restoredP010)
            val parityEvidence =
                "Camera2 P010 fused parity PASS camera=$cameraId size=${size.width}x${size.height} " +
                    "planes=$sourcePlaneEvidence canonicalAfterImageClose=true " +
                    "decodedBytes=$decodedBytes storedBytes=$storedBytes " +
                    "copyMs=${copyNs / 1_000_000.0} " +
                    "encodeCrcRuns=$ENCODE_MEASURED_RUNS " +
                    "encodeCrcMedianMs=${encodeMedianNs / 1_000_000.0} " +
                    "encodeCrcP95Ms=${encodeP95Ns / 1_000_000.0} " +
                    "encodeCrcAverageMs=${encodeAverageNs / 1_000_000.0} " +
                    "encodeCrcMaxMs=${encodeMaxNs / 1_000_000.0} " +
                    "fusedPackCrcMs=${fusedNs / 1_000_000.0} " +
                    "directMappedPackCrcMs=${directMappedPackNs / 1_000_000.0} " +
                    "niv99ChangedBytes=$changedBytes niv99Runs=$NIV99_MEASURED_RUNS " +
                    "niv99=${niv99Stats.logValue()} " +
                    "niv99SamplesMs=${formatSamplesMs(niv99SamplesNs)}"
            Log.i(TAG, parityEvidence)
            publishInstrumentationEvidence("niv99ParityEvidence", parityEvidence)
            } finally {
                parityBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)
            }
        }

        val freshTelemetry = measureFreshFusedFrames(
            session = session,
            request = request,
            reader = reader,
            storedBytes = Npv10PacketCodecEngine.storedBytes(
                Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                    size.width,
                    size.height,
                    Npv10PayloadFormat.P010_LE
                ),
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            ).toInt()
        )
        val serviceStats = timingStats(freshTelemetry.serviceNs)
        val callbackStats = timingStats(freshTelemetry.callbackNs)
        val queueStats = timingStats(freshTelemetry.queueDelayNs)
        val endToEndStats = timingStats(freshTelemetry.endToEndNs)
        val arrivalFps = observedFps(freshTelemetry.callbackArrivalNs)
        val sensorFps = observedFps(freshTelemetry.sensorTimestampNs)
        val capacityFps = P010_PACKING_DISPATCH_WORKERS * NANOS_PER_SECOND.toDouble() /
            serviceStats.p95Ns.toDouble()
        val capacityHeadroom = capacityFps / TARGET_CAPTURE_FPS.toDouble()
        val preAssertEvidence =
            "Camera2 P010 prestarted-dispatch/native-row-parallel pre-assert telemetry " +
                "readerMaxImages=$readerMaxImages pendingCapacity=$pendingFrameCapacity " +
                "capacityFps=${format(capacityFps)} capacityHeadroom=${format(capacityHeadroom)}x " +
                "service=${serviceStats.logValue()} serviceSamplesMs=${formatSamplesMs(freshTelemetry.serviceNs)} " +
                "queue=${queueStats.logValue()} queueSamplesMs=${formatSamplesMs(freshTelemetry.queueDelayNs)} " +
                "callback=${callbackStats.logValue()} callbackSamplesMs=${formatSamplesMs(freshTelemetry.callbackNs)} " +
                "endToEnd=${endToEndStats.logValue()} endToEndSamplesMs=${formatSamplesMs(freshTelemetry.endToEndNs)} " +
                "maxQueueDepth=${freshTelemetry.maxQueueDepth} maxInFlight=${freshTelemetry.maxInFlight} " +
                "workerFrames=${freshTelemetry.workerFrameCounts.toSortedMap()} " +
                "workerPriorities=${freshTelemetry.workerPriorities.toSortedMap()}"
        Log.i(TAG, preAssertEvidence)
        publishInstrumentationEvidence("npv10PreAssertEvidence", preAssertEvidence)

        assertEquals(FRESH_MEASURED_RUNS, freshTelemetry.serviceNs.size)
        assertEquals(FRESH_TOTAL_RUNS, freshTelemetry.targetImagesAcquired)
        assertEquals(FRESH_TOTAL_RUNS, freshTelemetry.tasksSubmitted)
        assertEquals(FRESH_TOTAL_RUNS, freshTelemetry.tasksCompleted)
        assertEquals(FRESH_TOTAL_RUNS, freshTelemetry.targetImagesClosed)
        assertTrue(
            "P010 harness retained ${freshTelemetry.maxInFlight} Images; production pending-frame " +
                "capacity is $pendingFrameCapacity",
            freshTelemetry.maxInFlight <= pendingFrameCapacity
        )
        assertEquals(P010_PACKING_DISPATCH_WORKERS, freshTelemetry.workerFrameCounts.size)
        assertTrue(
            "Every production-equivalent P010 worker must pack at least one Camera2 Image",
            freshTelemetry.workerFrameCounts.values.all { it > 0 }
        )
        assertEquals(
            "Each packing worker must retain its own direct packet buffer",
            P010_PACKING_DISPATCH_WORKERS,
            freshTelemetry.workerBufferIdentities.values.toSet().size
        )
        assertStrictlyIncreasing("Camera2 callback arrival", freshTelemetry.callbackArrivalNs)
        assertStrictlyIncreasing("Camera2 sensor timestamp", freshTelemetry.sensorTimestampNs)
        assertTrue(
            "Native-row-parallel P010 dispatch capacity ${format(capacityFps)} fps provides only " +
                "${format(capacityHeadroom)}x headroom over $TARGET_CAPTURE_FPS fps; " +
                "required headroom is ${format(MIN_CAPACITY_HEADROOM)}x",
            capacityHeadroom >= MIN_CAPACITY_HEADROOM
        )
        assertTrue(
            "Camera2 callback P95 ${formatMs(callbackStats.p95Ns)} ms exceeds the " +
                "${formatMs(CALLBACK_P95_BUDGET_NS)} ms ownership-transfer budget",
            callbackStats.p95Ns <= CALLBACK_P95_BUDGET_NS
        )
        assertTrue(
            "P010 worker queue-delay P95 ${formatMs(queueStats.p95Ns)} ms exceeds one " +
                "$TARGET_CAPTURE_FPS fps frame period (${formatMs(FRAME_PERIOD_NS)} ms)",
            queueStats.p95Ns <= FRAME_PERIOD_NS
        )
        assertTrue(
            "P010 callback-to-pack P95 ${formatMs(endToEndStats.p95Ns)} ms exceeds the " +
                "fixed end-to-end budget (${formatMs(END_TO_END_P95_BUDGET_NS)} ms)",
            endToEndStats.p95Ns <= END_TO_END_P95_BUDGET_NS
        )
        assertTrue(
            "P010 packing queue reached ${freshTelemetry.maxQueueDepth} waiting frames; " +
                "bounded limit is $MAX_MEASURED_QUEUE_DEPTH",
            freshTelemetry.maxQueueDepth <= MAX_MEASURED_QUEUE_DEPTH
        )
        assertTrue(
            "Camera2 callback cadence ${format(arrivalFps)} fps is below the " +
                "${format(MIN_OBSERVED_CAPTURE_FPS)} fps validation floor",
            arrivalFps >= MIN_OBSERVED_CAPTURE_FPS
        )
        assertTrue(
            "Camera2 sensor cadence ${format(sensorFps)} fps is below the " +
                "${format(MIN_OBSERVED_CAPTURE_FPS)} fps validation floor",
            sensorFps >= MIN_OBSERVED_CAPTURE_FPS
        )
        val performanceEvidence =
            "Camera2 P010 prestarted-dispatch/native-row-parallel performance PASS " +
                "targetFps=$TARGET_CAPTURE_FPS measuredRuns=$FRESH_MEASURED_RUNS " +
                "dispatchWorkers=$P010_PACKING_DISPATCH_WORKERS " +
                "capacityFps=${format(capacityFps)} capacityHeadroom=${format(capacityHeadroom)}x " +
                "arrivalFps=${format(arrivalFps)} sensorFps=${format(sensorFps)} " +
                "callback=${callbackStats.logValue()} queue=${queueStats.logValue()} " +
                "service=${serviceStats.logValue()} endToEnd=${endToEndStats.logValue()} " +
                "maxQueueDepth=${freshTelemetry.maxQueueDepth} " +
                "maxInFlight=${freshTelemetry.maxInFlight} " +
                "completionInversions=${freshTelemetry.completionInversions} " +
                "ignoredAfterTarget=${freshTelemetry.imagesIgnoredAfterTarget} " +
                "workerFrames=${freshTelemetry.workerFrameCounts.toSortedMap()} " +
                "workerPriorities=${freshTelemetry.workerPriorities.toSortedMap()} " +
                "workerBuffers=${freshTelemetry.workerBufferIdentities.toSortedMap()}"
        Log.i(TAG, performanceEvidence)
        publishInstrumentationEvidence("npv10PerformanceEvidence", performanceEvidence)
    }

    private fun measureFreshFusedFrames(
        session: CameraCaptureSession,
        request: CaptureRequest,
        reader: ImageReader,
        storedBytes: Int
    ): FreshPipelineTelemetry {
        val taskFinished = CountDownLatch(FRESH_TOTAL_RUNS)
        val callbackReturned = CountDownLatch(FRESH_TOTAL_RUNS)
        val sequence = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val serviceNs = LongArray(FRESH_TOTAL_RUNS)
        val callbackNs = LongArray(FRESH_TOTAL_RUNS)
        val queueDelayNs = LongArray(FRESH_TOTAL_RUNS)
        val endToEndNs = LongArray(FRESH_TOTAL_RUNS)
        val callbackArrivalNs = LongArray(FRESH_TOTAL_RUNS)
        val sensorTimestampNs = LongArray(FRESH_TOTAL_RUNS)
        val targetImagesAcquired = AtomicInteger(0)
        val tasksSubmitted = AtomicInteger(0)
        val tasksCompleted = AtomicInteger(0)
        val targetImagesClosed = AtomicInteger(0)
        val imagesIgnoredAfterTarget = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val maxQueueDepth = AtomicInteger(0)
        val workerThreadIndex = AtomicInteger(0)
        val workerFrameCounts = ConcurrentHashMap<String, AtomicInteger>()
        val workerPriorities = ConcurrentHashMap<String, Int>()
        val workerBufferIdentities = ConcurrentHashMap<String, Int>()
        val workerPacketLeases = ConcurrentHashMap<String, Npv10NativeFrameBuffer>()
        val completionOrder = ConcurrentLinkedQueue<Int>()
        val outstandingImages = ConcurrentHashMap<Int, TrackedImageOwnership>()
        val pendingFrameCapacity = Npv10InFlightBufferPolicy.pendingFrames(reader.width, reader.height)
        val pendingFrameSlots = Semaphore(pendingFrameCapacity, true)
        val acceptingImages = AtomicBoolean(true)
        val workerPacketLease = ThreadLocal.withInitial {
            val workerName = Thread.currentThread().name
            val allocated = allocatePrefaultedNativeBuffer(storedBytes)
            val existing = workerPacketLeases.putIfAbsent(workerName, allocated)
            if (existing == null) {
                allocated
            } else {
                allocated.close()
                existing
            }
        }
        val packingExecutor = Executors.newFixedThreadPool(P010_PACKING_DISPATCH_WORKERS) { runnable ->
            object : Thread(
                runnable,
                "NPV10-Test-Pack-${workerThreadIndex.incrementAndGet()}"
            ) {
                init {
                    priority = Thread.NORM_PRIORITY + 2
                    isDaemon = true
                }

                override fun run() {
                    runCatching {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    }
                    super.run()
                }
            }
        } as ThreadPoolExecutor

        lateinit var drainPendingImage: Runnable
        fun releasePendingFrameSlot() {
            pendingFrameSlots.release()
            if (acceptingImages.get()) cameraHandler.post(drainPendingImage)
        }
        drainPendingImage = Runnable {
            if (!acceptingImages.get() || !pendingFrameSlots.tryAcquire()) return@Runnable
            val callbackStartedNs = SystemClock.elapsedRealtimeNanos()
            val image = runCatching { reader.acquireNextImage() }
                .onFailure { failure.compareAndSet(null, it) }
                .getOrNull()
            if (image == null) {
                pendingFrameSlots.release()
                return@Runnable
            }
            val run = sequence.getAndIncrement()
            if (run >= FRESH_TOTAL_RUNS) {
                try {
                    image.close()
                } finally {
                    releasePendingFrameSlot()
                }
                imagesIgnoredAfterTarget.incrementAndGet()
                return@Runnable
            }
            targetImagesAcquired.incrementAndGet()
            callbackArrivalNs[run] = callbackStartedNs
            sensorTimestampNs[run] = runCatching { image.timestamp }.getOrDefault(0L)
            val ownership = TrackedImageOwnership(image) {
                targetImagesClosed.incrementAndGet()
                inFlight.decrementAndGet()
                releasePendingFrameSlot()
            }
            outstandingImages[run] = ownership
            val currentInFlight = inFlight.incrementAndGet()
            maxInFlight.updateMax(currentInFlight)
            val enqueuedNs = SystemClock.elapsedRealtimeNanos()
            try {
                packingExecutor.execute {
                    val workerStartedNs = SystemClock.elapsedRealtimeNanos()
                    queueDelayNs[run] = (workerStartedNs - enqueuedNs).coerceAtLeast(0L)
                    val workerName = Thread.currentThread().name
                    workerFrameCounts.computeIfAbsent(workerName) { AtomicInteger(0) }
                        .incrementAndGet()
                    workerPriorities.putIfAbsent(
                        workerName,
                        runCatching {
                            Process.getThreadPriority(Process.myTid())
                        }.getOrDefault(Int.MAX_VALUE)
                    )
                    val targetLease = requireNotNull(workerPacketLease.get()) {
                        "Packing worker $workerName did not retain its native packet lease"
                    }
                    val target = targetLease.buffer
                    val targetIdentity = System.identityHashCode(target)
                    val previousIdentity = workerBufferIdentities.putIfAbsent(
                        workerName,
                        targetIdentity
                    )
                    try {
                        check(previousIdentity == null || previousIdentity == targetIdentity) {
                            "Packing worker $workerName changed packet buffer identity"
                        }
                        target.clear()
                        val result = Npv10FramePacker.packP010ImageToPackedTenBitBufferWithCrcResult(
                            image,
                            target
                        )
                        check(result.path == Npv10FramePacker.P010CopyPath.NATIVE_DIRECT)
                        check(result.bytesCopied == storedBytes)
                        check(result.crc32 != null)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        val finishedNs = SystemClock.elapsedRealtimeNanos()
                        serviceNs[run] = (finishedNs - workerStartedNs).coerceAtLeast(0L)
                        endToEndNs[run] = (finishedNs - callbackStartedNs).coerceAtLeast(0L)
                        completionOrder.add(run)
                        tasksCompleted.incrementAndGet()
                        outstandingImages.remove(run, ownership)
                        ownership.closeExactlyOnce()
                        taskFinished.countDown()
                    }
                }
                tasksSubmitted.incrementAndGet()
                maxQueueDepth.updateMax(packingExecutor.queue.size)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                outstandingImages.remove(run, ownership)
                ownership.closeExactlyOnce()
                taskFinished.countDown()
            } finally {
                callbackNs[run] = (
                    SystemClock.elapsedRealtimeNanos() - callbackStartedNs
                ).coerceAtLeast(0L)
                callbackReturned.countDown()
            }
        }
        reader.setOnImageAvailableListener({ drainPendingImage.run() }, cameraHandler)

        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {}, cameraHandler)
        val allTasksFinished = taskFinished.await(FRESH_CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val allCallbacksReturned = callbackReturned.await(
            FRESH_CALLBACK_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
        acceptingImages.set(false)
        runCatching { session.stopRepeating() }
        runCatching { session.abortCaptures() }
        reader.setOnImageAvailableListener(null, null)
        val drainCallbacksQuiesced = CountDownLatch(1)
        cameraHandler.post { drainCallbacksQuiesced.countDown() }
        val allDrainCallbacksQuiesced = drainCallbacksQuiesced.await(
            FRESH_CALLBACK_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
        packingExecutor.shutdown()
        var workersTerminated = packingExecutor.awaitTermination(
            FRESH_WORKER_DRAIN_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
        if (!workersTerminated) {
            packingExecutor.shutdownNow()
            workersTerminated = packingExecutor.awaitTermination(
                FRESH_WORKER_FORCE_STOP_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        }
        if (workersTerminated) {
            workerPacketLeases.values.forEach(Npv10NativeFrameBuffer::close)
            workerPacketLeases.clear()
        }
        outstandingImages.values.forEach(TrackedImageOwnership::closeExactlyOnce)
        outstandingImages.clear()

        assertTrue(
            "Camera2 did not complete $FRESH_TOTAL_RUNS fresh P010 packing tasks",
            allTasksFinished
        )
        assertTrue(
            "Camera2 did not return all $FRESH_TOTAL_RUNS ownership-transfer callbacks",
            allCallbacksReturned
        )
        assertTrue("Camera2 acquisition drains did not quiesce after capture", allDrainCallbacksQuiesced)
        assertTrue("P010 packing workers did not terminate after capture", workersTerminated)
        failure.get()?.let { throw AssertionError("Fresh P010 fused packing failed", it) }
        assertEquals("P010 worker in-flight accounting did not drain", 0, inFlight.get())
        assertEquals(
            "P010 pending-frame permits did not drain",
            pendingFrameCapacity,
            pendingFrameSlots.availablePermits()
        )
        assertTrue("Fresh P010 service timing sample was not populated", serviceNs.all { it > 0L })
        assertTrue("Fresh P010 callback timing sample was not populated", callbackNs.all { it > 0L })
        assertTrue("Fresh P010 end-to-end timing sample was not populated", endToEndNs.all { it > 0L })
        assertTrue("Fresh P010 sensor timestamp was not populated", sensorTimestampNs.all { it > 0L })

        val measuredRange = FRESH_WARMUP_RUNS until FRESH_TOTAL_RUNS
        val measuredCompletionOrder = completionOrder.filter { it in measuredRange }
        val completionInversions = measuredCompletionOrder.zipWithNext().count { (left, right) ->
            right < left
        }
        return FreshPipelineTelemetry(
            serviceNs = serviceNs.copyOfRange(FRESH_WARMUP_RUNS, FRESH_TOTAL_RUNS),
            callbackNs = callbackNs.copyOfRange(FRESH_WARMUP_RUNS, FRESH_TOTAL_RUNS),
            queueDelayNs = queueDelayNs.copyOfRange(FRESH_WARMUP_RUNS, FRESH_TOTAL_RUNS),
            endToEndNs = endToEndNs.copyOfRange(FRESH_WARMUP_RUNS, FRESH_TOTAL_RUNS),
            callbackArrivalNs = callbackArrivalNs.copyOfRange(
                FRESH_WARMUP_RUNS,
                FRESH_TOTAL_RUNS
            ),
            sensorTimestampNs = sensorTimestampNs.copyOfRange(
                FRESH_WARMUP_RUNS,
                FRESH_TOTAL_RUNS
            ),
            targetImagesAcquired = targetImagesAcquired.get(),
            tasksSubmitted = tasksSubmitted.get(),
            tasksCompleted = tasksCompleted.get(),
            targetImagesClosed = targetImagesClosed.get(),
            imagesIgnoredAfterTarget = imagesIgnoredAfterTarget.get(),
            maxQueueDepth = maxQueueDepth.get(),
            maxInFlight = maxInFlight.get(),
            completionInversions = completionInversions,
            workerFrameCounts = workerFrameCounts.mapValues { it.value.get() },
            workerPriorities = workerPriorities.toMap(),
            workerBufferIdentities = workerBufferIdentities.toMap()
        )
    }

    private data class FreshPipelineTelemetry(
        val serviceNs: LongArray,
        val callbackNs: LongArray,
        val queueDelayNs: LongArray,
        val endToEndNs: LongArray,
        val callbackArrivalNs: LongArray,
        val sensorTimestampNs: LongArray,
        val targetImagesAcquired: Int,
        val tasksSubmitted: Int,
        val tasksCompleted: Int,
        val targetImagesClosed: Int,
        val imagesIgnoredAfterTarget: Int,
        val maxQueueDepth: Int,
        val maxInFlight: Int,
        val completionInversions: Int,
        val workerFrameCounts: Map<String, Int>,
        val workerPriorities: Map<String, Int>,
        val workerBufferIdentities: Map<String, Int>
    )

    private class TrackedImageOwnership(
        private val image: Image,
        private val onClosed: () -> Unit
    ) {
        private val closed = AtomicBoolean(false)

        fun closeExactlyOnce() {
            if (!closed.compareAndSet(false, true)) return
            try {
                image.close()
            } finally {
                onClosed()
            }
        }
    }

    private inner class TimingStats(
        val minNs: Long,
        val medianNs: Long,
        val p95Ns: Long,
        val averageNs: Double,
        val maxNs: Long
    ) {
        fun logValue(): String =
            "minMs=${formatMs(minNs)} medianMs=${formatMs(medianNs)} " +
                "p95Ms=${formatMs(p95Ns)} averageMs=${format(averageNs / NANOS_PER_MILLISECOND)} " +
                "maxMs=${formatMs(maxNs)}"
    }

    private fun timingStats(valuesNs: LongArray): TimingStats {
        require(valuesNs.isNotEmpty()) { "At least one timing sample is required" }
        require(valuesNs.all { it >= 0L }) { "Timing samples must be non-negative" }
        val sorted = valuesNs.sortedArray()
        val p95Index = (
            kotlin.math.ceil(sorted.size * 0.95).toInt() - 1
        ).coerceIn(0, sorted.lastIndex)
        return TimingStats(
            minNs = sorted.first(),
            medianNs = sorted[sorted.size / 2],
            p95Ns = sorted[p95Index],
            averageNs = valuesNs.average(),
            maxNs = sorted.last()
        )
    }

    private fun observedFps(timestampsNs: LongArray): Double {
        require(timestampsNs.size >= 2) { "At least two timestamps are required" }
        val elapsedNs = timestampsNs.last() - timestampsNs.first()
        if (elapsedNs <= 0L) return 0.0
        return (timestampsNs.size - 1).toDouble() * NANOS_PER_SECOND.toDouble() /
            elapsedNs.toDouble()
    }

    private fun assertStrictlyIncreasing(label: String, values: LongArray) {
        val violationIndex = (1 until values.size).firstOrNull { index ->
            values[index] <= values[index - 1]
        }
        assertTrue(
            if (violationIndex == null) {
                "$label values must be strictly increasing"
            } else {
                "$label values are not strictly increasing at index $violationIndex: " +
                    "${values[violationIndex - 1]} then ${values[violationIndex]}"
            },
            violationIndex == null
        )
    }

    private fun AtomicInteger.updateMax(candidate: Int) {
        var observed = get()
        while (candidate > observed && !compareAndSet(observed, candidate)) {
            observed = get()
        }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun formatMs(nanos: Long): String =
        format(nanos.toDouble() / NANOS_PER_MILLISECOND)

    private fun formatSamplesMs(samplesNs: LongArray): String =
        samplesNs.joinToString(prefix = "[", postfix = "]") { sample -> formatMs(sample) }

    private fun publishInstrumentationEvidence(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            INSTRUMENTATION_EVIDENCE_STATUS_CODE,
            Bundle(1).apply { putString(key, value) }
        )
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

    private fun findP010Camera(manager: CameraManager): Pair<String, Size>? =
        manager.cameraIdList.asSequence().mapNotNull { cameraId ->
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val size = map.getOutputSizes(ImageFormat.YCBCR_P010)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return@mapNotNull null
            cameraId to size
        }.firstOrNull()

    private fun openCamera(manager: CameraManager, cameraId: String): CameraDevice {
        val latch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        cameraClosedLatch = closedLatch
        var opened: CameraDevice? = null
        var error: String? = null
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                error = "Camera disconnected while opening"
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                error = "Camera open failed with error=$code"
                camera.close()
                latch.countDown()
            }

            override fun onClosed(camera: CameraDevice) {
                closedLatch.countDown()
            }
        }, cameraHandler)
        assertTrue("Timed out opening Camera2 P010 source", latch.await(15L, TimeUnit.SECONDS))
        check(error == null) { requireNotNull(error) }
        return requireNotNull(opened)
    }

    private fun createSession(device: CameraDevice, reader: ImageReader): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null
        var failed = false
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                configured = session
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                failed = true
                latch.countDown()
            }
        }
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(reader.surface)),
            { command -> cameraHandler.post(command) },
            callback
        )
        device.createCaptureSession(sessionConfiguration)
        assertTrue("Timed out configuring Camera2 P010 session", latch.await(15L, TimeUnit.SECONDS))
        assertTrue("Camera2 rejected the advertised P010 output", !failed)
        return requireNotNull(configured)
    }

    private fun assertBuffersEqual(message: String, expected: ByteBuffer, actual: ByteBuffer) {
        val left = expected.duplicate().apply { position(0) }
        val right = actual.duplicate().apply { position(0) }
        assertEquals("$message (length)", left.remaining(), right.remaining())
        var index = 0
        while (left.remaining() >= Long.SIZE_BYTES) {
            val expectedWord = left.long
            val actualWord = right.long
            if (expectedWord != actualWord) {
                for (offset in 0 until Long.SIZE_BYTES) {
                    val expectedByte = left.get(index + offset)
                    val actualByte = right.get(index + offset)
                    if (expectedByte != actualByte) {
                        fail(
                            "$message at byte ${index + offset}: " +
                                "expected=$expectedByte actual=$actualByte"
                        )
                    }
                }
            }
            index += Long.SIZE_BYTES
        }
        while (left.hasRemaining()) {
            val expectedByte = left.get()
            val actualByte = right.get()
            if (expectedByte != actualByte) {
                fail("$message at byte $index: expected=$expectedByte actual=$actualByte")
            }
            index += 1
        }
    }

    private fun countDifferingBytes(expected: ByteBuffer, actual: ByteBuffer): Int {
        val left = expected.duplicate().apply { position(0) }
        val right = actual.duplicate().apply { position(0) }
        assertEquals("Compared packet lengths differ", left.remaining(), right.remaining())
        var changed = 0
        while (left.remaining() >= Long.SIZE_BYTES) {
            var difference = left.long xor right.long
            repeat(Long.SIZE_BYTES) {
                if (difference and 0xffL != 0L) changed += 1
                difference = difference ushr Byte.SIZE_BITS
            }
        }
        while (left.hasRemaining()) {
            if (left.get() != right.get()) changed += 1
        }
        return changed
    }

    private fun assertP010PaddingIsZero(decoded: ByteBuffer) {
        // ByteBuffer views default to big-endian even when the originating native lease was set to
        // little-endian. P010 is explicitly little-endian, so make the transport contract explicit
        // before interpreting its two-byte words.
        val words = decoded.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(0) }
        var sample = 0
        while (words.remaining() >= Short.SIZE_BYTES) {
            val word = words.short.toInt() and 0xffff
            assertEquals("NIV99 emitted non-zero P010 padding at sample $sample", 0, word and 0x3f)
            sample += 1
        }
        assertTrue("NIV99 decoded output contained no P010 samples", sample > 0)
    }

    private fun percentile(sortedValues: List<Long>, percentile: Double): Long {
        require(sortedValues.isNotEmpty())
        val rank = kotlin.math.ceil(sortedValues.size * percentile).toInt().coerceAtLeast(1)
        return sortedValues[(rank - 1).coerceAtMost(sortedValues.lastIndex)]
    }

    private companion object {
        const val TAG = "Npv10FusedP010Test"
        const val P010_PACKING_DISPATCH_WORKERS = 1
        const val TARGET_CAPTURE_FPS = 25
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val FRAME_PERIOD_NS = NANOS_PER_SECOND / TARGET_CAPTURE_FPS
        const val END_TO_END_P95_BUDGET_NS = 80_000_000L
        const val CALLBACK_P95_BUDGET_NS = FRAME_PERIOD_NS / 4L
        const val MIN_CAPACITY_HEADROOM = 1.5
        const val MIN_OBSERVED_CAPTURE_FPS = 23.75
        const val MAX_MEASURED_QUEUE_DEPTH = 2
        const val ENCODE_WARMUP_RUNS = 2
        const val ENCODE_MEASURED_RUNS = 9
        const val NIV99_WARMUP_RUNS = 2
        const val NIV99_MEASURED_RUNS = 9
        const val INSTRUMENTATION_EVIDENCE_STATUS_CODE = 2
        const val FRESH_WARMUP_RUNS = 4
        const val FRESH_MEASURED_RUNS = 25
        const val FRESH_TOTAL_RUNS = FRESH_WARMUP_RUNS + FRESH_MEASURED_RUNS
        const val FRESH_CAPTURE_TIMEOUT_SECONDS = 30L
        const val FRESH_CALLBACK_TIMEOUT_SECONDS = 5L
        const val FRESH_WORKER_DRAIN_TIMEOUT_SECONDS = 5L
        const val FRESH_WORKER_FORCE_STOP_TIMEOUT_SECONDS = 2L
        const val CAMERA_CLOSE_TIMEOUT_SECONDS = 5L
        const val MEMORY_PAGE_BYTES = 4 * 1024
    }
}
