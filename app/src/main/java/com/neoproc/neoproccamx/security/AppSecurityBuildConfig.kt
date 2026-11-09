package com.neoproc.neoproccamx.security

import com.neoproc.neoproccamx.BuildConfig

internal object AppSecurityBuildConfig {
    val allowedSigningFingerprints: Set<String>
        get() = AppSigningPolicy.parseAllowedFingerprints(BuildConfig.SECURITY_ALLOWED_SIGNING_CERT_SHA256)

    val backendPins: Map<String, List<String>>
        get() = BackendPinningConfig.parse(BuildConfig.SECURITY_SSL_PINS)

    val playIntegrityEnabled: Boolean
        get() = BuildConfig.PLAY_INTEGRITY_ENABLED && BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER > 0L

    val playIntegrityProjectNumber: Long
        get() = BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER
}
