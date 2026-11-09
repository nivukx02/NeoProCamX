package com.neoproc.neoproccamx.niv99

/**
 * Stable non-cryptographic camera identifier used only by the native frame-provenance packet.
 * Camera IDs are not logged or used as an authorization secret. Hashing avoids JNI string work on
 * every frame while retaining an exact, deterministic identity for stale-packet rejection.
 */
object Niv99CameraIdentityHash {
    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    fun of(cameraId: String): Long {
        if (cameraId.isBlank()) return 0L
        var hash = FNV_OFFSET_BASIS
        for (character in cameraId) {
            val value = character.code
            hash = (hash xor (value and 0xff).toLong()) * FNV_PRIME
            hash = (hash xor ((value ushr 8) and 0xff).toLong()) * FNV_PRIME
        }
        // Native uses zero as the incomplete-identity sentinel.
        return if (hash == 0L) 1L else hash
    }
}
