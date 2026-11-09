package com.neoproc.neoproccamx.quality

internal enum class ProductionGateStatus {
    PASS,
    FAIL,
    NOT_PROVEN
}

internal enum class ProductionGateCategory {
    BUILD,
    SOURCE_VALIDATION,
    ARTIFACT,
    DEVICE_VALIDATION,
    FEATURE_VALIDATION
}

internal data class ProductionGateCheck(
    val id: String,
    val category: ProductionGateCategory,
    val required: Boolean,
    val status: ProductionGateStatus = ProductionGateStatus.NOT_PROVEN,
    val evidence: String = "not evaluated"
)

internal data class ProductionReadinessReport(
    val overallStatus: ProductionGateStatus,
    val productionReady: Boolean,
    val requiredCount: Int,
    val passedRequiredCount: Int,
    val failedRequiredCount: Int,
    val notProvenRequiredCount: Int,
    val optionalNotProvenCount: Int,
    val failedRequiredIds: List<String>,
    val notProvenRequiredIds: List<String>,
    val summary: String
)

internal object ProductionReadinessGatePolicy {

    fun evaluate(checks: List<ProductionGateCheck>): ProductionReadinessReport {
        val requiredChecks = checks.filter { it.required }
        val failedRequired = requiredChecks.filter { it.status == ProductionGateStatus.FAIL }
        val notProvenRequired = requiredChecks.filter { it.status == ProductionGateStatus.NOT_PROVEN }
        val passedRequired = requiredChecks.filter { it.status == ProductionGateStatus.PASS }
        val optionalNotProven = checks.filter { !it.required && it.status == ProductionGateStatus.NOT_PROVEN }
        val overall = when {
            failedRequired.isNotEmpty() -> ProductionGateStatus.FAIL
            notProvenRequired.isNotEmpty() -> ProductionGateStatus.NOT_PROVEN
            else -> ProductionGateStatus.PASS
        }
        return ProductionReadinessReport(
            overallStatus = overall,
            productionReady = overall == ProductionGateStatus.PASS,
            requiredCount = requiredChecks.size,
            passedRequiredCount = passedRequired.size,
            failedRequiredCount = failedRequired.size,
            notProvenRequiredCount = notProvenRequired.size,
            optionalNotProvenCount = optionalNotProven.size,
            failedRequiredIds = failedRequired.map { it.id },
            notProvenRequiredIds = notProvenRequired.map { it.id },
            summary = "status=$overall required=${requiredChecks.size} passedRequired=${passedRequired.size} " +
                "failedRequired=${failedRequired.size} notProvenRequired=${notProvenRequired.size} " +
                "optionalNotProven=${optionalNotProven.size}"
        )
    }

    fun defaultRequiredChecks(): List<ProductionGateCheck> =
        listOf(
            build("gradle_unit_build_warning_gate", "Gradle unit tests and debug build must pass with warning mode fail"),
            source("git_diff_check", "Source diff whitespace validation must pass"),
            artifact("debug_apk_artifact", "Debug APK must exist and contain bytes"),
            device("device_connected", "ADB device must be connected and authorized"),
            device("device_install", "APK must install successfully on the connected device"),
            device("device_foreground_launch", "MainActivity must launch and remain foreground"),
            feature("feature_preview_pipeline", "Preview pipeline has build and policy evidence"),
            feature("feature_recording_pipeline", "Recording pipeline has build and policy evidence"),
            feature("feature_hdr_ndr_nivukx", "HDR/NDR/NIVUKX transport has build and metadata evidence"),
            feature("feature_log_nlog", "Log/N-Log transport has build and metadata evidence"),
            feature("feature_raw_capture", "RAW capture path has build and truth-policy evidence"),
            feature("feature_npv10_master", "NPV10 private master path has build and storage evidence"),
            feature("feature_npv10_mov_export", "NPV10 MOV export path has build and conversion evidence"),
            feature("feature_gallery_publish", "Gallery publish path has build and MediaStore evidence"),
            feature("feature_settings", "Settings path has build and catalog evidence"),
            feature("feature_mode_switching", "Mode switching has build and policy evidence"),
            feature("feature_mode_switch_throttle", "Mode preset switching must throttle rapid input before Camera2 setters run"),
            feature("feature_camera2_session_rebuild_budget", "Camera2 session graph policy must prove rebuilds only when graph signatures change"),
            feature("feature_gpu_recording_session_parity", "GPU recording start must keep preview and encoder session parity without a bypass"),
            feature("feature_color_selection", "Manual color science and color space selection has build evidence"),
            device("runtime_gpu_processing_evidence", "Runtime certificate must prove claimed GPU processing paths"),
            device("runtime_zero_copy_evidence", "Runtime certificate must prove claimed zero-copy import paths"),
            device("runtime_4k60_cpu_frame_time_evidence", "4K60+ claims require CPU and frame-time telemetry under target"),
            device("runtime_no_anr_logcat_evidence", "Runtime logcat evidence must show mode switching and recording without ANR or fatal close")
        )

    private fun build(id: String, evidence: String): ProductionGateCheck =
        ProductionGateCheck(id, ProductionGateCategory.BUILD, required = true, evidence = evidence)

    private fun source(id: String, evidence: String): ProductionGateCheck =
        ProductionGateCheck(id, ProductionGateCategory.SOURCE_VALIDATION, required = true, evidence = evidence)

    private fun artifact(id: String, evidence: String): ProductionGateCheck =
        ProductionGateCheck(id, ProductionGateCategory.ARTIFACT, required = true, evidence = evidence)

    private fun device(id: String, evidence: String): ProductionGateCheck =
        ProductionGateCheck(id, ProductionGateCategory.DEVICE_VALIDATION, required = true, evidence = evidence)

    private fun feature(id: String, evidence: String): ProductionGateCheck =
        ProductionGateCheck(id, ProductionGateCategory.FEATURE_VALIDATION, required = true, evidence = evidence)
}
