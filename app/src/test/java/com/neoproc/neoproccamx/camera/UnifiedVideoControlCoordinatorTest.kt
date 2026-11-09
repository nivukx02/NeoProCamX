package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UnifiedVideoControlCoordinatorTest {

    @Test
    fun `runtime mode mapping keeps RAW and NDR authority ahead of presentation labels`() {
        assertEquals(
            UnifiedVideoControlMode.RAW,
            UnifiedVideoControlMode.fromRuntime(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "NDR10+",
                rawSensorActive = true,
                professionalControlsActive = false
            )
        )
        assertEquals(
            UnifiedVideoControlMode.NDR,
            UnifiedVideoControlMode.fromRuntime(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "NDR10+",
                rawSensorActive = false,
                professionalControlsActive = true
            )
        )
        assertEquals(
            UnifiedVideoControlMode.LOG,
            UnifiedVideoControlMode.fromRuntime(
                cinemaMode = CinemaVideoMode.LOG,
                hdrMode = "OFF",
                rawSensorActive = false,
                professionalControlsActive = false
            )
        )
        assertEquals(
            UnifiedVideoControlMode.PRO,
            UnifiedVideoControlMode.fromRuntime(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "OFF",
                rawSensorActive = false,
                professionalControlsActive = true
            )
        )
        assertEquals(
            UnifiedVideoControlMode.VIDEO,
            UnifiedVideoControlMode.fromRuntime(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "  off  ",
                rawSensorActive = false,
                professionalControlsActive = false
            )
        )
    }

    @Test
    fun `all mode and detail combinations emit one bounded automatic authority per control`() {
        UnifiedVideoControlMode.entries.forEach { mode ->
            VideoDetailProfile.entries.forEach { profile ->
                val decision = resolve(
                    mode = mode,
                    profile = profile,
                    recording = true,
                    automatic = automaticSignals(
                        faceExposureBiasStops = 0.8f,
                        faceExposureConfidence = 0.95f,
                        measuredWhiteBalanceConfidence = 0.95f,
                        autoWhiteBalanceConverged = true,
                        aiFocusDistanceDiopters = 4f,
                        aiFocusConfidence = 0.95f
                    )
                )

                assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
                assertNull(decision.exposure.sensorSensitivityIso)
                assertNull(decision.exposure.exposureTimeNs)
                assertTrue(decision.exposure.exposureCompensationIndex in -6..6)
                assertEquals(UnifiedWhiteBalanceAuthority.PLATFORM_AUTO, decision.whiteBalance.authority)
                assertTrue(decision.whiteBalance.requestAutoLock)
                assertEquals(5_600, decision.whiteBalance.stableFallbackKelvin)
                assertEquals(UnifiedFocusAuthority.AI_LENS_DISTANCE, decision.focus.authority)
                assertTrue(requireNotNull(decision.focus.lensFocusDistanceDiopters) in 0f..10f)
                assertTrue(decision.rejections.isEmpty())
            }
        }
    }

    @Test
    fun `manual WB sensor and focus authority is invariant across every mode and detail profile`() {
        UnifiedVideoControlMode.entries.forEach { mode ->
            VideoDetailProfile.entries.forEach { profile ->
                val decision = resolve(
                    mode = mode,
                    profile = profile,
                    recording = true,
                    frameExposureBudgetNs = 33_333_333L,
                    manual = UnifiedVideoManualControls(
                        iso = 99_999,
                        exposureTimeNs = 100_000_000L,
                        exposureCompensationIndex = 3,
                        exposureCompensationOverrideActive = true,
                        whiteBalanceKelvin = 15_000,
                        whiteBalanceTint = 500,
                        focusDistanceDiopters = 99f,
                        focusOverrideActive = true
                    ),
                    automatic = automaticSignals(
                        faceExposureBiasStops = 2f,
                        faceExposureConfidence = 1f,
                        highlightProtectionBiasStops = -1f,
                        sensorLinearNdrBiasStops = -1f,
                        aiFocusDistanceDiopters = 1f,
                        aiFocusConfidence = 1f
                    )
                )

                assertEquals(UnifiedExposureAuthority.USER_MANUAL_SENSOR, decision.exposure.authority)
                assertEquals(3_200, decision.exposure.sensorSensitivityIso)
                assertEquals(33_333_333L, decision.exposure.exposureTimeNs)
                assertEquals(0, decision.exposure.exposureCompensationIndex)
                assertEquals(0f, decision.exposure.adaptiveBiasStops, 0f)
                assertEquals(0f, decision.exposure.appliedManualEvStops, 0f)
                assertTrue(decision.exposure.userIsoPreserved)
                assertTrue(decision.exposure.userExposureTimePreserved)
                assertEquals(UnifiedWhiteBalanceAuthority.USER_FIXED, decision.whiteBalance.authority)
                assertEquals(10_000, decision.whiteBalance.fixedKelvin)
                assertEquals(150, decision.whiteBalance.fixedTint)
                assertFalse(decision.whiteBalance.requestAutoLock)
                assertEquals(UnifiedFocusAuthority.USER_MANUAL, decision.focus.authority)
                assertEquals(10f, decision.focus.lensFocusDistanceDiopters ?: -1f, 0f)
                assertTrue(
                    UnifiedVideoControlRejection.FULL_MANUAL_EXPOSURE_IGNORES_EV in
                        decision.rejections
                )
            }
        }
    }

    @Test
    fun `software ISO priority preserves manual ISO while EV is realized on its metered shutter`() {
        val decision = resolve(
            targetFps = 24,
            limits = limits().copy(supportsSoftwareIsoPriority = true),
            manual = UnifiedVideoManualControls(
                iso = 400,
                exposureCompensationIndex = 2,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals(meteredExposureTimeNs = 10_000_000L).copy(
                softwareIsoPriorityExposureTimeNs = 20_000_000L
            ),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(UnifiedExposureAuthority.USER_SOFTWARE_ISO_PRIORITY, decision.exposure.authority)
        assertEquals(400, decision.exposure.sensorSensitivityIso)
        assertEquals(20_000_000L, decision.exposure.exposureTimeNs)
        assertTrue(decision.exposure.userIsoPreserved)
        assertEquals(1f, decision.exposure.appliedManualEvStops, 0.001f)
    }

    @Test
    fun `invalid software ISO priority evidence cannot be clamped into manual authority`() {
        val decision = resolve(
            targetFps = 24,
            limits = limits().copy(supportsSoftwareIsoPriority = true),
            manual = UnifiedVideoManualControls(iso = 400),
            automatic = automaticSignals().copy(
                softwareIsoPriorityExposureTimeNs = Long.MAX_VALUE
            ),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
        assertNull(decision.exposure.exposureTimeNs)
        assertTrue(
            UnifiedVideoControlRejection.INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE in
                decision.rejections
        )
    }

    @Test
    fun `manual ISO with no adaptive shutter authority fails closed to platform AE`() {
        val decision = resolve(
            targetFps = 24,
            manual = UnifiedVideoManualControls(iso = 400),
            automatic = automaticSignals(meteredExposureTimeNs = null),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
        assertNull(decision.exposure.sensorSensitivityIso)
        assertNull(decision.exposure.exposureTimeNs)
        assertTrue(
            UnifiedVideoControlRejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in
                decision.rejections
        )
    }

    @Test
    fun `software shutter priority preserves manual shutter while EV is realized on its metered ISO`() {
        val decision = resolve(
            limits = limits().copy(supportsSoftwareShutterPriority = true),
            manual = UnifiedVideoManualControls(
                exposureTimeNs = 10_000_000L,
                exposureCompensationIndex = 2,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals(meteredIso = 400).copy(
                softwareShutterPriorityIso = 800
            )
        )

        assertEquals(UnifiedExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY, decision.exposure.authority)
        assertEquals(800, decision.exposure.sensorSensitivityIso)
        assertEquals(10_000_000L, decision.exposure.exposureTimeNs)
        assertTrue(decision.exposure.userExposureTimePreserved)
        assertEquals(1f, decision.exposure.appliedManualEvStops, 0.001f)
    }

    @Test
    fun `invalid software shutter priority evidence cannot be clamped into manual authority`() {
        val decision = resolve(
            limits = limits().copy(supportsSoftwareShutterPriority = true),
            manual = UnifiedVideoManualControls(exposureTimeNs = 10_000_000L),
            automatic = automaticSignals().copy(softwareShutterPriorityIso = Int.MAX_VALUE)
        )

        assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
        assertNull(decision.exposure.sensorSensitivityIso)
        assertTrue(
            UnifiedVideoControlRejection.INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE in
                decision.rejections
        )
    }

    @Test
    fun `automatic video stays under live platform AE and does not freeze a metered frame`() {
        val decision = resolve(
            automatic = automaticSignals(
                meteredIso = 1_250,
                meteredExposureTimeNs = 31_000_000L
            )
        )

        assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
        assertNull(decision.exposure.sensorSensitivityIso)
        assertNull(decision.exposure.exposureTimeNs)
    }

    @Test
    fun `complete NPV10 cadence pair owns automatic exposure without changing frame quality`() {
        val decision = resolve(
            targetFps = 30,
            frameExposureBudgetNs = 33_333_333L,
            automatic = automaticSignals().copy(
                cadenceGuardExposureTimeNs = 25_000_000L,
                cadenceGuardSensorSensitivityIso = 3_840
            ),
            limits = limits().copy(isoRange = CameraIntRange(50, 102_400))
        )

        assertEquals(
            UnifiedExposureAuthority.SYSTEM_NPV10_CADENCE_GUARD,
            decision.exposure.authority
        )
        assertEquals(3_840, decision.exposure.sensorSensitivityIso)
        assertEquals(25_000_000L, decision.exposure.exposureTimeNs)
        assertEquals(33_333_333L, decision.exposure.frameExposureBudgetNs)
        assertTrue(decision.exposure.userIsoPreserved)
        assertTrue(decision.exposure.userExposureTimePreserved)
    }

    @Test
    fun `operator manual exposure takes precedence over an available cadence pair`() {
        val decision = resolve(
            frameExposureBudgetNs = 33_333_333L,
            manual = UnifiedVideoManualControls(
                iso = 800,
                exposureTimeNs = 10_000_000L
            ),
            automatic = automaticSignals().copy(
                cadenceGuardExposureTimeNs = 25_000_000L,
                cadenceGuardSensorSensitivityIso = 3_840
            ),
            limits = limits().copy(isoRange = CameraIntRange(50, 102_400))
        )

        assertEquals(UnifiedExposureAuthority.USER_MANUAL_SENSOR, decision.exposure.authority)
        assertEquals(800, decision.exposure.sensorSensitivityIso)
        assertEquals(10_000_000L, decision.exposure.exposureTimeNs)
    }

    @Test
    fun `explicit AE compensation is immediate and excludes competing adaptive writers`() {
        val decision = resolve(
            manual = UnifiedVideoManualControls(
                exposureCompensationIndex = 4,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals(
                faceExposureBiasStops = 2f,
                faceExposureConfidence = 1f,
                highlightProtectionBiasStops = -2f,
                previousExposureCompensationIndex = -4
            )
        )

        assertEquals(4, decision.exposure.exposureCompensationIndex)
        assertEquals(0f, decision.exposure.adaptiveBiasStops, 0f)
    }

    @Test
    fun `highlight protection owns exposure when it conflicts with face lift`() {
        val decision = resolve(
            mode = UnifiedVideoControlMode.VIDEO,
            automatic = automaticSignals(
                faceExposureBiasStops = 1f,
                faceExposureConfidence = 1f,
                highlightProtectionBiasStops = -0.75f
            )
        )

        assertEquals(-0.75f, decision.exposure.adaptiveBiasStops, 0f)
        assertTrue(decision.exposure.exposureCompensationIndex < 0)
    }

    @Test
    fun `NDR and RAW never add positive face exposure over sensor linear authority`() {
        listOf(UnifiedVideoControlMode.NDR, UnifiedVideoControlMode.RAW).forEach { mode ->
            val neutral = resolve(
                mode = mode,
                automatic = automaticSignals(
                    faceExposureBiasStops = 2f,
                    faceExposureConfidence = 1f,
                    sensorLinearNdrBiasStops = 0.5f
                )
            )
            assertEquals(0f, neutral.exposure.adaptiveBiasStops, 0f)

            val protected = resolve(
                mode = mode,
                automatic = automaticSignals(
                    faceExposureBiasStops = 2f,
                    faceExposureConfidence = 1f,
                    sensorLinearNdrBiasStops = -0.6f
                )
            )
            assertEquals(-0.6f, protected.exposure.adaptiveBiasStops, 0f)
        }
    }

    @Test
    fun `detail profiles bound automatic EV slew without changing its target`() {
        val natural = resolve(
            profile = VideoDetailProfile.NATURAL_DETAIL,
            automatic = automaticSignals(
                highlightProtectionBiasStops = -1f,
                previousExposureCompensationIndex = 5
            )
        )
        val focused = resolve(
            profile = VideoDetailProfile.FOCUSED_DETAIL,
            automatic = automaticSignals(
                highlightProtectionBiasStops = -1f,
                previousExposureCompensationIndex = 5
            )
        )

        assertEquals(-1f, natural.exposure.adaptiveBiasStops, 0f)
        assertEquals(-1f, focused.exposure.adaptiveBiasStops, 0f)
        assertEquals(4, natural.exposure.exposureCompensationIndex)
        assertEquals(3, focused.exposure.exposureCompensationIndex)
    }

    @Test
    fun `manual white balance clamps to sensor and tint limits`() {
        val decision = resolve(
            manual = UnifiedVideoManualControls(
                whiteBalanceKelvin = 1_000,
                whiteBalanceTint = -500
            )
        )

        assertEquals(UnifiedWhiteBalanceAuthority.USER_FIXED, decision.whiteBalance.authority)
        assertEquals(2_000, decision.whiteBalance.fixedKelvin)
        assertEquals(-150, decision.whiteBalance.fixedTint)
        assertFalse(decision.whiteBalance.requestAutoLock)
    }

    @Test
    fun `unsupported manual controls fail to safe automatic authorities without AI takeover`() {
        val decision = resolve(
            limits = limits().copy(
                supportsManualSensor = false,
                supportsFixedWhiteBalance = false,
                supportsManualFocusDistance = false
            ),
            manual = UnifiedVideoManualControls(
                iso = 400,
                whiteBalanceKelvin = 4_300,
                focusDistanceDiopters = 2f,
                focusOverrideActive = true
            ),
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 4f,
                aiFocusConfidence = 1f
            )
        )

        assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
        assertEquals(UnifiedWhiteBalanceAuthority.PLATFORM_AUTO, decision.whiteBalance.authority)
        assertEquals(UnifiedFocusAuthority.CONTINUOUS_AUTO, decision.focus.authority)
        assertTrue(UnifiedVideoControlRejection.MANUAL_SENSOR_UNSUPPORTED in decision.rejections)
        assertTrue(UnifiedVideoControlRejection.FIXED_WHITE_BALANCE_UNSUPPORTED in decision.rejections)
        assertTrue(UnifiedVideoControlRejection.MANUAL_FOCUS_UNSUPPORTED in decision.rejections)
    }

    @Test
    fun `auto WB locks only a converged high confidence measurement while recording`() {
        val stable = resolve(
            mode = UnifiedVideoControlMode.NDR,
            profile = VideoDetailProfile.FOCUSED_DETAIL,
            recording = true,
            automatic = automaticSignals(
                measuredWhiteBalanceKelvin = 4_250,
                measuredWhiteBalanceTint = 18,
                measuredWhiteBalanceConfidence = 0.8f,
                autoWhiteBalanceConverged = true
            )
        )
        val uncertain = resolve(
            mode = UnifiedVideoControlMode.NDR,
            recording = true,
            automatic = automaticSignals(
                measuredWhiteBalanceKelvin = 4_250,
                measuredWhiteBalanceConfidence = 0.69f,
                autoWhiteBalanceConverged = true
            )
        )
        val preview = resolve(
            recording = false,
            automatic = automaticSignals(
                measuredWhiteBalanceConfidence = 1f,
                autoWhiteBalanceConverged = true
            )
        )

        assertTrue(stable.whiteBalance.requestAutoLock)
        assertEquals(4_250, stable.whiteBalance.stableFallbackKelvin)
        assertEquals(18, stable.whiteBalance.stableFallbackTint)
        assertFalse(uncertain.whiteBalance.requestAutoLock)
        assertNull(uncertain.whiteBalance.stableFallbackKelvin)
        assertFalse(preview.whiteBalance.requestAutoLock)
    }

    @Test
    fun `auto WB confidence without a measured white point cannot lock recording`() {
        UnifiedVideoControlMode.values().forEach { mode ->
            VideoDetailProfile.values().forEach { profile ->
                val decision = resolve(
                    mode = mode,
                    profile = profile,
                    recording = true,
                    automatic = automaticSignals(
                        measuredWhiteBalanceKelvin = null,
                        measuredWhiteBalanceConfidence = 1f,
                        autoWhiteBalanceConverged = true
                    )
                )

                assertFalse(decision.whiteBalance.requestAutoLock)
                assertNull(decision.whiteBalance.stableFallbackKelvin)
            }
        }
    }

    @Test
    fun `automatic WB rejects out of range metadata instead of clamping it into lock evidence`() {
        val invalidMeasurements = listOf(
            1_999 to 0,
            10_001 to 0,
            5_600 to -151,
            5_600 to 151
        )
        UnifiedVideoControlMode.entries.forEach { mode ->
            VideoDetailProfile.entries.forEach { profile ->
                invalidMeasurements.forEach { (kelvin, tint) ->
                    val decision = resolve(
                        mode = mode,
                        profile = profile,
                        recording = true,
                        automatic = automaticSignals(
                            measuredWhiteBalanceKelvin = kelvin,
                            measuredWhiteBalanceTint = tint,
                            measuredWhiteBalanceConfidence = 1f,
                            autoWhiteBalanceConverged = true
                        )
                    )

                    assertFalse(decision.whiteBalance.requestAutoLock)
                    assertNull(decision.whiteBalance.stableFallbackKelvin)
                    assertEquals(0, decision.whiteBalance.stableFallbackTint)
                }
            }
        }

        listOf(2_000 to -150, 10_000 to 150).forEach { (kelvin, tint) ->
            val decision = resolve(
                recording = true,
                automatic = automaticSignals(
                    measuredWhiteBalanceKelvin = kelvin,
                    measuredWhiteBalanceTint = tint,
                    measuredWhiteBalanceConfidence = 1f,
                    autoWhiteBalanceConverged = true
                )
            )
            assertTrue(decision.whiteBalance.requestAutoLock)
            assertEquals(kelvin, decision.whiteBalance.stableFallbackKelvin)
            assertEquals(tint, decision.whiteBalance.stableFallbackTint)
        }
    }

    @Test
    fun `manual focus accepts infinity and clamps the macro endpoint`() {
        val infinity = resolve(
            manual = UnifiedVideoManualControls(
                focusDistanceDiopters = 0f,
                focusOverrideActive = true
            )
        )
        val macro = resolve(
            manual = UnifiedVideoManualControls(
                focusDistanceDiopters = 20f,
                focusOverrideActive = true
            )
        )

        assertEquals(UnifiedFocusAuthority.USER_MANUAL, infinity.focus.authority)
        assertEquals(0f, infinity.focus.lensFocusDistanceDiopters ?: -1f, 0f)
        assertEquals(10f, macro.focus.lensFocusDistanceDiopters ?: -1f, 0f)
    }

    @Test
    fun `invalid explicit manual focus fails safe instead of allowing AI to override the user`() {
        val decision = resolve(
            manual = UnifiedVideoManualControls(
                focusDistanceDiopters = Float.NaN,
                focusOverrideActive = true
            ),
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 3f,
                aiFocusConfidence = 1f
            )
        )

        assertEquals(UnifiedFocusAuthority.CONTINUOUS_AUTO, decision.focus.authority)
        assertNull(decision.focus.lensFocusDistanceDiopters)
        assertTrue(UnifiedVideoControlRejection.INVALID_MANUAL_FOCUS in decision.rejections)
    }

    @Test
    fun `detail profile confidence and slew tuning is deterministic`() {
        val natural = resolve(
            profile = VideoDetailProfile.NATURAL_DETAIL,
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 5f,
                aiFocusConfidence = 0.63f,
                previousFocusDistanceDiopters = 1f,
                elapsedSincePreviousDecisionSeconds = 0.1f
            )
        )
        val focused = resolve(
            profile = VideoDetailProfile.FOCUSED_DETAIL,
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 5f,
                aiFocusConfidence = 0.63f,
                previousFocusDistanceDiopters = 1f,
                elapsedSincePreviousDecisionSeconds = 0.1f
            )
        )
        val extreme = resolve(
            profile = VideoDetailProfile.EXTREME_DETAIL,
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 5f,
                aiFocusConfidence = 0.63f,
                previousFocusDistanceDiopters = 1f,
                elapsedSincePreviousDecisionSeconds = 0.1f
            )
        )

        assertEquals(UnifiedFocusAuthority.CONTINUOUS_AUTO, natural.focus.authority)
        assertEquals(UnifiedFocusAuthority.AI_LENS_DISTANCE, focused.focus.authority)
        assertEquals(1.3f, focused.focus.lensFocusDistanceDiopters ?: -1f, 0.0001f)
        assertEquals(UnifiedFocusAuthority.AI_LENS_DISTANCE, extreme.focus.authority)
        assertEquals(1.5f, extreme.focus.lensFocusDistanceDiopters ?: -1f, 0.0001f)
    }

    @Test
    fun `large high confidence focus transitions accelerate while NDR retains lower anti-hunt slew`() {
        val video = resolve(
            mode = UnifiedVideoControlMode.VIDEO,
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 5f,
                aiFocusConfidence = 1f,
                previousFocusDistanceDiopters = 1f,
                elapsedSincePreviousDecisionSeconds = 0.1f
            )
        )
        val ndr = resolve(
            mode = UnifiedVideoControlMode.NDR,
            automatic = automaticSignals(
                aiFocusDistanceDiopters = 5f,
                aiFocusConfidence = 1f,
                previousFocusDistanceDiopters = 1f,
                elapsedSincePreviousDecisionSeconds = 0.1f
            )
        )

        assertEquals(2.05f, video.focus.lensFocusDistanceDiopters ?: -1f, 0.0001f)
        assertEquals(1.8925f, ndr.focus.lensFocusDistanceDiopters ?: -1f, 0.0001f)
    }

    @Test
    fun `natural focus reference transitions settle below one second without overshoot in every mode`() {
        UnifiedVideoControlMode.entries.forEach { mode ->
            listOf(0.25f to 5f, 5f to 0.25f).forEach { (start, target) ->
                var previous = start
                var settledFrame = 0
                val expectedDirection = if (target > start) 1f else -1f

                transition@ for (frame in 1..24) {
                    val decision = resolve(
                        mode = mode,
                        profile = VideoDetailProfile.NATURAL_DETAIL,
                        automatic = automaticSignals(
                            aiFocusDistanceDiopters = target,
                            aiFocusConfidence = 1f,
                            previousFocusDistanceDiopters = previous,
                            elapsedSincePreviousDecisionSeconds = 0.04f
                        )
                    )
                    assertEquals(UnifiedFocusAuthority.AI_LENS_DISTANCE, decision.focus.authority)
                    val next = requireNotNull(decision.focus.lensFocusDistanceDiopters)
                    val movement = next - previous
                    assertTrue("$mode focus reversed direction", movement * expectedDirection >= 0f)
                    assertTrue("$mode focus overshot target", (target - next) * expectedDirection >= 0f)
                    previous = next
                    if (abs(target - next) <= 0.08f) {
                        settledFrame = frame
                        break@transition
                    }
                }

                assertTrue("$mode did not settle below 1 second", settledFrame in 1..24)
            }
        }
    }

    @Test
    fun `natural slew upgrade leaves focused and extreme response unchanged`() {
        fun firstStep(profile: VideoDetailProfile): Float = requireNotNull(
            resolve(
                profile = profile,
                automatic = automaticSignals(
                    aiFocusDistanceDiopters = 5f,
                    aiFocusConfidence = 1f,
                    previousFocusDistanceDiopters = 1f,
                    elapsedSincePreviousDecisionSeconds = 0.1f
                )
            ).focus.lensFocusDistanceDiopters
        )

        assertEquals(1.7f, firstStep(VideoDetailProfile.NATURAL_DETAIL), 0.0001f)
        assertEquals(2.05f, firstStep(VideoDetailProfile.FOCUSED_DETAIL), 0.0001f)
        assertEquals(2.75f, firstStep(VideoDetailProfile.EXTREME_DETAIL), 0.0001f)
    }

    @Test
    fun `profile focus deadband suppresses micro jitter without hiding a real distance change`() {
        UnifiedVideoControlMode.entries.forEach { mode ->
            VideoDetailProfile.entries.forEach { profile ->
                var previous = 2f
                listOf(0.003f, -0.003f, 0.002f, -0.002f).repeat(8).forEach { offset ->
                    val decision = resolve(
                        mode = mode,
                        profile = profile,
                        recording = true,
                        automatic = automaticSignals(
                            aiFocusDistanceDiopters = 2f + offset,
                            aiFocusConfidence = 1f,
                            previousFocusDistanceDiopters = previous,
                            elapsedSincePreviousDecisionSeconds = 0.04f
                        )
                    )
                    assertEquals(UnifiedFocusAuthority.AI_LENS_DISTANCE, decision.focus.authority)
                    val resolved = requireNotNull(decision.focus.lensFocusDistanceDiopters)
                    assertEquals(previous, resolved, 0f)
                    previous = resolved
                }

                val meaningful = resolve(
                    mode = mode,
                    profile = profile,
                    recording = true,
                    automatic = automaticSignals(
                        aiFocusDistanceDiopters = 2.05f,
                        aiFocusConfidence = 1f,
                        previousFocusDistanceDiopters = 2f,
                        elapsedSincePreviousDecisionSeconds = 0.04f
                    )
                )
                assertTrue(requireNotNull(meaningful.focus.lensFocusDistanceDiopters) > 2f)
            }
        }
    }

    @Test
    fun `focus deadband survives unavailable timing while meaningful changes remain immediate`() {
        val unavailableTimings = listOf(0f, -0.1f, Float.NaN, Float.POSITIVE_INFINITY)
        UnifiedVideoControlMode.entries.forEach { mode ->
            VideoDetailProfile.entries.forEach { profile ->
                unavailableTimings.forEach { timing ->
                    val microJitter = resolve(
                        mode = mode,
                        profile = profile,
                        recording = true,
                        automatic = automaticSignals(
                            aiFocusDistanceDiopters = 2.003f,
                            aiFocusConfidence = 1f,
                            previousFocusDistanceDiopters = 2f,
                            elapsedSincePreviousDecisionSeconds = timing
                        )
                    )
                    assertEquals(
                        2f,
                        requireNotNull(microJitter.focus.lensFocusDistanceDiopters),
                        0f
                    )

                    val meaningful = resolve(
                        mode = mode,
                        profile = profile,
                        recording = true,
                        automatic = automaticSignals(
                            aiFocusDistanceDiopters = 2.05f,
                            aiFocusConfidence = 1f,
                            previousFocusDistanceDiopters = 2f,
                            elapsedSincePreviousDecisionSeconds = timing
                        )
                    )
                    assertEquals(
                        2.05f,
                        requireNotNull(meaningful.focus.lensFocusDistanceDiopters),
                        0f
                    )
                }
            }
        }
    }

    private fun <T> List<T>.repeat(times: Int): List<T> = buildList(size * times) {
        repeat(times) { addAll(this@repeat) }
    }

    @Test
    fun `reversed hardware ranges and non-finite signals are sanitized deterministically`() {
        val decision = resolve(
            limits = UnifiedVideoSensorLimits(
                isoRange = CameraIntRange(3_200, 50),
                exposureTimeRangeNs = CameraLongRange(50_000_000L, 125_000L),
                exposureCompensationRange = CameraIntRange(6, -6),
                exposureCompensationStepStops = Float.NaN,
                whiteBalanceRangeKelvin = CameraIntRange(10_000, 2_000),
                maxFocusDistanceDiopters = 10f,
                supportsManualSensor = true,
                supportsFixedWhiteBalance = true,
                supportsManualFocusDistance = true
            ),
            manual = UnifiedVideoManualControls(
                iso = -100,
                exposureTimeNs = Long.MAX_VALUE,
                whiteBalanceKelvin = Int.MAX_VALUE
            ),
            automatic = automaticSignals(
                faceExposureBiasStops = Float.NaN,
                faceExposureConfidence = Float.NaN,
                highlightProtectionBiasStops = Float.NaN,
                sensorLinearNdrBiasStops = Float.NaN,
                aiFocusDistanceDiopters = Float.NaN,
                aiFocusConfidence = Float.NaN
            )
        )

        assertEquals(50, decision.exposure.sensorSensitivityIso)
        assertTrue(requireNotNull(decision.exposure.exposureTimeNs) in 125_000L..33_333_333L)
        assertEquals(10_000, decision.whiteBalance.fixedKelvin)
        assertEquals(0f, decision.exposure.adaptiveBiasStops, 0f)
        assertEquals(UnifiedFocusAuthority.CONTINUOUS_AUTO, decision.focus.authority)
    }

    @Test
    fun `decision generation is monotonic-safe and copied as a nonnegative integration token`() {
        assertEquals(0L, resolve(generation = -7L).generation)
        assertEquals(42L, resolve(generation = 42L).generation)
    }

    @Test
    fun `advertised AE priority capability matrix selects only the exact supported hardware axis`() {
        data class CapabilityCase(
            val advertisedModes: String,
            val isoPriority: Boolean,
            val shutterPriority: Boolean
        )

        val cases = listOf(
            CapabilityCase("OFF", isoPriority = false, shutterPriority = false),
            CapabilityCase("OFF_ISO", isoPriority = true, shutterPriority = false),
            CapabilityCase("OFF_SHUTTER", isoPriority = false, shutterPriority = true),
            CapabilityCase("OFF_ISO_SHUTTER", isoPriority = true, shutterPriority = true)
        )

        cases.forEach { capability ->
            val caseLimits = limits().copy(
                supportsIsoPriority = capability.isoPriority,
                supportsShutterPriority = capability.shutterPriority
            )
            val fixedIso = resolve(
                limits = caseLimits,
                manual = UnifiedVideoManualControls(
                    iso = 200,
                    exposureCompensationOverrideActive = true
                )
            )
            val fixedShutter = resolve(
                limits = caseLimits,
                manual = UnifiedVideoManualControls(
                    exposureTimeNs = 5_000_000L,
                    exposureCompensationOverrideActive = true
                )
            )

            assertEquals(
                "ISO authority for ${capability.advertisedModes}",
                if (capability.isoPriority) {
                    UnifiedExposureAuthority.USER_ISO_PRIORITY
                } else {
                    UnifiedExposureAuthority.PLATFORM_AE
                },
                fixedIso.exposure.authority
            )
            assertEquals(
                "shutter authority for ${capability.advertisedModes}",
                if (capability.shutterPriority) {
                    UnifiedExposureAuthority.USER_SHUTTER_PRIORITY
                } else {
                    UnifiedExposureAuthority.PLATFORM_AE
                },
                fixedShutter.exposure.authority
            )
            assertEquals(
                !capability.isoPriority,
                UnifiedVideoControlRejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in
                    fixedIso.rejections
            )
            assertEquals(
                !capability.shutterPriority,
                UnifiedVideoControlRejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in
                    fixedShutter.rejections
            )
        }
    }

    @Test
    fun `fresh software priority fills only a missing hardware axis and hardware remains authoritative`() {
        val softwareIso = resolve(
            limits = limits().copy(supportsSoftwareIsoPriority = true),
            manual = UnifiedVideoManualControls(
                iso = 200,
                exposureCompensationIndex = 2,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals().copy(
                softwareIsoPriorityExposureTimeNs = 20_000_000L
            )
        )
        val softwareShutter = resolve(
            limits = limits().copy(supportsSoftwareShutterPriority = true),
            manual = UnifiedVideoManualControls(
                exposureTimeNs = 5_000_000L,
                exposureCompensationIndex = -2,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals().copy(softwareShutterPriorityIso = 800)
        )
        val hardwareWins = resolve(
            limits = limits().copy(
                supportsIsoPriority = true,
                supportsSoftwareIsoPriority = true
            ),
            manual = UnifiedVideoManualControls(
                iso = 200,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals().copy(
                softwareIsoPriorityExposureTimeNs = 20_000_000L
            )
        )

        assertEquals(UnifiedExposureAuthority.USER_SOFTWARE_ISO_PRIORITY, softwareIso.exposure.authority)
        assertEquals(200, softwareIso.exposure.sensorSensitivityIso)
        assertEquals(20_000_000L, softwareIso.exposure.exposureTimeNs)
        assertEquals(UnifiedExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY, softwareShutter.exposure.authority)
        assertEquals(800, softwareShutter.exposure.sensorSensitivityIso)
        assertEquals(5_000_000L, softwareShutter.exposure.exposureTimeNs)
        assertEquals(UnifiedExposureAuthority.USER_ISO_PRIORITY, hardwareWins.exposure.authority)
        assertNull(hardwareWins.exposure.exposureTimeNs)
    }

    @Test
    fun `software capability without a fresh complementary value fails closed to platform AE`() {
        val missingIsoComplement = resolve(
            limits = limits().copy(supportsSoftwareIsoPriority = true),
            manual = UnifiedVideoManualControls(
                iso = 200,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals().copy(softwareIsoPriorityExposureTimeNs = null)
        )
        val missingShutterComplement = resolve(
            limits = limits().copy(supportsSoftwareShutterPriority = true),
            manual = UnifiedVideoManualControls(
                exposureTimeNs = 5_000_000L,
                exposureCompensationOverrideActive = true
            ),
            automatic = automaticSignals().copy(softwareShutterPriorityIso = null)
        )

        listOf(missingIsoComplement, missingShutterComplement).forEach { decision ->
            assertEquals(UnifiedExposureAuthority.PLATFORM_AE, decision.exposure.authority)
            assertTrue(
                UnifiedVideoControlRejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in
                    decision.rejections
            )
        }
    }

    private fun resolve(
        generation: Long = 1L,
        mode: UnifiedVideoControlMode = UnifiedVideoControlMode.VIDEO,
        profile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
        targetFps: Int = 30,
        frameExposureBudgetNs: Long = 0L,
        recording: Boolean = false,
        limits: UnifiedVideoSensorLimits = limits(),
        manual: UnifiedVideoManualControls = UnifiedVideoManualControls(),
        automatic: UnifiedVideoAutomaticSignals = automaticSignals()
    ): UnifiedVideoControlDecision = UnifiedVideoControlCoordinator.resolve(
        UnifiedVideoControlRequest(
            generation = generation,
            mode = mode,
            detailProfile = profile,
            targetFps = targetFps,
            frameExposureBudgetNs = frameExposureBudgetNs,
            recording = recording,
            limits = limits,
            manual = manual,
            automatic = automatic
        )
    )

    private fun limits(): UnifiedVideoSensorLimits = UnifiedVideoSensorLimits(
        isoRange = CameraIntRange(50, 3_200),
        exposureTimeRangeNs = CameraLongRange(125_000L, 100_000_000L),
        exposureCompensationRange = CameraIntRange(-6, 6),
        exposureCompensationStepStops = 0.5f,
        whiteBalanceRangeKelvin = CameraIntRange(2_000, 10_000),
        maxFocusDistanceDiopters = 10f,
        supportsManualSensor = true,
        supportsFixedWhiteBalance = true,
        supportsManualFocusDistance = true
    )

    private fun automaticSignals(
        meteredIso: Int? = 400,
        meteredExposureTimeNs: Long? = 10_000_000L,
        faceExposureBiasStops: Float = 0f,
        faceExposureConfidence: Float = 0f,
        highlightProtectionBiasStops: Float = 0f,
        sensorLinearNdrBiasStops: Float = 0f,
        measuredWhiteBalanceKelvin: Int? = 5_600,
        measuredWhiteBalanceTint: Int = 0,
        measuredWhiteBalanceConfidence: Float = 0f,
        autoWhiteBalanceConverged: Boolean = false,
        aiFocusDistanceDiopters: Float? = null,
        aiFocusConfidence: Float = 0f,
        previousExposureCompensationIndex: Int? = null,
        previousFocusDistanceDiopters: Float? = null,
        elapsedSincePreviousDecisionSeconds: Float = 0f
    ): UnifiedVideoAutomaticSignals = UnifiedVideoAutomaticSignals(
        meteredIso = meteredIso,
        meteredExposureTimeNs = meteredExposureTimeNs,
        faceExposureBiasStops = faceExposureBiasStops,
        faceExposureConfidence = faceExposureConfidence,
        highlightProtectionBiasStops = highlightProtectionBiasStops,
        sensorLinearNdrBiasStops = sensorLinearNdrBiasStops,
        measuredWhiteBalanceKelvin = measuredWhiteBalanceKelvin,
        measuredWhiteBalanceTint = measuredWhiteBalanceTint,
        measuredWhiteBalanceConfidence = measuredWhiteBalanceConfidence,
        autoWhiteBalanceConverged = autoWhiteBalanceConverged,
        aiFocusDistanceDiopters = aiFocusDistanceDiopters,
        aiFocusConfidence = aiFocusConfidence,
        previousExposureCompensationIndex = previousExposureCompensationIndex,
        previousFocusDistanceDiopters = previousFocusDistanceDiopters,
        elapsedSincePreviousDecisionSeconds = elapsedSincePreviousDecisionSeconds
    )
}
