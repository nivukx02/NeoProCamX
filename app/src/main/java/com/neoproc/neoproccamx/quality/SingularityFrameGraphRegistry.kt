package com.neoproc.neoproccamx.quality

internal enum class SingularityReleaseClass(val label: String, val rank: Int) {
    NO_RELEASE("NO-RELEASE", 0),
    CORE_HONEST("CORE-HONEST", 1),
    SINGULARITY_25("SINGULARITY-25", 25),
    SINGULARITY_50("SINGULARITY-50", 50),
    SINGULARITY_75("SINGULARITY-75", 75),
    SINGULARITY_100("SINGULARITY-100", 100),
    SINGULARITY_1000("SINGULARITY-1000", 1000),
    SINGULARITY_10000("SINGULARITY-10000", 10000),
    SINGULARITY_100000("SINGULARITY-100000", 100000);

    fun atMost(other: SingularityReleaseClass): SingularityReleaseClass =
        if (rank <= other.rank) this else other

    companion object {
        fun fromLabel(label: String): SingularityReleaseClass =
            entries.firstOrNull { it.label == label || it.name == label } ?: CORE_HONEST
    }
}

internal enum class SingularityTransportClassification {
    AHB_VULKAN_ZERO_COPY,
    AHB_GPU_COMPATIBILITY,
    ENCODER_SURFACE_DIRECT,
    PRIVATE_MASTER_GPU_EXPORT,
    RAW_SENSOR_PRIVATE_MASTER,
    CPU_COMPATIBILITY_NON_SINGULARITY,
    UNPROVEN
}

internal enum class SingularityStageExecutionState {
    EXECUTED,
    IDENTITY_PASS,
    FALLBACK_EXECUTED,
    DISABLED_BY_TIER,
    DISABLED_BY_DEVICE,
    FAILED_WITH_DOWNGRADE,
    FAILED_RECORDING_STOPPED
}

internal enum class SingularityStageAuthority(
    val blockIndex: Int,
    val displayName: String,
    val minimumReleaseClass: SingularityReleaseClass,
    val capabilityToken: String
) {
    CAPTURE_CONTRACT(0, "Capture Contract", SingularityReleaseClass.SINGULARITY_25, "camera2_capture_contract"),
    SENSOR_CALIBRATION(1, "Sensor Calibration", SingularityReleaseClass.SINGULARITY_50, "sensor_calibration_metadata"),
    RAW_RECONSTRUCTION(2, "RAW Reconstruction", SingularityReleaseClass.SINGULARITY_50, "raw_reconstruction"),
    OPTICAL_IDENTITY(3, "Optical Identity", SingularityReleaseClass.SINGULARITY_50, "optical_identity"),
    EXPOSURE_AUTHORITY(4, "Exposure Authority", SingularityReleaseClass.SINGULARITY_50, "exposure_authority"),
    DYNAMIC_RANGE_AUTHORITY(5, "Dynamic Range Authority", SingularityReleaseClass.SINGULARITY_50, "dynamic_range_authority"),
    NOISE_AUTHORITY(6, "Noise Authority", SingularityReleaseClass.SINGULARITY_50, "noise_authority"),
    MOTION_AUTHORITY(7, "Motion Authority", SingularityReleaseClass.SINGULARITY_75, "motion_authority"),
    MULTI_LENS_AUTHORITY(8, "Multi-Lens Authority", SingularityReleaseClass.SINGULARITY_75, "multi_lens_authority"),
    SUBJECT_DEPTH_AUTHORITY(9, "Subject And Depth Authority", SingularityReleaseClass.SINGULARITY_75, "subject_depth_authority"),
    TEXTURE_AUTHORITY(10, "Texture Authority", SingularityReleaseClass.SINGULARITY_50, "texture_authority"),
    RESOLUTION_AUTHORITY(11, "Resolution Authority", SingularityReleaseClass.SINGULARITY_75, "resolution_authority"),
    COLOR_AUTHORITY(12, "Color Authority", SingularityReleaseClass.SINGULARITY_50, "color_authority"),
    TONE_CINEMA_AUTHORITY(13, "Tone And Cinema Look Authority", SingularityReleaseClass.SINGULARITY_50, "tone_cinema_authority"),
    OUTPUT_ENCODE_AUTHORITY(14, "Output And Encode Authority", SingularityReleaseClass.SINGULARITY_25, "output_encode_authority"),
    QC_METADATA_AUTHORITY(15, "QC And Metadata Authority", SingularityReleaseClass.SINGULARITY_25, "qc_metadata_authority");

    companion object {
        fun forBlock(blockIndex: Int): SingularityStageAuthority =
            entries.first { it.blockIndex == blockIndex }
    }
}

