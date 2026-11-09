package com.neoproc.neoproccamx

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.infrastructure.telemetry.TelemetryCollector
import com.neoproc.neoproccamx.recording.CinemaCamMtpPublicationScheduler
import com.neoproc.neoproccamx.recording.RecordingMediaStoreOutputStore
import com.neoproc.neoproccamx.security.AsyncSecurityInitFlag
import com.neoproc.neoproccamx.security.RuntimeSecurityManager
import com.neoproc.neoproccamx.security.RuntimeSecurityReport
import com.neoproc.neoproccamx.security.SecureSecretVault
import com.neoproc.neoproccamx.tessera.TesseraRuntimeProvider
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * NeoProCamXApp — High-Performance Application Singleton
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Optimized for fast cold start:
 *  - Deferred initialization of non-critical components
 *  - Async background warmup for AI/Vulkan
 *  - Minimal onCreate() blocking time
 *
 * Provides:
 *  - Centralized PreferencesManager instance
 *  - Global crash handler / logger
 *  - Background warmup for heavy components
 */
class NeoProCamXApp : Application(), ViewModelStoreOwner {

    // Camera/recording ownership is process-scoped so an Activity reclaimed while a foreground
    // recording is active cannot tear down the lossless packet writers or Camera2 data session.
    override val viewModelStore: ViewModelStore = ViewModelStore()

    companion object {
        private const val TAG = "NeoProCamXApp"
        
        @Volatile
        lateinit var instance: NeoProCamXApp
            private set
        
        /** True when background warmup is complete */
        val isWarmedUp: Boolean
            get() = _warmedUp.get()
        
        private val _warmedUp = AtomicBoolean(false)
    }

    /** Application-scoped coroutines for background work */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /** Preferences - loaded synchronously (fast, needed immediately) */
    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var runtimeSecurityManager: RuntimeSecurityManager
        private set

    lateinit var secureSecretVault: SecureSecretVault
        private set

