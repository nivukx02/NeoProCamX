package com.neoproc.neoproccamx.camera

/** Fail-closed two-hop dispatch used only by the Camera2 startup-lease publication path. */
internal object Npv10AuthorizedCameraDispatch {
    fun dispatch(
        execute: (Runnable) -> Unit,
        post: (Runnable) -> Boolean,
        action: () -> Unit,
        onFailure: (reason: String, failure: Throwable?) -> Unit
    ) {
        val guardedAction = Runnable {
            try {
                action()
            } catch (failure: Throwable) {
                onFailure("Authorized NPV10 camera action threw", failure)
            }
        }
        try {
            execute(Runnable {
                val postResult = runCatching { post(guardedAction) }
                val failure = postResult.exceptionOrNull()
                if (failure != null) {
                    onFailure("Authorized NPV10 camera-handler post threw", failure)
                } else if (!postResult.getOrDefault(false)) {
                    onFailure("Authorized NPV10 camera-handler post was rejected", null)
                }
            })
        } catch (failure: Throwable) {
            onFailure("Authorized NPV10 camera-executor dispatch was rejected", failure)
        }
    }
}

/** The replay can retire its lease only for the current session's authoritative P010 request. */
internal object Npv10ProducerStartupCommitPolicy {
    fun canCommitReplay(
        releaseRequested: Boolean,
        p010TargetPresent: Boolean,
        sessionCurrent: Boolean,
        submissionGeneration: Long,
        leaseGeneration: Long
    ): Boolean = releaseRequested && p010TargetPresent && sessionCurrent &&
        submissionGeneration == leaseGeneration
}