internal data class SingularityStageDefinition(
    val globalStageId: Int,
    val blockIndex: Int,
    val localStageIndex: Int,
    val stageName: String,
    val functionalAuthority: String,
    val minimumReleaseClass: SingularityReleaseClass,
    val requiredCapabilities: Set<String>,
    val qcGateIds: Set<SingularityQualityGateId>,
    val smfRecordKey: String
) {
    init {
        require(globalStageId in 0 until SingularityFrameGraphRegistry.STAGE_COUNT) {
            "globalStageId must be 0..255"
        }
        require(blockIndex in 0 until SingularityFrameGraphRegistry.BLOCK_COUNT) {
            "blockIndex must be 0..15"
        }
        require(localStageIndex in 0 until SingularityFrameGraphRegistry.STAGES_PER_BLOCK) {
            "localStageIndex must be 0..15"
        }
        require(globalStageId == blockIndex * SingularityFrameGraphRegistry.STAGES_PER_BLOCK + localStageIndex) {
            "globalStageId must match block/local stage indexes"
        }
        require(stageName.matches(STAGE_NAME_PATTERN)) { "stageName must be a stable API identifier" }
        require(functionalAuthority.isNotBlank()) { "functionalAuthority is required" }
        require(smfRecordKey.isNotBlank()) { "smfRecordKey is required" }
    }

    companion object {
        private val STAGE_NAME_PATTERN = Regex("[a-z0-9_]+")
    }
}

internal data class SingularityStageEvidence(
    val globalStageId: Int,
    val blockIndex: Int,
    val localStageIndex: Int,
    val stageName: String,
    val functionalAuthority: String,
    val inputResources: List<String>,
    val outputResources: List<String>,
    val minimumReleaseClass: SingularityReleaseClass,
    val requiredCapabilities: Set<String>,
    val executionBackend: String,
    val executionState: SingularityStageExecutionState,
    val fallbackId: String,
    val fallbackReason: String,
    val gpuDurationNs: Long,
    val nnapiDurationNs: Long,
    val cpuControlDurationNs: Long,
    val confidence: Double,
    val qcGateIds: Set<SingularityQualityGateId>,
    val smfRecordKey: String,
    val evidenceReferences: List<String>
) {
    init {
        val definition = SingularityFrameGraphRegistry.stageById(globalStageId)
            ?: error("Unknown Singularity stage id $globalStageId")
        require(blockIndex == definition.blockIndex) { "blockIndex does not match registry" }
        require(localStageIndex == definition.localStageIndex) { "localStageIndex does not match registry" }
        require(stageName == definition.stageName) { "stageName does not match registry" }
        require(functionalAuthority == definition.functionalAuthority) { "functionalAuthority does not match registry" }
        require(minimumReleaseClass == definition.minimumReleaseClass) {
            "minimumReleaseClass does not match registry"
        }
        require(requiredCapabilities == definition.requiredCapabilities) {
            "requiredCapabilities do not match registry"
        }
        require(qcGateIds == definition.qcGateIds) { "qcGateIds do not match registry" }
        require(smfRecordKey == definition.smfRecordKey) { "smfRecordKey does not match registry" }
        require(executionBackend.isNotBlank()) { "executionBackend is required" }
        require(gpuDurationNs >= 0L) { "gpuDurationNs cannot be negative" }
        require(nnapiDurationNs >= 0L) { "nnapiDurationNs cannot be negative" }
        require(cpuControlDurationNs >= 0L) { "cpuControlDurationNs cannot be negative" }
        require(confidence in 0.0..1.0) { "confidence must be 0.0..1.0" }
        if (executionState == SingularityStageExecutionState.FALLBACK_EXECUTED) {
            require(fallbackId.isNotBlank()) { "fallbackId is required for fallback execution" }
            require(fallbackReason.isNotBlank()) { "fallbackReason is required for fallback execution" }
        }
        if (
            executionState == SingularityStageExecutionState.FAILED_WITH_DOWNGRADE ||
            executionState == SingularityStageExecutionState.FAILED_RECORDING_STOPPED
        ) {
            require(fallbackReason.isNotBlank()) { "failure reason is required for failed stage state" }
        }
    }

    val isBlockingFailure: Boolean
        get() = executionState == SingularityStageExecutionState.FAILED_WITH_DOWNGRADE ||
            executionState == SingularityStageExecutionState.FAILED_RECORDING_STOPPED

    val isReduction: Boolean
        get() = executionState == SingularityStageExecutionState.FALLBACK_EXECUTED ||
            executionState == SingularityStageExecutionState.DISABLED_BY_TIER ||
            executionState == SingularityStageExecutionState.DISABLED_BY_DEVICE ||
            executionState == SingularityStageExecutionState.FAILED_WITH_DOWNGRADE

    companion object {
        fun fromDefinition(
            definition: SingularityStageDefinition,
            executionBackend: String,
            executionState: SingularityStageExecutionState,
            inputResources: List<String> = emptyList(),
            outputResources: List<String> = emptyList(),
            fallbackId: String = "",
            fallbackReason: String = "",
            gpuDurationNs: Long = 0L,
            nnapiDurationNs: Long = 0L,
            cpuControlDurationNs: Long = 0L,
            confidence: Double = 1.0,
            evidenceReferences: List<String> = emptyList()
        ): SingularityStageEvidence =
            SingularityStageEvidence(
                globalStageId = definition.globalStageId,
                blockIndex = definition.blockIndex,
                localStageIndex = definition.localStageIndex,
                stageName = definition.stageName,
                functionalAuthority = definition.functionalAuthority,
                inputResources = inputResources,
                outputResources = outputResources,
                minimumReleaseClass = definition.minimumReleaseClass,
                requiredCapabilities = definition.requiredCapabilities,
                executionBackend = executionBackend,
                executionState = executionState,
                fallbackId = fallbackId,
                fallbackReason = fallbackReason,
                gpuDurationNs = gpuDurationNs,
                nnapiDurationNs = nnapiDurationNs,
                cpuControlDurationNs = cpuControlDurationNs,
                confidence = confidence,
                qcGateIds = definition.qcGateIds,
                smfRecordKey = definition.smfRecordKey,
                evidenceReferences = evidenceReferences
            )
    }
}

