package com.neoproc.neoproccamx.security

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object PinnedBackendClient {
    fun create(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 30_000L,
        writeTimeoutMs: Long = 30_000L,
        pins: Map<String, List<String>> = AppSecurityBuildConfig.backendPins
    ): OkHttpClient {
        require(connectTimeoutMs in 1..120_000L) { "Invalid backend connect timeout" }
        require(readTimeoutMs in 1..120_000L) { "Invalid backend read timeout" }
        require(writeTimeoutMs in 1..120_000L) { "Invalid backend write timeout" }
        require(pins.isNotEmpty()) { "Pinned backend client requires at least one configured host" }

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .followRedirects(false)
            .followSslRedirects(false)

        val certificatePinner = CertificatePinner.Builder()
        pins.forEach { (host, hostPins) ->
            require(hostPins.isNotEmpty()) { "Pinned backend host has no pins" }
            hostPins.forEach { pin ->
                certificatePinner.add(host, pin)
            }
        }
        builder.certificatePinner(certificatePinner.build())

        return builder.build()
    }
}
