// REJECTED experiment; this file is outside all application source sets.
package com.neoproc.neoproccamx.recording

import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Moves F2FS physical block assignment out of Cinema frame writes and into reservation work.
 * The native operation temporarily pins only the allocation inode, restores its prior pin state,
 * and preserves the existing POSIX reservation on unsupported filesystems/Android policies.
 * Every allocation, restoration and descriptor error still propagates to recording admission.
 */
internal object Npv10PhysicalExtentAllocator {
    data class Result(val materialized: Boolean, val capabilityErrno: Int)

    private val unsupportedReported = AtomicBoolean(false)

    fun allocate(descriptor: FileDescriptor, offsetBytes: Long, lengthBytes: Long): Result {
        require(offsetBytes >= 0L && lengthBytes > 0L) { "Invalid physical extent bounds" }
        Math.addExact(offsetBytes, lengthBytes)
        val result = if (Npv10DirectMappedCommitNative.libraryAvailable) {
            // A duplicate keeps this exact opened inode alive across JNI; no path reopen,
            // reflection of hidden FileDescriptor fields, or ownership transfer to native code.
            ParcelFileDescriptor.dup(descriptor).use { owned ->
                decodeNativeResult(nativeAllocate(owned.fd, offsetBytes, lengthBytes))
            }
        } else {
            Os.posix_fallocate(descriptor, offsetBytes, lengthBytes)
            Result(materialized = false, capabilityErrno = 38) // ENOSYS: native route unavailable.
        }
        if (result.materialized && offsetBytes == 0L) {
            Log.i(TAG, "NPV10 extent backend=F2FS_PRE_DIO temporaryPinRestored=true " +
                "offset=$offsetBytes bytes=$lengthBytes")
        } else if (!result.materialized && unsupportedReported.compareAndSet(false, true)) {
            Log.i(TAG, "NPV10 extent backend=POSIX_RESERVED physicalMaterialization=false " +
                "capabilityErrno=${result.capabilityErrno}; existing reservation checks retained")
        }
        return result
    }

    internal fun decodeNativeResult(values: IntArray): Result {
        if (values.size != 4 || values.any { it < 0 } || values[3] !in 0..1) {
            throw IOException("Malformed NPV10 physical extent result")
        }
        if (values[0] != 0 || values[2] != 0) {
            throw IOException("NPV10 physical extent failed: errno=${values[0]} " +
                "capabilityErrno=${values[1]} pinReleaseErrno=${values[2]}")
        }
        if ((values[3] == 1) != (values[1] == 0)) {
            throw IOException("NPV10 physical extent capability/result mismatch")
        }
        return Result(materialized = values[3] == 1, capabilityErrno = values[1])
    }

    private const val TAG = "Npv10PhysicalExtent"

    @JvmStatic
    private external fun nativeAllocate(descriptor: Int, offsetBytes: Long, lengthBytes: Long): IntArray
}
