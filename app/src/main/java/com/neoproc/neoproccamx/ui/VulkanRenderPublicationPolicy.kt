package com.neoproc.neoproccamx.ui

/** Result of reconciling a requested preview LUT with the generation-bound native upload. */
internal data class NativePreviewLutPublication(
    val snapshot: NativePreviewLutSnapshot,
    val softwareFallbackRequired: Boolean
)

/** Native LUT identity is published only after the matching upload is accepted. */
internal object NativePreviewLutPublicationPolicy {
    fun resolve(
        desiredEnabled: Boolean,
        desiredSize: Int,
        desiredContentHash: Int,
        renderProfileGeneration: Long,
        nativeUploadAccepted: Boolean,
        previousNativeSnapshot: NativePreviewLutSnapshot? = null,
        nativeDisableAcceptedAfterFailure: Boolean? = null
    ): NativePreviewLutPublication {
        require(desiredSize >= 0) { "LUT size cannot be negative" }
        require(!desiredEnabled || desiredSize > 1) { "Enabled LUTs require a valid cube size" }
        val previous = previousNativeSnapshot ?: NativePreviewLutSnapshot(
            renderProfileGeneration = renderProfileGeneration
        )
        val replacementSafelyDisabled = nativeDisableAcceptedAfterFailure == true
        val softwareFallbackSafe = desiredEnabled && !nativeUploadAccepted &&
            (!previous.enabled || replacementSafelyDisabled)
        val preservePreviousNativeIdentity = !nativeUploadAccepted &&
            previous.enabled && !replacementSafelyDisabled
        val nativeEnabled = when {
            nativeUploadAccepted -> desiredEnabled
            preservePreviousNativeIdentity -> true
            else -> false
        }
        return NativePreviewLutPublication(
            snapshot = NativePreviewLutSnapshot(
                enabled = nativeEnabled,
                size = when {
                    nativeUploadAccepted && nativeEnabled -> desiredSize
                    preservePreviousNativeIdentity -> previous.size
                    else -> 0
                },
                contentHash = when {
                    nativeUploadAccepted && nativeEnabled -> desiredContentHash
                    preservePreviousNativeIdentity -> previous.contentHash
                    else -> 0
                },
                renderProfileGeneration = renderProfileGeneration
            ),
            softwareFallbackRequired = softwareFallbackSafe
        )
    }
}
