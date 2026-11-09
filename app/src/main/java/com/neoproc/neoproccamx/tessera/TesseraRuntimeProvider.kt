package com.neoproc.neoproccamx.tessera

/** Process-wide scheduler shared by camera-supporting background and heterogeneous work. */
internal object TesseraRuntimeProvider {
    private val lock = Any()

    @Volatile
    private var installedConfig: TesseraRuntimeConfig = TesseraRuntimeConfig.forDevice()

    @Volatile
    private var activeRuntime: TesseraComputeRuntime? = null

    fun configure(config: TesseraRuntimeConfig) {
        synchronized(lock) {
            check(activeRuntime == null) { "Tessera runtime is already active" }
            installedConfig = config
        }
    }

    fun runtime(): TesseraComputeRuntime {
        activeRuntime?.let { return it }
        return synchronized(lock) {
            activeRuntime ?: TesseraComputeRuntime(installedConfig).also { activeRuntime = it }
        }
    }

    fun reportBackendAvailability(backend: TesseraExecutionBackend, available: Boolean) {
        runtime().reportBackendAvailability(backend, available)
    }

    fun shutdown() {
        synchronized(lock) {
            val runtime = activeRuntime ?: return
            // Publish null while holding the creation lock, then retain that lock until close's strong
            // retirement barrier completes. runtime() may observe null, but cannot install a replacement
            // whose workers overlap payloads from the retiring process-wide scheduler.
            activeRuntime = null
            runtime.close()
        }
    }
}