internal object SingularityFrameGraphRegistry {
    const val BLOCK_COUNT = 16
    const val STAGES_PER_BLOCK = 16
    const val STAGE_COUNT = BLOCK_COUNT * STAGES_PER_BLOCK
    const val REGISTRY_VERSION = "SINGULARITY_FRAME_GRAPH_256_V1"

    val stages: List<SingularityStageDefinition> = buildStages()
    val stagesById: Map<Int, SingularityStageDefinition> = stages.associateBy { it.globalStageId }
    val stagesByName: Map<String, SingularityStageDefinition> = stages.associateBy { it.stageName }

    init {
        require(stages.size == STAGE_COUNT) { "Singularity registry must contain 256 stages" }
        require(stages.map { it.globalStageId } == (0 until STAGE_COUNT).toList()) {
            "Singularity stage ids must be contiguous 000..255"
        }
        require(stages.groupBy { it.blockIndex }.size == BLOCK_COUNT) {
            "Singularity registry must contain exactly 16 blocks"
        }
        stages.groupBy { it.blockIndex }.forEach { (block, blockStages) ->
            require(blockStages.size == STAGES_PER_BLOCK) { "Block $block must contain 16 stages" }
            require(blockStages.map { it.localStageIndex } == (0 until STAGES_PER_BLOCK).toList()) {
                "Block $block local stage ids must be contiguous 00..15"
            }
        }
        require(stagesByName.size == STAGE_COUNT) { "Stage names must be unique" }
    }

    fun stageById(id: Int): SingularityStageDefinition? = stagesById[id]

    fun stageByName(name: String): SingularityStageDefinition? = stagesByName[name]

    fun evidenceForAll(
        executionBackend: String,
        executionState: SingularityStageExecutionState,
        evidenceReference: String = ""
    ): List<SingularityStageEvidence> =
        stages.map { definition ->
            SingularityStageEvidence.fromDefinition(
                definition = definition,
                executionBackend = executionBackend,
                executionState = executionState,
                evidenceReferences = evidenceReference.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            )
        }

