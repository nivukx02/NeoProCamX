package com.neoproc.neoproccamx.rendering

/**
 * Retains the newest render-profile replacement while an asynchronous RAW output still owns the
 * current native profile. The recorder drains that exact output before the replacement is retried,
 * preventing either a mixed-profile encode or a dropped/terminal frame during live profile updates.
 */
internal class NdrRenderProfileMutationDeferral {
    internal enum class Admission {
        PROCEED,
        STALE_REVISION
    }

    private var pending: NdrRenderProfileIdentity? = null

    @Synchronized
    fun defer(identity: NdrRenderProfileIdentity): NdrRenderProfileIdentity {
        require(identity.valid) { "Deferred NDR render identity must be valid" }
        val retained = pending
        if (retained == null ||
            identity.profileGeneration > retained.profileGeneration ||
            (identity.profileGeneration == retained.profileGeneration &&
                identity.configRevision > retained.configRevision)
        ) {
            pending = identity
        }
        return requireNotNull(pending)
    }

    @Synchronized
    fun admission(identity: NdrRenderProfileIdentity): Admission {
        val retained = pending ?: return Admission.PROCEED
        val stale = identity.profileGeneration < retained.profileGeneration ||
            (identity.profileGeneration == retained.profileGeneration &&
                identity.configRevision < retained.configRevision)
        if (stale) return Admission.STALE_REVISION
        pending = null
        return Admission.PROCEED
    }

    @Synchronized
    fun pendingIdentity(): NdrRenderProfileIdentity? = pending

    @Synchronized
    fun readyIdentity(drained: Boolean): NdrRenderProfileIdentity? =
        pending.takeIf { drained }

    @Synchronized
    fun clear() {
        pending = null
    }
}
