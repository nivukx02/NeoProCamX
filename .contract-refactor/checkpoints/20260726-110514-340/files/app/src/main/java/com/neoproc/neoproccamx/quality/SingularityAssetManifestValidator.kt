package com.neoproc.neoproccamx.quality

internal enum class SingularityAssetManifestKind(val assetPath: String) {
    MODEL("models/model_manifest.json"),
    SHADER("shaders/shader_manifest.json"),
    LUT("luts/lut_manifest.json"),
    LOOK("looks/look_manifest.json")
}

internal data class SingularityAssetInventoryFile(
    val path: String,
    val sha256: String,
    val bytes: Long
) {
    init {
        require(path.isNotBlank()) { "inventory path is required" }
        require(bytes >= 0L) { "inventory bytes cannot be negative" }
        if (sha256.isNotBlank()) {
            require(sha256.matches(SHA_256_PATTERN)) { "sha256 must be lowercase hex" }
        }
    }

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class SingularityAssetManifestEntry(
    val assetId: String,
    val kind: SingularityAssetManifestKind,
    val file: String,
    val fileHashSha256: String,
    val stageIds: Set<Int>,
    val minimumReleaseClass: SingularityReleaseClass,
    val requiredExtensions: Set<String> = emptySet(),
    val inputContract: String = "",
    val outputContract: String = "",
    val descriptorBindings: List<String> = emptyList(),
    val pushConstantBytes: Int = 0,
    val workgroup: String = "",
    val precisionContract: String = "",
    val latencyBudgetNs: Long = 0L,
    val fallbackId: String = "",
    val qcGateIds: Set<SingularityQualityGateId> = emptySet(),
    val smfRecordKey: String = "",
    val validationAssets: List<String> = emptyList(),
    val available: Boolean = true,
    val unavailableReason: String = "",
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(assetId.matches(ASSET_ID_PATTERN)) { "assetId must be a stable identifier" }
        if (available) {
            require(file.isNotBlank()) { "available asset must declare file" }
            require(fileHashSha256.matches(SHA_256_PATTERN)) { "available asset must declare SHA-256" }
        } else {
            require(unavailableReason.isNotBlank()) { "unavailable asset requires unavailableReason" }
        }
        require(stageIds.all { it in 0 until SingularityFrameGraphRegistry.STAGE_COUNT }) {
            "stageIds must be 0..255"
        }
        require(pushConstantBytes >= 0) { "pushConstantBytes cannot be negative" }
        require(latencyBudgetNs >= 0L) { "latencyBudgetNs cannot be negative" }
    }

    companion object {
        private val ASSET_ID_PATTERN = Regex("[A-Za-z0-9_.:+-]+")
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class SingularityAssetManifest(
    val kind: SingularityAssetManifestKind,
    val formatVersion: String = FORMAT_VERSION,
    val entries: List<SingularityAssetManifestEntry>
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "Unsupported manifest formatVersion $formatVersion" }
        require(entries.all { it.kind == kind }) { "Manifest entries must match manifest kind" }
    }

    companion object {
        const val FORMAT_VERSION = "SINGULARITY_ASSET_MANIFEST_1"
    }
}

internal data class SingularityAssetManifestValidationReport(
    val valid: Boolean,
    val failures: List<String>,
    val unavailableAssetIds: List<String>,
    val availableAssetIds: List<String>
)

internal object SingularityAssetManifestValidator {
    fun validate(
        manifest: SingularityAssetManifest,
        inventory: List<SingularityAssetInventoryFile>
    ): SingularityAssetManifestValidationReport {
        val failures = mutableListOf<String>()
        val inventoryByPath = inventory.associateBy { it.path.normalizedAssetPath() }
        val duplicateIds = manifest.entries
            .groupingBy { it.assetId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            failures += "duplicate asset ids: ${duplicateIds.joinToString()}"
        }

        manifest.entries.forEach { entry ->
            if (entry.available) {
                val inventoryFile = inventoryByPath[entry.file.normalizedAssetPath()]
                if (inventoryFile == null) {
                    failures += "${entry.assetId} file is not present: ${entry.file}"
                } else {
                    if (inventoryFile.bytes <= 0L) {
                        failures += "${entry.assetId} file is empty: ${entry.file}"
                    }
                    if (!inventoryFile.sha256.equals(entry.fileHashSha256, ignoreCase = true)) {
                        failures += "${entry.assetId} SHA-256 mismatch"
                    }
                }
                failures += validateKindSpecific(entry)
            }
        }

        return SingularityAssetManifestValidationReport(
            valid = failures.isEmpty(),
            failures = failures,
            unavailableAssetIds = manifest.entries.filterNot { it.available }.map { it.assetId },
            availableAssetIds = manifest.entries.filter { it.available }.map { it.assetId }
        )
    }

    private fun validateKindSpecific(entry: SingularityAssetManifestEntry): List<String> = buildList {
        when (entry.kind) {
            SingularityAssetManifestKind.SHADER -> {
                if (entry.inputContract.isBlank()) add("${entry.assetId} shader inputContract is required")
                if (entry.outputContract.isBlank()) add("${entry.assetId} shader outputContract is required")
                if (entry.descriptorBindings.isEmpty()) add("${entry.assetId} shader descriptorBindings are required")
                if (entry.workgroup.isBlank()) add("${entry.assetId} shader workgroup is required")
                if (entry.precisionContract.isBlank()) add("${entry.assetId} shader precisionContract is required")
                if (entry.latencyBudgetNs <= 0L) add("${entry.assetId} shader latencyBudgetNs is required")
                if (entry.qcGateIds.isEmpty()) add("${entry.assetId} shader qcGateIds are required")
                if (entry.smfRecordKey.isBlank()) add("${entry.assetId} shader smfRecordKey is required")
            }
            SingularityAssetManifestKind.MODEL -> {
                MODEL_REQUIRED_METADATA_KEYS.forEach { key ->
                    if (entry.metadata[key].isNullOrBlank()) add("${entry.assetId} model metadata $key is required")
                }
            }
            SingularityAssetManifestKind.LUT -> {
                if (!entry.file.endsWith(".cube", ignoreCase = true)) {
                    add("${entry.assetId} LUT must be a .cube asset")
                }
                if (entry.metadata["identityAtZeroStrength"] != "true") {
                    add("${entry.assetId} LUT must declare identityAtZeroStrength=true")
                }
            }
            SingularityAssetManifestKind.LOOK -> {
                if (entry.metadata["creativeEffectsOptIn"] != "true") {
                    add("${entry.assetId} look must declare creativeEffectsOptIn=true")
                }
            }
        }
    }

    private fun String.normalizedAssetPath(): String =
        replace('\\', '/').trim().trimStart('/')

    private val MODEL_REQUIRED_METADATA_KEYS = listOf(
        "engine",
        "version",
        "inputTensorContract",
        "outputTensorContract",
        "expectedAccelerator",
        "actualAccelerator",
        "confidenceOutput"
    )
}
