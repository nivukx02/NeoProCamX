package com.neoproc.neoproccamx.camera

internal enum class CameraVendorReadoutCandidateStatus {
    SAFE_EXPOSED,
    GATED_PRIVATE
}

internal data class CameraVendorReadoutCandidate(
    val keyName: String,
    val size: CameraStreamSize,
    val status: CameraVendorReadoutCandidateStatus,
    val reason: String
)

internal data class CameraVendorReadoutScanReport(
    val scannedKeyCount: Int,
    val candidates: List<CameraVendorReadoutCandidate>
) {
    val safeCandidates: List<CameraVendorReadoutCandidate> =
        candidates.filter { it.status == CameraVendorReadoutCandidateStatus.SAFE_EXPOSED }
    val gatedCandidates: List<CameraVendorReadoutCandidate> =
        candidates.filter { it.status == CameraVendorReadoutCandidateStatus.GATED_PRIVATE }

    fun toLogLine(): String =
        "keys=$scannedKeyCount safe=${safeCandidates.size} gated=${gatedCandidates.size} " +
            "safeSizes=${safeCandidates.map { it.size.compactLabel() }.distinct().joinToString().ifBlank { "none" }} " +
            "gatedSizes=${gatedCandidates.map { it.size.compactLabel() }.distinct().joinToString().ifBlank { "none" }}"

    companion object {
        val EMPTY = CameraVendorReadoutScanReport(scannedKeyCount = 0, candidates = emptyList())
    }
}

internal object CameraVendorStreamCandidatePolicy {
    fun resolve(
        vendorSizesByKey: Map<String, List<CameraStreamSize>>,
        safeExposedSizes: Set<CameraStreamSize>
    ): CameraVendorReadoutScanReport {
        val candidates = vendorSizesByKey
            .flatMap { (keyName, sizes) ->
                sizes.distinct().map { size ->
                    val safe = safeExposedSizes.contains(size)
                    CameraVendorReadoutCandidate(
                        keyName = keyName,
                        size = size,
                        status = if (safe) {
                            CameraVendorReadoutCandidateStatus.SAFE_EXPOSED
                        } else {
                            CameraVendorReadoutCandidateStatus.GATED_PRIVATE
                        },
                        reason = if (safe) {
                            "vendor reported and HAL stream map exposes exact safe output"
                        } else {
                            "vendor reported but HAL stream map did not expose safe output"
                        }
                    )
                }
            }
            .distinctBy { "${it.keyName}:${it.size.width}x${it.size.height}" }
            .sortedWith(
                compareByDescending<CameraVendorReadoutCandidate> { it.size.area }
                    .thenBy { it.keyName }
            )

        return CameraVendorReadoutScanReport(
            scannedKeyCount = vendorSizesByKey.size,
            candidates = candidates
        )
    }
}

private fun CameraStreamSize.compactLabel(): String = "${width}x${height}"
