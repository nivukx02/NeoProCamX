package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionConfigurationFingerprintTest {
    private val omittedRoute = CinemaStreamRoute(
        requirement = CinemaStreamRequirement.OMITTED,
        size = null,
        createReader = false,
        includeInSession = false,
        reason = "not requested"
    )

    private val streamGraph = CinemaStreamGraphPlan(
        mode = CinemaStreamGraphMode.VIDEO_PREVIEW_READY,
        rawSensor = omittedRoute,
        yuvAnalysis = omittedRoute,
        jpegStill = omittedRoute,
        encoder = omittedRoute,
        hardwareImport = omittedRoute,
        fallbackDropOrder = emptyList(),
        diagnostics = emptyList()
    )

    private val fpsPlan = CameraStreamBasedFpsOverridePlan.disabled(
        appliedFps = 25,
        publicAeRange = CameraIntRange(25, 25),
        reason = "fixed public range"
    )

    private fun fingerprint(
        surface: Any,
        generationId: Int = 7,
        graph: CinemaStreamGraphPlan = streamGraph,
        outputs: List<CameraSessionOutputFingerprint>? = null
    ) = CameraSessionConfigurationFingerprint(
        cameraId = "0",
        outputs = outputs ?: listOf(
            CameraSessionOutputFingerprint(
                identity = CameraSessionSurfaceIdentity(surface),
                generationId = generationId,
                kind = CameraSessionSurfaceKind.PREVIEW,
                dynamicRangeProfile = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                streamUseCaseRole = StreamUseCasePolicy.StreamRole.PREVIEW,
                streamUseCase = 1L
            )
        ),
        readers = emptyList(),
        streamGraph = graph,
        streamBasedFpsPlan = fpsPlan,
        maximumResolutionPixelMode = false,
        maximumResolutionSessionParameterApplied = false
    )

    @Test
    fun `same surface reference and configuration reuse the live session`() {
        val surface = Any()
        val configured = fingerprint(surface)
        val desired = fingerprint(surface)

        assertTrue(
            CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(
                hasActiveSession = true,
                configured = configured,
                desired = desired,
                allowIdenticalRecoveryRebuild = false
            )
        )
    }

    @Test
    fun `different buffer queue or surface generation requires a new session`() {
        val surface = Any()

        assertNotEquals(fingerprint(surface), fingerprint(Any()))
        assertNotEquals(fingerprint(surface, generationId = 7), fingerprint(surface, generationId = 8))
    }

    @Test
    fun `health recovery can rebuild an otherwise identical configuration`() {
        val surface = Any()
        val configured = fingerprint(surface)

        assertFalse(
            CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(
                hasActiveSession = true,
                configured = configured,
                desired = configured,
                allowIdenticalRecoveryRebuild = true
            )
        )
    }

    @Test
    fun `logical graph diagnostics do not rebuild an identical Camera2 topology`() {
        val surface = Any()
        val diagnosticOnlyGraph = streamGraph.copy(
            mode = CinemaStreamGraphMode.PREVIEW_STILL_READY,
            diagnostics = listOf("logical mode changed; physical outputs did not")
        )

        assertTrue(
            CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(
                hasActiveSession = true,
                configured = fingerprint(surface),
                desired = fingerprint(surface, graph = diagnosticOnlyGraph),
                allowIdenticalRecoveryRebuild = false
            )
        )
    }

    @Test
    fun `output order does not rebuild an identical configured Surface set`() {
        val firstSurface = Any()
        val secondSurface = Any()
        fun output(surface: Any, profile: Long?) = CameraSessionOutputFingerprint(
            identity = CameraSessionSurfaceIdentity(surface),
            generationId = 7,
            kind = CameraSessionSurfaceKind.PREVIEW,
            dynamicRangeProfile = profile,
            streamUseCaseRole = StreamUseCasePolicy.StreamRole.PREVIEW,
            streamUseCase = 1L
        )
        val first = output(firstSurface, null)
        val second = output(secondSurface, HdrDynamicRangePolicy.PROFILE_HDR10_PLUS)

        assertTrue(
            CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(
                hasActiveSession = true,
                configured = fingerprint(firstSurface, outputs = listOf(first, second)),
                desired = fingerprint(firstSurface, outputs = listOf(second, first)),
                allowIdenticalRecoveryRebuild = false
            )
        )
    }
}
