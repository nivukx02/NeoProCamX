package com.neoproc.neoproccamx.camera

internal enum class BypassCapabilityScore(val label: String) {
    TRUE_ORIGINAL_RAW("TRUE ORIGINAL RAW"),
    SENSOR_RAW_LIMITED_ISP("SENSOR RAW LIMITED ISP"),
    PARTIAL_VIDEO_BYPASS("PARTIAL VIDEO ISP-OFF"),
    VENDOR_REQUIRED("VENDOR REQUIRED")
}

internal data class CameraBypassCapabilityReport(
    val raw: BypassCapabilityScore,
    val video: BypassCapabilityScore,
    val fullSensor: BypassCapabilityScore,
    val halProfile: CameraHalProfile,
    val ispOffApplied: Boolean,
    val ispOffCapability: String,
    val displayLabel: String,
    val details: List<String>
) {
    fun toLogLine(): String =
        "ispOffApplied=$ispOffApplied ispOffCapability=$ispOffCapability " +
            "rawIspOff=${raw.label} videoIspOff=${video.label} sensorReadout=${fullSensor.label} " +
            "hal=${halProfile.toLogLine()} details=${details.joinToString("; ")}"
}

internal object CameraBypassCapabilityScorePolicy {
    fun resolve(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        vendorScanReport: CameraVendorReadoutScanReport = CameraVendorReadoutScanReport.EMPTY
    ): CameraBypassCapabilityReport {
        val truthful = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability,
            requestPlan = requestPlan
        )
        val halProfile = CameraHalProfilePolicy.resolve(
            capability = capability,
            requestPlan = requestPlan,
            vendorScanReport = vendorScanReport
        )

        val rawMax = largest(capability.rawOutputSizes)
        val yuvMax = largest(capability.yuvOutputSizes)
        val encoderMax = largest(capability.encoderOutputSizes)
        val hasVideoStream = yuvMax != null || encoderMax != null
        val largestExposed = largest(
            capability.rawOutputSizes + capability.yuvOutputSizes + capability.encoderOutputSizes
        )
        val details = buildList {
            add(
                if (rawMax != null) {
                    "RAW_SENSOR ${rawMax.label()} exposed"
                } else {
                    "RAW_SENSOR not exposed by Camera2 HAL"
                }
            )
            add(
                if (hasVideoStream) {
                    "Video path uses max YUV/encoder readout with ISP-off keys ${requestPlan.supportedFeatureCount}/${requestPlan.totalFeatureCount}"
                } else {
                    "Video path has no safe YUV or encoder stream"
                }
            )
            val pixelArray = capability.pixelArraySize
            if (pixelArray != null && largestExposed != null && largestExposed.area < pixelArray.area) {
                add("Full sensor ${pixelArray.label()} requires vendor HAL because largest safe stream is ${largestExposed.label()}")
            } else if (pixelArray != null && largestExposed != null) {
                add("Full sensor ${pixelArray.label()} is covered by exposed stream ${largestExposed.label()}")
            }
            if (vendorScanReport.gatedCandidates.isNotEmpty()) {
                val gatedSizes = vendorScanReport.gatedCandidates
                    .map { it.size.label() }
                    .distinct()
                    .joinToString()
                add("Vendor/private candidates gated until public HAL stream map exposes them safely: $gatedSizes")
            }
            if (!requestPlan.complete && requestPlan.unsupportedReasons.isNotEmpty()) {
                add("Incomplete ISP-off coverage: ${requestPlan.unsupportedReasons.joinToString("; ")}")
            }
            yuvMax?.let { add("Max YUV sidecar ${it.label()}") }
            encoderMax?.let { add("Max encoder delivery ${it.label()}") }
            addAll(truthful.details)
        }

        return CameraBypassCapabilityReport(
            raw = truthful.raw,
            video = truthful.video,
            fullSensor = truthful.fullSensor,
            halProfile = halProfile,
            ispOffApplied = requestPlan.supportedFeatureCount > 0,
            ispOffCapability = if (requestPlan.supportedFeatureCount > 0) {
                truthful.video.label.toCapabilityToken()
            } else {
                "NONE"
            },
            displayLabel = "HAL ${halProfile.badgeLabel}: RAW ${truthful.raw.label} | VIDEO ${truthful.video.label} | FULL SENSOR ${truthful.fullSensor.label}",
            details = details
        )
    }

    private fun largest(sizes: List<CameraStreamSize>): CameraStreamSize? =
        sizes.maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun CameraStreamSize.label(): String = "${width}x${height}"

    private fun String.toCapabilityToken(): String =
        uppercase()
            .replace("-", "_")
            .replace(Regex("\\s+"), "_")
}
