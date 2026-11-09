package com.neoproc.neoproccamx.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.neoproc.neoproccamx.utils.CryptoUtils

data class SigningInspectionResult(
    val trustedInstall: Boolean,
    val observedFingerprints: Set<String>,
    val allowedFingerprints: Set<String>,
    val hasMultipleSigners: Boolean
)

object ApkTamperGuard {
    fun inspect(context: Context): SigningInspectionResult {
        val allowedFingerprints = AppSecurityBuildConfig.allowedSigningFingerprints
        if (allowedFingerprints.isEmpty()) {
            return SigningInspectionResult(
                trustedInstall = true,
                observedFingerprints = emptySet(),
                allowedFingerprints = emptySet(),
                hasMultipleSigners = false
            )
        }

        return try {
            val packageInfo = context.packageManager.getSigningPackageInfo(context.packageName)
            val (signatures, multipleSigners) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signingInfo = packageInfo.signingInfo
                    val hasMultiple = signingInfo?.hasMultipleSigners() == true
                    val signers = when {
                        signingInfo == null -> emptyArray()
                        hasMultiple -> signingInfo.apkContentsSigners
                        else -> signingInfo.signingCertificateHistory
                    }
                    signers to hasMultiple
                } else {
                    @Suppress("DEPRECATION")
                    val signers = packageInfo.signatures ?: emptyArray()
                    signers to false
                }
            val observedFingerprints = signatures
                .map { CryptoUtils.sha256(it.toByteArray()) }
                .map(AppSigningPolicy::normalizeFingerprint)
                .toSet()
            val trustedInstall = AppSigningPolicy.isTrustedInstalledSigners(
                observedFingerprints = observedFingerprints,
                allowedFingerprints = allowedFingerprints,
                hasMultipleSigners = multipleSigners
            )
            SigningInspectionResult(
                trustedInstall = trustedInstall,
                observedFingerprints = observedFingerprints,
                allowedFingerprints = allowedFingerprints,
                hasMultipleSigners = multipleSigners
            )
        } catch (_: Exception) {
            SigningInspectionResult(
                trustedInstall = false,
                observedFingerprints = emptySet(),
                allowedFingerprints = allowedFingerprints,
                hasMultipleSigners = false
            )
        }
    }
}

private fun PackageManager.getSigningPackageInfo(packageName: String): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }
}
