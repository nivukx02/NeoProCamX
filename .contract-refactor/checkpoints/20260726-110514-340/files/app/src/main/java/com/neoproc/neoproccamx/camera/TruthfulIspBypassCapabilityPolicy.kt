package com.neoproc.neoproccamx.camera

internal data class TruthfulIspBypassCapabilityResult(
    val raw: BypassCapabilityScore,
    val video: BypassCapabilityScore,
    val fullSensor: BypassCapabilityScore,
    val details: List<String>
)

internal object TruthfulIspBypassCapabilityPolicy {
    private const val SENSOR_COVERAGE_TOLERANCE = 0.98

    fun resolve(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan
    ): TruthfulIspBypassCapabilityResult {
        val referenceSensor = capability.activeArraySize ?: capability.pixelArraySize
        val rawMax = largest(capability.rawOutputSizes)
        val yuvMax = largest(capability.yuvOutputSizes)
        val encoderMax = largest(capability.encoderOutputSizes)
        val largestVideo = largest(capability.yuvOutputSizes + capability.encoderOutputSizes)

        val hasRawStream = capability.supportsRaw && rawMax != null
        val rawCoversSensor = rawMax != null &&
            referenceSensor != null &&
            coversSensor(rawMax, referenceSensor)
        val videoCoversSensor = largestVideo != null &&
            referenceSensor != null &&
            coversSensor(largestVideo, referenceSensor)
        val hasVideoStream = yuvMax != null || encoderMax != null
        val hasAnyIspOffKey = requestPlan.supportedFeatureCount > 0
        val hasTrueOriginalRaw = hasRawStream &&
            rawCoversSensor &&
            capability.supportsManualSensor &&
            requestPlan.complete

        val rawScore = when {
            hasTrueOriginalRaw -> BypassCapabilityScore.TRUE_ORIGINAL_RAW
            hasRawStream -> BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP
            else -> BypassCapabilityScore.VENDOR_REQUIRED
        }
        val videoScore = if (hasVideoStream && hasAnyIspOffKey) {
            BypassCapabilityScore.PARTIAL_VIDEO_BYPASS
        } else {
            BypassCapabilityScore.VENDOR_REQUIRED
        }
        val fullSensorScore = when {
            hasTrueOriginalRaw -> BypassCapabilityScore.TRUE_ORIGINAL_RAW
            rawCoversSensor && hasRawStream -> BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP
            videoCoversSensor && videoScore == BypassCapabilityScore.PARTIAL_VIDEO_BYPASS ->
                BypassCapabilityScore.PARTIAL_VIDEO_BYPASS
            else -> BypassCapabilityScore.VENDOR_REQUIRED
        }

        return TruthfulIspBypassCapabilityResult(
            raw = rawScore,
            video = videoScore,
            fullSensor = fullSensorScore,
            details = buildDetails(
                capability = capability,
                requestPlan = requestPlan,
                referenceSensor = referenceSensor,
                rawMax = rawMax,
                yuvMax = yuvMax,
                encoderMax = encoderMax,
                rawCoversSensor = rawCoversSensor,
                videoCoversSensor = videoCoversSensor,
                hasTrueOriginalRaw = hasTrueOriginalRaw
            )
        )
    }

    private fun buildDetails(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        referenceSensor: CameraStreamSize?,
        rawMax: CameraStreamSize?,
        yuvMax: CameraStreamSize?,
        encoderMax: CameraStreamSize?,
        rawCoversSensor: Boolean,
        videoCoversSensor: Boolean,
        hasTrueOriginalRaw: Boolean
    ): List<String> =
        buildList {
            when {
                hasTrueOriginalRaw && rawMax != null ->
                    add("RAW_SENSOR ${rawMax.label()} is a true original RAW_SENSOR path")
                rawMax != null && referenceSensor != null && !rawCoversSensor ->
                    add("RAW_SENSOR ${rawMax.label()} is below active sensor ${referenceSensor.label()}")
                rawMax != null && !capability.supportsManualSensor ->
                    add("RAW_SENSOR covers full sensor but manual sensor control is unavailable")
                rawMax != null && !requestPlan.complete ->
                    add("RAW_SENSOR covers full sensor but ISP bypass is incomplete: ${requestPlan.unsupportedReasons.joinToString("; ")}")
                rawMax != null ->
                    add("RAW_SENSOR ${rawMax.label()} is exposed but original bypass truth could not be fully proven")
                else ->
                    add("RAW_SENSOR original path is not exposed by Camera2 HAL")
            }

            if (videoCoversSensor) {
                add("YUV/encoder path covers active sensor size but remains best-effort ISP-bypassed video, not true RAW")
            } else {
                val maxVideo = largest(listOfNotNull(yuvMax, encoderMax))
                if (referenceSensor != null && maxVideo != null) {
                    add("YUV/encoder ${maxVideo.label()} is below active sensor ${referenceSensor.label()}")
                }
            }
        }

    private fun coversSensor(
        actual: CameraStreamSize,
        referenceSensor: CameraStreamSize
    ): Boolean =
        actual.area.toDouble() >= referenceSensor.area.toDouble() * SENSOR_COVERAGE_TOLERANCE

    private fun largest(sizes: List<CameraStreamSize>): CameraStreamSize? =
        sizes.maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun CameraStreamSize.label(): String = "${width}x${height}"
}