    /**
     * FIX D-10: when [AsyncSecurityInitFlag] is enabled, the early Deferred that runs the runtime
     * security tamper/root evaluation off the main thread. The startup gate does NOT await this handle
     * directly — [RuntimeSecurityManager.getReport] performs or awaits the synchronized evaluation — so this is kept
     * only so the launch is an explicit Deferred (never a fire-and-forget). Null while the flag is OFF.
     */
    @Volatile
    private var securityInitDeferred: Deferred<RuntimeSecurityReport>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.neoproc.neoproccamx.utils.BootTrace.mark("app.onCreate.start")
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        // Device-adaptive scaling: cap the strict-high-end escalation at what THIS device can sustain,
        // so a weaker phone records its native-sensor maximum instead of being forced to (and rejected
        // for missing) 4K/flagship. Default ceiling is FLAGSHIP = no cap, so this only ever RELAXES a
        // genuinely weaker device — a capable one is unchanged. Probed synchronously so the first frame
        // already sees the correct ceiling; runCatching so a null service never mis-demotes a device.
        runCatching {
            val totalMemMb =
                (getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                    ?.let { am -> android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo) }
                    ?.totalMem?.takeIf { it > 0L }?.div(1024L * 1024L)
                    ?: (Runtime.getRuntime().maxMemory() / (1024L * 1024L))
            HighEndQualityPolicy.deviceCapabilityCeiling =
                com.neoproc.neoproccamx.pipeline.DeviceCapabilityCeilingPolicy.resolve(
                    totalMemMb = totalMemMb,
                    cpuCores = Runtime.getRuntime().availableProcessors()
                )
        }.onFailure { Log.w(TAG, "device-capability ceiling probe failed; keeping FLAGSHIP", it) }
        setupUncaughtExceptionHandler()
        val pendingMovRecoveryCutoffSeconds =
            (System.currentTimeMillis() / 1_000L - 1L).coerceAtLeast(0L)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                RecordingMediaStoreOutputStore(this@NeoProCamXApp)
                    .recoverPendingMovOutputsCreatedBefore(pendingMovRecoveryCutoffSeconds)
            }.onFailure { Log.w(TAG, "Pending recording recovery failed", it) }
            // Older Cinema Cam builds kept NPV10 and record-develop masters exclusively in the
            // app-private library. Reconcile them on a serialized background lane so Windows MTP can
            // discover the same verified conversion sources without USB debugging. Direct-gallery
            // videos are never copied into this conversion-only publication path.
            CinemaCamMtpPublicationScheduler.reconcile(this@NeoProCamXApp)
        }

        // Bring up the on-device camera diagnostics log BEFORE the camera can open. Many devices
        // (e.g. vivo/OriginOS) suppress third-party app logcat, so the camera-pipeline negotiation
        // and fallback events would otherwise be invisible. getExternalFilesDir(...) resolves to
        // /sdcard/Android/data/<pkg>/files which is pullable via `adb pull` without run-as.
        if (!AsyncSecurityInitFlag.enabled) {
            runCatching {
                NeoLogger.initialize(getExternalFilesDir(null) ?: filesDir)
                NeoLogger.i(TAG, "NeoProCamX ${BuildConfig.VERSION_NAME} start; on-device camera diagnostics log active")
            }.onFailure { Log.w(TAG, "NeoLogger init failed (non-critical)", it) }
        }
        
        // ABSOLUTE MINIMUM - only what's required for UI to start
        preferencesManager = PreferencesManager(this)
        runtimeSecurityManager = RuntimeSecurityManager(this)
        secureSecretVault = SecureSecretVault(this)
        if (AsyncSecurityInitFlag.enabled) {
            // FIX D-10: run the tamper/root evaluation OFF the main thread as an early Deferred, and
            // create the on-device diagnostics log on an IO task. The MainActivity startup gate awaits
            // this verdict via fail-closed RuntimeSecurityManager.getReport(), so there is no silent bypass.
            securityInitDeferred = appScope.async(Dispatchers.Default) {
                runtimeSecurityManager.initialize()
            }
            appScope.launch(Dispatchers.IO) {
                runCatching {
                    NeoLogger.initialize(getExternalFilesDir(null) ?: filesDir)
                    NeoLogger.i(TAG, "NeoProCamX ${BuildConfig.VERSION_NAME} start; on-device camera diagnostics log active")
                }.onFailure { Log.w(TAG, "NeoLogger init failed (non-critical)", it) }
            }
        } else {
            runtimeSecurityManager.initialize()
        }

        // QW-4a: Dispatch the neoproc_vulkan dlopen onto its OWN IO task first, so the
        // first-frame-critical native library load runs concurrently with — and is never
        // gated behind — the Keystore crypto and the (network-bound) Play-Integrity warmup
        // in performBackgroundWarmup(). Idempotent: System.loadLibrary is a no-op once loaded
        // and every rendering bridge still loads it defensively on first use.
        appScope.launch(Dispatchers.IO) {
            try {
                System.loadLibrary("neoproc_vulkan")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library pre-load failed (will retry on first use)")
            } catch (e: SecurityException) {
                Log.w(TAG, "Native library pre-load blocked by security policy")
            }
        }

        // Defer EVERYTHING else to background - zero blocking
        appScope.launch(Dispatchers.IO) {
            performBackgroundWarmup()
            _warmedUp.set(true)
        }
        
        com.neoproc.neoproccamx.utils.BootTrace.mark("app.onCreate.end")
        // Quick log (no expensive calls)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "NeoProCamX instant start")
        }
    }

    /**
     * Background warmup - runs at MINIMUM priority, NEVER blocks UI.
     */
    private suspend fun performBackgroundWarmup() {
        try {
            // App-private, durable and local-only by default. Upload remains fail-closed until a future
            // consent surface supplies both explicit consent and an HTTPS endpoint.
            TelemetryCollector.initialize(java.io.File(filesDir, "telemetry"))
            secureSecretVault.ensureMasterKey()
            secureSecretVault.getOrCreateInstallationSecret()
            runtimeSecurityManager.initialize()
            runtimeSecurityManager.warmUpPlayIntegrityIfAvailable()

            // QW-4a: native neoproc_vulkan pre-load is now dispatched to its own IO task in
            // onCreate() — before this crypto/Play-Integrity work — so the first-frame-critical
            // dlopen is never blocked behind the Play-Integrity network round-trip.
            
            // Touch JIT classes in background
            warmupJitClasses()
        } catch (e: Exception) {
            Log.w(TAG, "Background warmup encountered non-critical error", e)
        }
    }

    /**
     * Touch JIT-heavy classes to warm up the runtime.
     * This helps reduce first-frame jank.
     */
    private fun warmupJitClasses() {
        // Touch coroutine infrastructure
        kotlinx.coroutines.flow.MutableStateFlow(0)
        
        // Touch common collections
        java.util.concurrent.ConcurrentHashMap<String, Any>()
        java.util.ArrayDeque<Any>()
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL: Uncaught exception on thread ${thread.name}", throwable)
                // Persist crash info for post-mortem analysis
                val crashInfo = "Thread: ${thread.name}\nException: ${throwable}\n" +
                    "Stack: ${throwable.stackTraceToString().take(2000)}"
                try {
                    val file = java.io.File(filesDir, "last_crash.txt")
                    file.writeText(crashInfo)
                } catch (_: Exception) { /* Best-effort persistence */ }
                // Mirror to external files dir so the crash trace is pullable without run-as.
                try {
                    getExternalFilesDir(null)?.let { ext ->
                        java.io.File(ext, "last_crash.txt").writeText(crashInfo)
                    }
                } catch (_: Exception) { /* Best-effort persistence */ }
            } catch (_: Exception) { /* Never throw from crash handler */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        TelemetryCollector.shutdown()
        viewModelStore.clear()
        TesseraRuntimeProvider.shutdown()
        super.onTerminate()
    }
}
