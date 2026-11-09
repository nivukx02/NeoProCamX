package com.neoproc.neoproccamx.recording

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

internal data class NivukxTruthRecord(
    val tier: String,
    val payload: Map<String, String>,
    val previousHash: String,
    val hash: String
)

internal data class NivukxTruthMetadataBlock(
    val payloadJson: String,
    val signatureBase64: String,
    val signatureAlgorithm: String,
    val metadataBytes: Int,
    val videoBitrateBps: Long,
    val records: List<NivukxTruthRecord>
) {
    val metadataBitrateRatio: Double
        get() = (metadataBytes.toDouble() * Byte.SIZE_BITS.toDouble()) /
            videoBitrateBps.coerceAtLeast(1L).toDouble()
}

internal object NivukxTruthLedger {
    fun generateSigningKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun buildRecords(tiers: List<Pair<String, Map<String, String>>>): List<NivukxTruthRecord> {
        var previousHash = "GENESIS"
        return tiers.map { (tier, payload) ->
            val canonicalPayload = canonicalMap(payload)
            val hash = sha256Hex("$tier|$previousHash|$canonicalPayload")
            NivukxTruthRecord(
                tier = tier,
                payload = payload.toSortedMap(),
                previousHash = previousHash,
                hash = hash
            ).also {
                previousHash = hash
            }
        }
    }

    fun buildPayloadJson(records: List<NivukxTruthRecord>): String =
        buildString {
            append("{\"schema\":\"nivukx-tmb-draft-12\",\"records\":[")
            records.forEachIndexed { index, record ->
                if (index > 0) append(',')
                append("{\"tier\":\"").append(escape(record.tier)).append("\",")
                append("\"previousHash\":\"").append(record.previousHash).append("\",")
                append("\"hash\":\"").append(record.hash).append("\",")
                append("\"payload\":").append(canonicalMap(record.payload)).append('}')
            }
            append("]}")
        }

    fun sign(
        payloadJson: String,
        privateKey: PrivateKey,
        videoBitrateBps: Long,
        records: List<NivukxTruthRecord>
    ): NivukxTruthMetadataBlock {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signed = Base64.getEncoder().encodeToString(signature.sign())
        return NivukxTruthMetadataBlock(
            payloadJson = payloadJson,
            signatureBase64 = signed,
            signatureAlgorithm = "Ed25519",
            metadataBytes = payloadJson.toByteArray(StandardCharsets.UTF_8).size + signed.length,
            videoBitrateBps = videoBitrateBps,
            records = records
        )
    }

    fun verify(block: NivukxTruthMetadataBlock, publicKey: PublicKey): Boolean {
        val recordsValid = block.records.fold("GENESIS") { expectedPrevious, record ->
            if (record.previousHash != expectedPrevious) return false
            val expectedHash = sha256Hex("${record.tier}|${record.previousHash}|${canonicalMap(record.payload)}")
            if (record.hash != expectedHash) return false
            record.hash
        }
        if (recordsValid.isBlank()) return false
        val expectedJson = buildPayloadJson(block.records)
        if (expectedJson != block.payloadJson) return false
        return runCatching {
            val signature = Signature.getInstance(block.signatureAlgorithm)
            signature.initVerify(publicKey)
            signature.update(block.payloadJson.toByteArray(StandardCharsets.UTF_8))
            signature.verify(Base64.getDecoder().decode(block.signatureBase64))
        }.getOrDefault(false)
    }

    internal fun canonicalMap(payload: Map<String, String>): String =
        payload.toSortedMap().entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { (key, value) ->
            "\"${escape(key)}\":${jsonValue(value)}"
        }

    private fun jsonValue(value: String): String =
        if (value == "true" || value == "false" || value.toDoubleOrNull() != null) {
            value
        } else {
            "\"${escape(value)}\""
        }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
