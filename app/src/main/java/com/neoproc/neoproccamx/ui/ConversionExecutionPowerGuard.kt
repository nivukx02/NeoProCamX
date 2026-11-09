package com.neoproc.neoproccamx.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.neoproc.neoproccamx.recording.MediaConversionForegroundService

/** Acquires one execution lease for the complete, bounded offline-conversion work window. */
internal interface ConversionExecutionLease : AutoCloseable {
    val isHeld: Boolean
}

internal fun interface ConversionExecutionLeaseFactory {
    fun acquire(): ConversionExecutionLease
}

/**
 * Makes power ownership independently testable and guarantees release on success, cancellation,
 * or failure. The lease is acquired after Camera2 has released its resources and is released
 * before Camera2 is reopened.
 */
internal class ConversionExecutionPowerGuard private constructor(
    private val leaseFactory: ConversionExecutionLeaseFactory?,
    context: Context?
) {
    private val applicationContext = context?.applicationContext

    constructor(leaseFactory: ConversionExecutionLeaseFactory) : this(leaseFactory, null)

    constructor(context: Context) : this(null, context)

    fun <T> run(work: () -> T): T {
        val context = applicationContext
        return if (context != null) {
            runWithAndroidProtection(context, work)
        } else {
            runWithTestLease(requireNotNull(leaseFactory), work)
        }
    }

    private fun <T> runWithTestLease(
        factory: ConversionExecutionLeaseFactory,
        work: () -> T
    ): T = factory.acquire().use { lease ->
        check(lease.isHeld) { "Media-conversion execution protection was not acquired" }
        val result = work()
        check(lease.isHeld) { "Media-conversion execution protection was lost" }
        result
    }

    /**
     * Android lint deliberately requires acquire/release to remain in one lexical lifetime. Keeping
     * the complete foreground-service and wake-lock transaction here also guarantees that a service
     * release failure cannot skip wake-lock release.
     */
    @SuppressLint("WakelockTimeout")
    private fun <T> runWithAndroidProtection(context: Context, work: () -> T): T {
        val powerManager = context.getSystemService(PowerManager::class.java)
            ?: throw IllegalStateException("PowerManager unavailable for media conversion")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }
        try {
            wakeLock.acquire()
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Unable to acquire media-conversion wake lock",
                failure
            )
        }
        var foregroundToken = 0L
        var protectionEstablished = false
        try {
            // The synchronous conversion transaction is the exact lease lifetime. A fixed timeout
            // would abort valid long-form masters, while this finally block is exception-complete.
            check(wakeLock.isHeld) { "Android did not grant the media-conversion wake lock" }
            foregroundToken = MediaConversionForegroundService.acquire(context)
            check(MediaConversionForegroundService.isActive(foregroundToken)) {
                "Media-conversion foreground execution protection was not acquired"
            }
            protectionEstablished = true
            val result = work()
            check(wakeLock.isHeld && MediaConversionForegroundService.isActive(foregroundToken)) {
                "Media-conversion execution protection was lost"
            }
            return result
        } catch (failure: Throwable) {
            if (!protectionEstablished) {
                throw IllegalStateException(
                    "Unable to protect media conversion from sleep and background CPU restriction",
                    failure
                )
            }
            throw failure
        } finally {
            try {
                if (foregroundToken != 0L) {
                    MediaConversionForegroundService.release(context, foregroundToken)
                }
            } finally {
                wakeLock.release()
            }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "NeoProCamX:Niv99MediaConversion"
    }
}