    private fun buildStages(): List<SingularityStageDefinition> =
        namesByBlock().flatMapIndexed { blockIndex, names ->
            val authority = SingularityStageAuthority.forBlock(blockIndex)
            names.mapIndexed { localStageIndex, stageName ->
                val globalStageId = blockIndex * STAGES_PER_BLOCK + localStageIndex
                SingularityStageDefinition(
                    globalStageId = globalStageId,
                    blockIndex = blockIndex,
                    localStageIndex = localStageIndex,
                    stageName = stageName,
                    functionalAuthority = authority.displayName,
                    minimumReleaseClass = authority.minimumReleaseClass,
                    requiredCapabilities = requiredCapabilitiesFor(authority, stageName),
                    qcGateIds = qcGateIdsFor(blockIndex, localStageIndex, stageName),
                    smfRecordKey = "stage_${globalStageId.toString().padStart(3, '0')}_$stageName"
                )
            }
        }

    private fun requiredCapabilitiesFor(
        authority: SingularityStageAuthority,
        stageName: String
    ): Set<String> = buildSet {
        add(authority.capabilityToken)
        if (stageName.contains("vulkan") || authority.blockIndex in 2..7 || authority.blockIndex in 10..14) {
            add("vulkan_or_validated_native_equivalent")
        }
        if (
            authority == SingularityStageAuthority.SUBJECT_DEPTH_AUTHORITY ||
            authority == SingularityStageAuthority.RESOLUTION_AUTHORITY
        ) {
            add("validated_neural_or_shader_equivalent")
        }
        if (stageName.contains("multi_lens") || stageName.contains("physical_")) add("physical_camera_streams")
        if (stageName.contains("raw")) add("raw_sensor_stream")
        if (stageName.contains("npv10")) add("npv10_private_master")
        if (stageName.contains("encoder") || stageName.contains("muxer") || stageName.contains("container")) {
            add("mediacodec_mediamuxer_output")
        }
    }

    private fun qcGateIdsFor(
        blockIndex: Int,
        localStageIndex: Int,
        stageName: String
    ): Set<SingularityQualityGateId> = buildSet {
        when (blockIndex) {
            5 -> addAll(listOf(SingularityQualityGateId.QG04, SingularityQualityGateId.QG05))
            6 -> addAll(listOf(SingularityQualityGateId.QG03, SingularityQualityGateId.QG07))
            7 -> add(SingularityQualityGateId.QG02)
            10 -> addAll(listOf(SingularityQualityGateId.QG01, SingularityQualityGateId.QG07, SingularityQualityGateId.QG08))
            11 -> addAll(listOf(SingularityQualityGateId.QG09, SingularityQualityGateId.QG08))
            12 -> add(SingularityQualityGateId.QG11)
            13 -> addAll(listOf(SingularityQualityGateId.QG10, SingularityQualityGateId.QG13))
            14 -> addAll(listOf(SingularityQualityGateId.QG12, SingularityQualityGateId.QG15))
            15 -> add(SingularityQualityGateId.QG16)
        }
        if (stageName.contains("skin")) add(SingularityQualityGateId.QG06)
        if (stageName.contains("highlight")) add(SingularityQualityGateId.QG04)
        if (stageName.contains("shadow")) add(SingularityQualityGateId.QG05)
        if (stageName.contains("text")) add(SingularityQualityGateId.QG08)
        if (stageName.contains("thermal")) add(SingularityQualityGateId.QG14)
        when (blockIndex to localStageIndex) {
            15 to 7 -> addAll(listOf(SingularityQualityGateId.QG01, SingularityQualityGateId.QG02, SingularityQualityGateId.QG03, SingularityQualityGateId.QG04))
            15 to 8 -> addAll(listOf(SingularityQualityGateId.QG05, SingularityQualityGateId.QG06, SingularityQualityGateId.QG07, SingularityQualityGateId.QG08))
            15 to 9 -> add(SingularityQualityGateId.QG09)
            15 to 10 -> addAll(listOf(SingularityQualityGateId.QG10, SingularityQualityGateId.QG11))
            15 to 11 -> addAll(listOf(SingularityQualityGateId.QG12, SingularityQualityGateId.QG13))
            15 to 12 -> addAll(listOf(SingularityQualityGateId.QG14, SingularityQualityGateId.QG15, SingularityQualityGateId.QG16))
        }
    }

