package com.neoproc.neoproccamx.bridge

import java.security.MessageDigest
import java.util.Locale

internal data class CamxBridgeArtifactDescriptor(
    val id: String,
    val name: String,
    val extension: String,
    val bytes: Long,
    val modifiedAtEpochMs: Long,
    val mimeType: String,
    val role: String
)

internal data class CamxBridgeMediaDescriptor(
    val artifact: CamxBridgeArtifactDescriptor,
    val category: String,
    val origin: String,
    val companions: List<CamxBridgeArtifactDescriptor>
)

/**
 * Stable wire contract shared with CamX Studio.
 *
 * The bridge is intentionally read-only and contains no recording controls. Android's ADB RSA
 * authorization plus the provider's signature-level DUMP permission authenticates the desktop
 * transport; the package/signing identity and deterministic catalogue fingerprint are still
 * included so the desktop can fail closed on malformed or substituted payloads.
 */
internal object CamxDesktopBridgeContract {
    const val AUTHORITY = "com.neoproc.neoproccamx.desktopbridge"
    const val SCHEMA = "com.neoproc.camx.desktop-bridge.catalog.v1"
    const val PROTOCOL_VERSION = 1
    const val AUTHENTICATION = "ADB_RSA_ANDROID_DUMP_PERMISSION"

    private val supportedMediaExtensions = setOf(
        ".mov", ".mp4", ".mkv", ".mxf", ".avi", ".webm", ".m4v", ".hevc",
        ".npv10", ".npraw", ".dng", ".raw", ".nivukx"
    )
    private val rawExtensions = setOf(".npv10", ".npraw", ".dng", ".raw")

    fun normalizedExtension(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf(String::isNotEmpty)
            ?.let { ".${it.lowercase(Locale.US)}" }
            .orEmpty()

    fun isSupportedMediaName(name: String): Boolean =
        normalizedExtension(name) in supportedMediaExtensions

    fun categoryFor(name: String): String =
        if (normalizedExtension(name) in rawExtensions) "raw" else "sdr"

    fun stableArtifactId(sourceKind: String, sourceIdentity: String): String =
        sha256Hex("$SCHEMA\u0000$sourceKind\u0000$sourceIdentity".toByteArray(Charsets.UTF_8))

    fun artifactUri(id: String): String = "content://$AUTHORITY/artifact/$id"

    fun digestUri(id: String): String = "content://$AUTHORITY/digest/$id"

    fun catalogFingerprint(items: List<CamxBridgeMediaDescriptor>): String {
        val canonical = items.sortedBy { it.artifact.id }.joinToString(separator = "\n") { item ->
            buildString {
                append(item.artifact.id).append('\u0000')
                append(item.artifact.name).append('\u0000')
                append(item.artifact.bytes).append('\u0000')
                append(item.artifact.modifiedAtEpochMs).append('\u0000')
                append(item.category).append('\u0000')
                append(item.origin)
                item.companions.sortedWith(compareBy({ it.role }, { it.id })).forEach { companion ->
                    append('\u0000').append(companion.role)
                    append('\u0000').append(companion.id)
                    append('\u0000').append(companion.name)
                    append('\u0000').append(companion.bytes)
                }
            }
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    fun buildCatalogJson(
        packageName: String,
        versionName: String,
        versionCode: Long,
        signingCertificateSha256: String,
        deviceIdSha256: String,
        generatedAtEpochMs: Long,
        items: List<CamxBridgeMediaDescriptor>
    ): String = buildString {
        append('{')
        appendJsonField("schema", SCHEMA).append(',')
        append("\"protocolVersion\":").append(PROTOCOL_VERSION).append(',')
        appendJsonField("authentication", AUTHENTICATION).append(',')
        appendJsonField("packageName", packageName).append(',')
        appendJsonField("versionName", versionName).append(',')
        append("\"versionCode\":").append(versionCode).append(',')
        appendJsonField("signingCertificateSha256", signingCertificateSha256).append(',')
        appendJsonField("deviceIdSha256", deviceIdSha256).append(',')
        append("\"generatedAtEpochMs\":").append(generatedAtEpochMs).append(',')
        appendJsonField("catalogFingerprint", catalogFingerprint(items)).append(',')
        append("\"items\":[")
        items.sortedWith(compareByDescending<CamxBridgeMediaDescriptor> { it.artifact.modifiedAtEpochMs }
            .thenBy { it.artifact.name })
            .forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendMediaItem(item)
            }
        append("]}")
    }

    fun buildDigestJson(id: String, bytes: Long, sha256: String): String = buildString {
        append('{')
        appendJsonField("schema", "com.neoproc.camx.desktop-bridge.digest.v1").append(',')
        appendJsonField("artifactId", id).append(',')
        append("\"bytes\":").append(bytes).append(',')
        appendJsonField("sha256", sha256)
        append('}')
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { value ->
            "%02x".format(Locale.US, value.toInt() and 0xff)
        }

    private fun StringBuilder.appendMediaItem(item: CamxBridgeMediaDescriptor) {
        append('{')
        appendJsonField("id", item.artifact.id).append(',')
        appendJsonField("name", item.artifact.name).append(',')
        appendJsonField("extension", item.artifact.extension).append(',')
        append("\"size\":").append(item.artifact.bytes).append(',')
        append("\"modifiedAtEpochMs\":").append(item.artifact.modifiedAtEpochMs).append(',')
        appendJsonField("mimeType", item.artifact.mimeType).append(',')
        appendJsonField("category", item.category).append(',')
        appendJsonField("origin", item.origin).append(',')
        appendJsonField("contentUri", artifactUri(item.artifact.id)).append(',')
        appendJsonField("digestUri", digestUri(item.artifact.id)).append(',')
        append("\"companions\":[")
        item.companions.sortedWith(compareBy({ it.role }, { it.name })).forEachIndexed { index, companion ->
            if (index > 0) append(',')
            append('{')
            appendJsonField("id", companion.id).append(',')
            appendJsonField("name", companion.name).append(',')
            appendJsonField("extension", companion.extension).append(',')
            append("\"size\":").append(companion.bytes).append(',')
            append("\"modifiedAtEpochMs\":").append(companion.modifiedAtEpochMs).append(',')
            appendJsonField("mimeType", companion.mimeType).append(',')
            appendJsonField("role", companion.role).append(',')
            appendJsonField("contentUri", artifactUri(companion.id)).append(',')
            appendJsonField("digestUri", digestUri(companion.id))
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendJsonField(name: String, value: String): StringBuilder =
        append('"').append(jsonEscape(name)).append("\":\"").append(jsonEscape(value)).append('"')

    internal fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
