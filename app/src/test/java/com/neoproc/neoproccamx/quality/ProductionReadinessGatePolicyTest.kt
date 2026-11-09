package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionReadinessGatePolicyTest {

    @Test
    fun allRequiredChecksPassingProducesProductionReadyPass() {
        val report = ProductionReadinessGatePolicy.evaluate(
            checks = ProductionReadinessGatePolicy.defaultRequiredChecks().map {
                it.copy(status = ProductionGateStatus.PASS, evidence = "verified")
            }
        )

        assertEquals(ProductionGateStatus.PASS, report.overallStatus)
        assertTrue(report.productionReady)
        assertEquals(0, report.failedRequiredCount)
        assertEquals(0, report.notProvenRequiredCount)
    }

    @Test
    fun requiredFailurePreventsProductionReadyStatus() {
        val checks = ProductionReadinessGatePolicy.defaultRequiredChecks().map {
            if (it.id == "gradle_unit_build_warning_gate") {
                it.copy(status = ProductionGateStatus.FAIL, evidence = "compile error")
            } else {
                it.copy(status = ProductionGateStatus.PASS, evidence = "verified")
            }
        }

        val report = ProductionReadinessGatePolicy.evaluate(checks)

        assertEquals(ProductionGateStatus.FAIL, report.overallStatus)
        assertFalse(report.productionReady)
        assertEquals(1, report.failedRequiredCount)
        assertTrue(report.summary.contains("failedRequired=1"))
    }

    @Test
    fun requiredNotProvenPreventsProductionReadyStatusWithoutCallingItFailure() {
        val checks = ProductionReadinessGatePolicy.defaultRequiredChecks().map {
            if (it.id == "device_foreground_launch") {
                it.copy(status = ProductionGateStatus.NOT_PROVEN, evidence = "no adb device")
            } else {
                it.copy(status = ProductionGateStatus.PASS, evidence = "verified")
            }
        }

        val report = ProductionReadinessGatePolicy.evaluate(checks)

        assertEquals(ProductionGateStatus.NOT_PROVEN, report.overallStatus)
        assertFalse(report.productionReady)
        assertEquals(0, report.failedRequiredCount)
        assertEquals(1, report.notProvenRequiredCount)
        assertTrue(report.summary.contains("notProvenRequired=1"))
    }

    @Test
    fun optionalNotProvenDoesNotBlockWhenRequiredChecksPass() {
        val checks = ProductionReadinessGatePolicy.defaultRequiredChecks().map {
            it.copy(status = ProductionGateStatus.PASS, evidence = "verified")
        } + ProductionGateCheck(
            id = "optional_ffprobe_metadata",
            category = ProductionGateCategory.DEVICE_VALIDATION,
            required = false,
            status = ProductionGateStatus.NOT_PROVEN,
            evidence = "ffprobe not installed"
        )

        val report = ProductionReadinessGatePolicy.evaluate(checks)

        assertEquals(ProductionGateStatus.PASS, report.overallStatus)
        assertTrue(report.productionReady)
        assertEquals(1, report.optionalNotProvenCount)
    }

    @Test
    fun defaultRequiredChecksCoverCriticalCameraFeatures() {
        val ids = ProductionReadinessGatePolicy.defaultRequiredChecks().map { it.id }.toSet()

        assertTrue(ids.contains("gradle_unit_build_warning_gate"))
        assertTrue(ids.contains("git_diff_check"))
        assertTrue(ids.contains("debug_apk_artifact"))
        assertTrue(ids.contains("device_foreground_launch"))
        assertTrue(ids.contains("feature_preview_pipeline"))
        assertTrue(ids.contains("feature_recording_pipeline"))
        assertTrue(ids.contains("feature_hdr_ndr_nivukx"))
        assertTrue(ids.contains("feature_log_nlog"))
        assertTrue(ids.contains("feature_raw_capture"))
        assertTrue(ids.contains("feature_npv10_master"))
        assertTrue(ids.contains("feature_npv10_mov_export"))
        assertTrue(ids.contains("feature_gallery_publish"))
        assertTrue(ids.contains("feature_settings"))
        assertTrue(ids.contains("feature_mode_switching"))
        assertTrue(ids.contains("feature_color_selection"))
        assertTrue(ids.contains("runtime_gpu_processing_evidence"))
        assertTrue(ids.contains("runtime_zero_copy_evidence"))
        assertTrue(ids.contains("runtime_4k60_cpu_frame_time_evidence"))
    }

    @Test
    fun defaultRequiredChecksCoverPhaseOneStabilityGates() {
        val ids = ProductionReadinessGatePolicy.defaultRequiredChecks().map { it.id }.toSet()

        assertTrue(ids.contains("feature_mode_switch_throttle"))
        assertTrue(ids.contains("feature_camera2_session_rebuild_budget"))
        assertTrue(ids.contains("feature_gpu_recording_session_parity"))
        assertTrue(ids.contains("runtime_no_anr_logcat_evidence"))
    }
}
