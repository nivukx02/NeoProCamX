package com.neoproc.neoproccamx.ui

internal enum class CameraUiRenderPass {
    STATUS_CHROME,
    TOP_WARNING
}

internal class CameraUiRenderScheduler(
    private val framePoster: (Runnable) -> Unit,
    private val render: (Set<CameraUiRenderPass>) -> Unit
) {
    private val pending = linkedSetOf<CameraUiRenderPass>()
    private var posted = false
    private var generation = 0

    fun request(pass: CameraUiRenderPass) {
        pending.add(pass)
        if (posted) return
        posted = true
        val expectedGeneration = generation
        framePoster(
            Runnable {
                if (expectedGeneration != generation) return@Runnable
                val passes = pending.toSet()
                pending.clear()
                posted = false
                if (passes.isNotEmpty()) {
                    render(passes)
                }
            }
        )
    }

    fun cancel() {
        generation += 1
        pending.clear()
        posted = false
    }
}
