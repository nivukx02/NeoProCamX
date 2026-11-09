package com.neoproc.neoproccamx.camera

internal data class SubjectMeteringUpdate(
    val focusRegion: CameraRect?,
    val exposureRegion: CameraRect?,
    val exposureRegionUpdated: Boolean
)

internal object CameraSubjectMeteringPolicy {
    fun resolve(
        focusRegion: CameraRect?,
        exposureRegion: CameraRect?,
        currentExposureRegion: CameraRect?,
        supportsManualSensor: Boolean,
        manualIso: Int,
        manualExposureNs: Long,
        lastExposureUpdateElapsedMs: Long,
        nowElapsedMs: Long
    ): SubjectMeteringUpdate {
        if (exposureRegion == null) {
            return SubjectMeteringUpdate(
                focusRegion = focusRegion,
                exposureRegion = null,
                exposureRegionUpdated = currentExposureRegion != null
            )
        }

        val shouldRefreshExposure = CameraControlMath.shouldRefreshExposureRegion(
            supportsManualSensor = supportsManualSensor,
            manualIso = manualIso,
            manualExposureNs = manualExposureNs,
            currentRegion = currentExposureRegion,
            lastUpdateElapsedMs = lastExposureUpdateElapsedMs,
            nowElapsedMs = nowElapsedMs,
            candidate = exposureRegion
        )
        val resolvedExposureRegion = if (shouldRefreshExposure) {
            exposureRegion
        } else {
            currentExposureRegion ?: exposureRegion
        }

        return SubjectMeteringUpdate(
            focusRegion = focusRegion,
            exposureRegion = resolvedExposureRegion,
            exposureRegionUpdated = resolvedExposureRegion != currentExposureRegion
        )
    }
}
