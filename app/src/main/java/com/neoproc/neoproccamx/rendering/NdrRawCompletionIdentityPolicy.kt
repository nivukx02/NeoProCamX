package com.neoproc.neoproccamx.rendering

/** Fail-closed decision for a RAW output whose Vulkan completion arrived asynchronously. */
internal object NdrRawCompletionIdentityPolicy {
    enum class Decision {
        DELIVER_EXACT,
        RETIRE_STALE
    }

    fun resolve(
        dispatchIdentity: NdrRenderProfileIdentity?,
        currentIdentity: NdrRenderProfileIdentity?,
        cameraSessionCurrent: Boolean
    ): Decision = if (
        cameraSessionCurrent &&
        dispatchIdentity != null &&
        dispatchIdentity.valid &&
        currentIdentity?.valid == true &&
        dispatchIdentity == currentIdentity
    ) {
        Decision.DELIVER_EXACT
    } else {
        Decision.RETIRE_STALE
    }
}