    private fun namesByBlock(): List<List<String>> = listOf(
        listOf(
            "frame_identity",
            "session_identity",
            "recording_intent",
            "device_capability_snapshot",
            "release_candidate_initialization",
            "camera_id_binding",
            "physical_camera_id_binding",
            "stream_combination_validation",
            "sensor_request_validation",
            "manual_control_packet",
            "frame_duration_contract",
            "timestamp_alignment",
            "imu_window_binding",
            "rolling_shutter_metadata",
            "thermal_memory_budget_snapshot",
            "capture_evidence_record_initialization"
        ),
        listOf(
            "black_level_ingest",
            "white_level_ingest",
            "bayer_pattern_validation",
            "active_array_and_crop_validation",
            "optical_black_correction",
            "fixed_pattern_noise_profile",
            "hot_pixel_map",
            "sensor_defect_repair",
            "lens_shading_map",
            "sensor_vignetting_correction",
            "row_column_banding_model",
            "sensor_noise_profile",
            "iso_gain_normalization",
            "white_saturation_tagging",
            "calibration_confidence",
            "calibrated_sensor_output"
        ),
        listOf(
            "raw_packing_validation",
            "raw_unpack_and_linearization",
            "green_split_balance",
            "edge_direction_estimation",
            "luma_demosaic_reconstruction",
            "chroma_demosaic_reconstruction",
            "false_color_suppression",
            "zipper_artifact_suppression",
            "moire_risk_control",
            "clipped_mosaic_reconstruction",
            "post_demosaic_defect_repair",
            "spectral_normalization",
            "camera_rgb_transform",
            "linear_range_sanitization",
            "reconstruction_detail_confidence",
            "linear_rgb_reconstruction_output"
        ),
        listOf(
            "lens_profile_selection",
            "focal_aperture_context",
            "distortion_map_generation",
            "geometric_distortion_correction",
            "chromatic_aberration_map",
            "lateral_chromatic_aberration_correction",
            "longitudinal_chromatic_aberration_confidence",
            "diffraction_risk_estimation",
            "psf_mtf_estimation",
            "deconvolution_confidence",
            "optical_recovery",
            "flare_veiling_glare_estimation",
            "vignette_residual_correction",
            "center_edge_resolution_balance",
            "optical_artifact_qc",
            "optically_corrected_output"
        ),
        listOf(
            "flicker_frequency_detection",
            "exposure_schedule_feasibility",
            "base_exposure_schedule",
            "shadow_exposure_schedule",
            "highlight_exposure_schedule",
            "multi_exposure_synchronization",
            "exposure_normalization",
            "motion_exposure_delta",
            "clipped_highlight_mask",
            "deep_shadow_mask",
            "skin_highlight_protection",
            "neon_highlight_protection",
            "exposure_confidence",
            "exposure_downgrade_ladder",
            "exposure_pumping_guard",
            "exposure_authority_output"
        ),
        listOf(
            "base_exposure_reconstruction",
            "shadow_exposure_reconstruction",
            "highlight_exposure_reconstruction",
            "exposure_alignment_confidence",
            "exposure_fusion_weights",
            "ghost_safe_exposure_fusion",
            "highlight_detail_recovery",
            "highlight_shoulder_construction",
            "shadow_chroma_floor",
            "shadow_texture_preservation",
            "local_dynamic_range_zone_map",
            "global_dynamic_range_estimate",
            "tone_headroom_estimate",
            "fusion_artifact_qc",
            "dynamic_range_fallback_selection",
            "scene_linear_dynamic_range_output"
        ),
        listOf(
            "shot_noise_model",
            "read_noise_model",
            "fixed_pattern_noise_residual",
            "banding_risk_map",
            "chroma_noise_model",
            "chroma_crawl_risk_map",
            "temporal_noise_model",
            "texture_noise_classifier",
            "protected_texture_masks",
            "denoise_strength_map",
            "spatial_noise_reduction",
            "temporal_noise_reduction",
            "banding_suppression",
            "chroma_stabilization",
            "detail_preservation_qc",
            "noise_authority_output"
        ),
        listOf(
            "gyro_sample_ingest",
            "accelerometer_sample_ingest",
            "sensor_imu_timestamp_alignment",
            "rolling_shutter_motion_model",
            "optical_flow_pyramid",
            "dense_sparse_flow_solution",
            "imu_flow_fusion",
            "motion_occlusion_map",
            "subject_motion_map",
            "reference_frame_anchor",
            "scene_cut_detection",
            "temporal_fusion_weights",
            "ghost_rejection_mask",
            "temporal_accumulation",
            "temporal_shimmer_qc",
            "motion_authority_output"
        ),
        listOf(
            "logical_camera_capability",
            "physical_stream_feasibility",
            "physical_lens_identity",
            "lens_intrinsic_calibration",
            "lens_extrinsic_calibration",
            "multi_lens_timestamp_tolerance",
            "multi_lens_exposure_match",
            "multi_lens_white_balance_match",
            "multi_lens_geometry_alignment",
            "parallax_map",
            "multi_lens_occlusion_mask",
            "per_lens_quality_score",
            "multi_lens_fusion_weights",
            "multi_lens_fusion",
            "multi_lens_seam_qc",
            "primary_or_fused_lens_output"
        ),
        listOf(
            "face_detection",
            "face_landmark_tracking",
            "skin_confidence_mask",
            "subject_segmentation",
            "depth_inference",
            "depth_confidence",
            "foreground_priority_map",
            "background_protection_map",
            "text_region_mask",
            "sky_region_mask",
            "foliage_region_mask",
            "neon_region_mask",
            "hair_fabric_region_mask",
            "protected_neutral_mask",
            "semantic_mask_temporal_stabilization",
            "subject_depth_authority_output"
        ),
        listOf(
            "microtexture_confidence_map",
            "edge_confidence_map",
            "skin_texture_guard",
            "hair_texture_guard",
            "fabric_texture_guard",
            "foliage_texture_guard",
            "text_legibility_guard",
            "clipped_edge_guard",
            "noise_sharpening_rejection",
            "local_contrast_pyramid",
            "midtone_detail_recovery",
            "confidence_gated_deconvolution",
            "edge_halo_clamp",
            "ringing_suppression",
            "texture_temporal_consistency_qc",
            "texture_authority_output"
        ),
        listOf(
            "native_resolution_confidence",
            "resolution_lift_requirement",
            "temporal_resolution_feasibility",
            "temporal_resolution_inputs",
            "temporal_resolution_lift",
            "neural_resolution_capability",
            "neural_tile_plan",
            "neural_tile_overlap_and_blend",
            "neural_resolution_lift",
            "hallucination_risk_map",
            "text_hallucination_rejection",
            "tile_seam_validation",
            "resolution_temporal_consistency",
            "validated_spatial_upscale_fallback",
            "output_acuity_qc",
            "resolution_authority_output"
        ),
        listOf(
            "camera_color_matrix",
            "white_balance_gain_stabilization",
            "neutral_axis_protection",
            "scene_illuminant_estimation",
            "chromatic_adaptation",
            "working_gamut_conversion",
            "skin_color_protection",
            "sky_color_protection",
            "neon_color_protection",
            "foliage_color_protection",
            "saturation_chroma_authority",
            "gamut_mapping",
            "lut_selection",
            "tetrahedral_lut_interpolation",
            "per_zone_lut_blending",
            "color_accuracy_qc_output"
        ),
        listOf(
            "scene_luminance_analysis",
            "display_output_target_selection",
            "tone_curve_selection",
            "local_tone_zone_map",
            "highlight_rolloff",
            "shadow_depth_mapping",
            "midtone_contrast_mapping",
            "tone_temporal_stabilization",
            "preview_file_tone_match",
            "creative_grain_policy",
            "creative_grain_synthesis",
            "highlight_halation",
            "highlight_bloom",
            "optional_anamorphic_streak",
            "optional_vignette_gate_character",
            "tone_cinema_authority_output"
        ),
        listOf(
            "output_mode_capability_validation",
            "output_transform_selection",
            "output_bit_depth_validation",
            "output_primaries_validation",
            "output_transfer_validation",
            "output_range_matrix_validation",
            "final_gpu_output_transform",
            "encoder_capability_probe",
            "codec_profile_level_selection",
            "bitrate_gop_quality_plan",
            "encoder_surface_preparation",
            "timestamp_cadence_contract",
            "encoder_frame_submission",
            "muxer_sample_write",
            "container_finalization_and_integrity",
            "playable_output_verification"
        ),
        listOf(
            "stage_ledger_finalization",
            "smf_singularity_envelope_initialization",
            "camera_device_evidence_record",
            "vulkan_transport_evidence_record",
            "neural_model_evidence_record",
            "encoder_output_evidence_record",
            "thermal_memory_drop_evidence_record",
            "qg01_qg04_edge_motion_chroma_highlight",
            "qg05_qg08_shadow_skin_texture_text",
            "qg09_neural_resolution_honesty",
            "qg10_qg11_tone_color_stability",
            "qg12_qg13_encode_preview_parity",
            "qg14_qg16_endurance_drop_metadata",
            "singularity_release_class_evaluation",
            "sidecar_finalization_and_recovery",
            "final_release_seal"
        )
    )
}
