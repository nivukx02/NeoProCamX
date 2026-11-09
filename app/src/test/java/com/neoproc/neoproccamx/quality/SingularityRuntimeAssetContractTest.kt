package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SingularityRuntimeAssetContractTest {

    @Test
    fun thresholdAssetEnumeratesAllCanonicalQualityGates() {
        val asset = projectFile("app/src/main/assets/quality/singularity_thresholds.json")
        val validation = SingularityThresholdAssetContract.validateAssetText(asset.readText())

        assertTrue(validation.failures.joinToString(), validation.valid)
        assertEquals(SingularityQualityGateId.entries.toSet(), validation.discoveredGateIds)
    }

    @Test
    fun manifestHashesMatchRealAvailableFilesAndUnavailableEntriesAreExplicit() {
        val manifestFiles = listOf(
            "app/src/main/assets/models/model_manifest.json",
            "app/src/main/assets/luts/lut_manifest.json",
            "app/src/main/assets/looks/look_manifest.json",
            "app/src/main/assets/shaders/shader_manifest.json"
        ).map(::projectFile)

        val unavailableEntries = mutableListOf<String>()
        manifestFiles.forEach { manifestFile ->
            manifestEntryPattern.findAll(manifestFile.readText()).forEach { match ->
                val assetId = match.groupValues[1]
                val file = match.groupValues[2]
                val expectedHash = match.groupValues[3]
                val available = match.groupValues[4].toBoolean()
                if (available) {
                    val actualFile = resolveManifestFile(file)
                    assertTrue("$assetId file missing: $file", actualFile.isFile)
                    assertEquals("$assetId hash mismatch", expectedHash, sha256(actualFile))
                } else {
                    unavailableEntries += assetId
                    assertTrue("$assetId unavailable entry must explain why", match.value.contains("unavailableReason"))
                }
            }
        }

        assertTrue(unavailableEntries.contains("SNNE.noise_texture_authority"))
        assertTrue(unavailableEntries.contains("SNAR.resolution_lift_authority"))
        assertTrue(unavailableEntries.contains("SNAF.autofocus_authority"))
        assertTrue(unavailableEntries.contains("multi_lens_fusion"))
        assertTrue(unavailableEntries.contains("spectral_normalize"))
    }

    @Test
    fun availableShaderEntriesBindToCommittedSpirvWithExactBinaryHashes() {
        val manifest = projectFile("app/src/main/assets/shaders/shader_manifest.json").readText()
        val entries = shaderManifestEntryPattern.findAll(manifest).toList()

        assertTrue("shader manifest entries were not parsed", entries.isNotEmpty())
        assertFalse(
            "legacy cinema_debayer reference must not be advertised as a shipped runtime shader",
            manifest.contains("app/src/main/cpp/shaders/vulkan/cinema_debayer.comp")
        )
        entries.forEach { match ->
            val assetId = match.groupValues[1]
            val sourceFile = match.groupValues[2]
            val binaryHash = match.groupValues[3]
            val available = match.groupValues[4].toBoolean()
            if (available) {
                assertTrue(
                    "$assetId must declare an exact committed SPIR-V SHA-256",
                    binaryHash.matches(Regex("[0-9a-f]{64}"))
                )
                val binary = projectFile(
                    "app/src/main/cpp/generated/${File(sourceFile).name}.spv"
                )
                assertTrue("$assetId committed SPIR-V missing: ${binary.path}", binary.isFile)
                assertEquals("$assetId SPIR-V hash mismatch", binaryHash, sha256(binary))
            } else {
                assertTrue("$assetId unavailable shader must not claim a binary", binaryHash.isBlank())
            }
        }
    }

    @Test
    fun objectValidatorRejectsMissingAvailableAssetsAndKeepsUnavailableEntriesHonest() {
        val manifest = SingularityAssetManifest(
            kind = SingularityAssetManifestKind.MODEL,
            entries = listOf(
                SingularityAssetManifestEntry(
                    assetId = "test_model",
                    kind = SingularityAssetManifestKind.MODEL,
                    file = "models/test.tflite",
                    fileHashSha256 = "0".repeat(64),
                    stageIds = setOf(144),
                    minimumReleaseClass = SingularityReleaseClass.SINGULARITY_50,
                    metadata = mapOf(
                        "engine" to "TensorFlowLite",
                        "version" to "test",
                        "inputTensorContract" to "input",
                        "outputTensorContract" to "output",
                        "expectedAccelerator" to "GPU",
                        "actualAccelerator" to "GPU",
                        "confidenceOutput" to "score"
                    )
                ),
                SingularityAssetManifestEntry(
                    assetId = "missing_future_model",
                    kind = SingularityAssetManifestKind.MODEL,
                    file = "",
                    fileHashSha256 = "",
                    stageIds = setOf(181),
                    minimumReleaseClass = SingularityReleaseClass.SINGULARITY_75,
                    available = false,
                    unavailableReason = "not shipped"
                )
            )
        )

        val report = SingularityAssetManifestValidator.validate(manifest, inventory = emptyList())

        assertTrue(report.failures.any { it.contains("test_model file is not present") })
        assertTrue(report.unavailableAssetIds.contains("missing_future_model"))
    }

    private fun projectFile(relativePath: String): File =
        listOf(File(relativePath), File("../$relativePath")).first { it.exists() }

    private fun resolveManifestFile(file: String): File =
        if (file.startsWith("app/")) projectFile(file) else projectFile("app/src/main/assets/$file")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private val manifestEntryPattern = Regex(
        "\\{[^{}]*\"assetId\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"file\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?" +
            "\"fileHashSha256\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?\"available\"\\s*:\\s*(true|false)[\\s\\S]*?\\}"
    )

    private val shaderManifestEntryPattern = Regex(
        "\\{[^{}]*\"assetId\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"file\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?" +
            "\"fileHashSha256\"\\s*:\\s*\"[^\"]*\"[\\s\\S]*?\"binaryHash\"\\s*:\\s*\"([^\"]*)\"[\\s\\S]*?" +
            "\"available\"\\s*:\\s*(true|false)[\\s\\S]*?\\}"
    )
}
