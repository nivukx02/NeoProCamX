package com.neoproc.neoproccamx.apex

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 7 — NIVUKX APEX PIPELINE ORCHESTRATOR
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Orchestrates the parameter flow from the APEX Scene Analyzer to the Vulkan native bridge.
 * Maps computed ApexProfileParams into the uniform arrays expected by ycbcr_preview.frag.
 */
object ApexPipelineOrchestrator {

    enum class ApexVulkanPass {
        ICTCP_CONVERT,
        TILE_LOCAL_TONE,
        MULTI_SCALE_DETAIL,
        TONE_MAP_DITHER
    }

    data class ApexPassPlan(
        val mode: ApexMode,
        val passes: List<ApexVulkanPass>,
        val previewRecordingSignature: Int
    )

    /**
     * Injects APEX-specific parameters into the 16-float uniform array (previewGrade/encoderGrade)
     * which corresponds to uGrade0..uGrade3 in the Vulkan shader.
     *
     * @param grade The 16-float array to modify (previewGrade or encoderGrade)
     * @param params The computed APEX profile parameters
     * @param metrics The current scene metrics
     */
    fun injectApexUniforms(
        grade: FloatArray,
        params: ApexProfileParams,
        metrics: SceneMetrics
    ) {
        require(grade.size == 16) { "Grade array must have exactly 16 floats" }
        
        // uGrade0 remains untouched:
        // grade[0] = contrast
        // grade[1] = saturation
        // grade[2] = shadowLift (unused by APEX natively, used by base path)
        // grade[3] = highlightCompression (APEX uses it as sceneWhite proxy)
        grade[3] = (metrics.sceneWhite - 0.18f).coerceIn(0f, 0.82f)

        // Overwrite uGrade1 (Indices 4, 5, 6, 7)
        grade[4] = params.sourcePeakNits     // uGrade1.x
        grade[5] = params.targetPeakNits     // uGrade1.y
        grade[6] = params.kneeRatio          // uGrade1.z
        grade[7] = params.shoulderStrength   // uGrade1.w

        // Overwrite uGrade2 (Indices 8, 9, 10, 11)
        grade[8] = params.shadowLift                 // uGrade2.x (apexShadowLift)
        
        // Base detail gain varies by mode:
        grade[9] = if (params.mode == ApexMode.ZERO) {
             -0.15f  // Special suppression for ZERO mode
        } else {
             params.detailGain1 // Detail amplification for SHARP/CINEMA
        }                                            // uGrade2.y (detailGainBase)
        
        grade[10] = params.colorVolumeCompression    // uGrade2.z (colorVolComp)
        grade[11] = params.saturationPreservation    // uGrade2.w (satPres)

        // uGrade3.x (Index 12) is Tone Delivery Mode (already set, do not overwrite)
        // Overwrite uGrade3.y (Index 13)
        grade[13] = params.baseChromaDenoise         // uGrade3.y (chromaSupBase)

        // APEX repurposes uGrade3.z/w because its branch returns before the
        // standard shadow/highlight-bias path. This keeps the 16-float ABI intact.
        grade[14] = metrics.noiseRisk.coerceIn(0f, 1f)    // uGrade3.z (apexNoiseRisk)
        grade[15] = metrics.motionScore.coerceIn(0f, 1f)  // uGrade3.w (apexMotionScore)
    }

    /**
     * Deterministic multi-pass plan for preview and encoder parity.
     * The signature is intentionally derived only from values that affect the
     * shader result, so preview/recording can verify they are rendering the
     * same APEX frame contract without depending on object identity.
     */
    fun buildPassPlan(
        params: ApexProfileParams,
        metrics: SceneMetrics
    ): ApexPassPlan {
        val passes = if (params.mode == ApexMode.ZERO) {
            listOf(
                ApexVulkanPass.ICTCP_CONVERT,
                ApexVulkanPass.TILE_LOCAL_TONE,
                ApexVulkanPass.TONE_MAP_DITHER
            )
        } else {
            listOf(
                ApexVulkanPass.ICTCP_CONVERT,
                ApexVulkanPass.TILE_LOCAL_TONE,
                ApexVulkanPass.MULTI_SCALE_DETAIL,
                ApexVulkanPass.TONE_MAP_DITHER
            )
        }
        var signature = 17
        fun mix(value: Float) {
            signature = signature * 31 + value.toBits()
        }
        signature = signature * 31 + params.mode.id
        mix(params.sourcePeakNits)
        mix(params.targetPeakNits)
        mix(params.kneeRatio)
        mix(params.shoulderStrength)
        mix(params.shadowLift)
        mix(params.detailGain1)
        mix(params.colorVolumeCompression)
        mix(params.saturationPreservation)
        mix(params.baseChromaDenoise)
        mix(metrics.sceneWhite)
        mix(metrics.noiseRisk)
        mix(metrics.motionScore)
        return ApexPassPlan(params.mode, passes, signature)
    }
}
