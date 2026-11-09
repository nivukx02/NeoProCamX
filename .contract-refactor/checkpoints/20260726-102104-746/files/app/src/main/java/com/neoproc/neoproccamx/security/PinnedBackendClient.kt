package com.neoproc.neoproccamx.security

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object PinnedBackendClient {
    fun create(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 30_000L,
        writeTimeoutMs: Long = 30_000L,
        pins: Map<String, List<String>> = AppSecurityBuildConfig.backendPins
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)

        if (pins.isNotEmpty()) {
            val certificatePinner = CertificatePinner.Builder()
            pins.forEach { (host, hostPins) ->
                hostPins.forEach { pin ->
                    certificatePinner.add(host, pin)
                }
            }
            builder.certificatePinner(certificatePinner.build())
        }

        return builder.build()
    }
}
