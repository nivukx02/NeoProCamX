package com.neoproc.neoproccamx.ui

import android.media.Image
import android.util.Log
import com.neoproc.neoproccamx.camera.Npv10ProducerStartupLeaseHandle
import com.neoproc.neoproccamx.recording.Npv10P010ImageAdmission
import com.neoproc.neoproccamx.recording.Npv10RecordingManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Records sequence zero only after the recorder has accepted ownership into its writer pipeline. */
internal class Npv10FirstSinkAdmissionTracker {
    private val timestampNs = AtomicLong(0L)

    fun observe(admission: Npv10P010ImageAdmission) {
        if (admission.writerAdmissionAccepted && admission.ingressSequence == 0L) {
            timestampNs.compareAndSet(0L, admission.sensorTimestampNs)
        }
    }

    fun timestampNs(): Long = timestampNs.get()
}

/** Exact sink identity spanning Camera2 producer proof, Image ownership, and writer frame 1. */
internal class Npv10RecordingStartupIngress(
    private val recorder: Npv10RecordingManager,
    private val startupLease: Npv10ProducerStartupLeaseHandle,
    private val expectedWidth: Int,
    private val expectedHeight: Int,
    private val expectedImageFormat: Int,
    private val minimumProducerTimestampNs: Long
) {
    private val firstAdmission = Npv10FirstSinkAdmissionTracker()
    private val violationLogCount = AtomicInteger(0)

    fun firstSinkTimestampNs(): Long = firstAdmission.timestampNs()

    fun onImage(image: Image, timestampNs: Long): Boolean {
        if (startupLease.isRevoked) {
            return reject(image, "NPV10 Camera2 startup lease was revoked before durable frame proof")
        }
        if (image.width != expectedWidth || image.height != expectedHeight ||
            image.format != expectedImageFormat
        ) {
            return reject(
                image,
                "NPV10 input contract violation: received ${image.width}x${image.height} " +
                    "format=${image.format}; expected=${expectedWidth}x$expectedHeight " +
                    "format=$expectedImageFormat. Strict master stopped; no frame was substituted."
            )
        }
        if (timestampNs < minimumProducerTimestampNs) {
            return reject(
                image,
                "NPV10 sink frame predates producer proof: frame=$timestampNs " +
                    "producer=$minimumProducerTimestampNs"
            )
        }
        val admission = recorder.enqueueP010ImageWithAdmission(image)
        if (admission.writerAdmissionAccepted && admission.sensorTimestampNs != timestampNs) {
            recorder.failInputContractViolation(
                "NPV10 writer admission timestamp changed: image=$timestampNs " +
                    "admission=${admission.sensorTimestampNs}"
            )
        } else {
            firstAdmission.observe(admission)
        }
        return admission.imageOwnershipTransferred
    }

    private fun reject(image: Image, message: String): Boolean {
        if (violationLogCount.getAndIncrement() < MAX_VIOLATION_LOGS) {
            Log.e("Npv10StartupIngress", message)
        }
        runCatching { image.close() }
        recorder.failInputContractViolation(message)
        return true
    }

    private companion object {
        const val MAX_VIOLATION_LOGS = 3
    }
}
