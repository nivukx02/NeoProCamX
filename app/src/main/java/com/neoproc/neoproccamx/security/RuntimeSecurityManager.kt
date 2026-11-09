package com.neoproc.neoproccamx.security

import android.content.Context
import android.util.Log
import com.neoproc.neoproccamx.BuildConfig
import com.neoproc.neoproccamx.utils.CryptoUtils

data class RuntimeSecurityReport(
    val tampered: Boolean,
    val rooted: Boolean,
    val emulator: Boolean,
    val debuggerAttached: Boolean,
    val startupAction: RuntimeSecurityStartupAction,
    val reasons: Set<String>,
    val observedFingerprints: Set<String>,
    val allowedFingerprints: Set<String>
) {
    val shouldBlockStartup: Boolean
        get() = startupAction == RuntimeSecurityStartupAction.BLOCK
}

class RuntimeSecurityManager(private val context: Context) {
    companion object {
        private const val TAG = "RuntimeSecurity"
    }

    private val playIntegrityClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (AppSecurityBuildConfig.playIntegrityEnabled) {
            PlayIntegrityClient(
                context = context.applicationContext,
                cloudProjectNumber = AppSecurityBuildConfig.playIntegrityProjectNumber
            )
        } else {
            null
        }
    }

    @Volatile
    private var currentReport = RuntimeSecurityReport(
        tampered = false,
        rooted = false,
        emulator = false,
        debuggerAttached = false,
        startupAction = RuntimeSecurityStartupAction.ALLOW,
        reasons = emptySet(),
        observedFingerprints = emptySet(),
        allowedFingerprints = emptySet()
    )

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(): RuntimeSecurityReport {
        if (initialized) return currentReport
        val signingInspection = ApkTamperGuard.inspect(context)
        val deviceSignals = DeviceRiskInspector.inspect()
        val decision = RuntimeAppSecurityPolicy.evaluate(
            isDebugBuild = BuildConfig.DEBUG,
            tampered = !signingInspection.trustedInstall,
            rooted = deviceSignals.rooted,
            emulator = deviceSignals.emulator,
            debuggerAttached = deviceSignals.debuggerAttached,
            blockOnTamper = BuildConfig.SECURITY_BLOCK_ON_TAMPER,
            blockOnCompromisedDevice = BuildConfig.SECURITY_BLOCK_ON_COMPROMISED_DEVICE
        )
        return RuntimeSecurityReport(
            tampered = !signingInspection.trustedInstall,
            rooted = deviceSignals.rooted,
            emulator = deviceSignals.emulator,
            debuggerAttached = deviceSignals.debuggerAttached,
            startupAction = decision.startupAction,
            reasons = decision.reasons,
            observedFingerprints = signingInspection.observedFingerprints,
            allowedFingerprints = signingInspection.allowedFingerprints
        ).also { report ->
            currentReport = report
            when (report.startupAction) {
                RuntimeSecurityStartupAction.BLOCK ->
                    Log.e(TAG, "Blocking runtime due to security policy reasons=${report.reasons}. Observed fingerprints=${report.observedFingerprints}. Allowed fingerprints=${report.allowedFingerprints}")
                RuntimeSecurityStartupAction.WARN ->
                    Log.w(TAG, "Runtime security warnings detected reasons=${report.reasons}")
                RuntimeSecurityStartupAction.ALLOW ->
                    Log.i(TAG, "Runtime security checks passed")
            }
            initialized = true
        }
    }

    fun getReport(): RuntimeSecurityReport =
        if (initialized) currentReport else initialize()

    suspend fun warmUpPlayIntegrityIfAvailable(): Boolean =
        runCatching { playIntegrityClient?.warmUp() ?: false }
            .onFailure { Log.w(TAG, "Play Integrity warmup failed", it) }
            .getOrDefault(false)

    suspend fun createIntegrityBackendRequest(
        action: String,
        payload: ByteArray,
        installationSecretId: String? = null
    ): IntegrityBackendRequest? {
        val client = playIntegrityClient ?: return null
        require(installationSecretId == null || installationSecretId.length in 16..256) {
            "Invalid installation secret identifier"
        }
        val securityReport = initialize()
        val binding = PlayIntegrityRequestPolicy.bindRequest(
            action = action,
            payload = payload,
            requestId = CryptoUtils.generateSecureToken(24),
            issuedAtEpochMs = System.currentTimeMillis()
        )
        val token = try {
            client.requestIntegrityToken(binding.tokenPayload)
        } finally {
            CryptoUtils.secureWipe(binding.tokenPayload)
        }
        return IntegrityBackendRequest(
            action = action,
            requestId = binding.requestId,
            issuedAtEpochMs = binding.issuedAtEpochMs,
            requestHash = binding.requestHash,
            integrityToken = token,
            appVersion = BuildConfig.VERSION_NAME,
            securitySignals = securityReport.reasons,
            installationSecretId = installationSecretId
        )
    }
}
