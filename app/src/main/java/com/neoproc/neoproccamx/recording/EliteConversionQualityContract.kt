package com.neoproc.neoproccamx.recording

/**
 * Fail-closed quality evidence shared by the offline video and RAW develop converters.
 *
 * This is deliberately an audit contract, not another image-processing stage. It never selects a
 * codec, bitrate, resolution, CPU path, or GPU path, and therefore cannot silently trade image quality
 * for speed. A converter supplies facts from the path that actually ran; [enforce] rejects publication
 * when those facts show an 8-bit fallback, a duplicate transfer/quantization, inconsistent colour tags,
 * lost capture metadata, dropped frames, reduced geometry, or a bitrate below the caller's own floor.
 *
 * Sensor-RAW develops are required to use a linear Float32 ACEScg working plane. P010 video develops may
 * use either linear Float32 source RGB or ACEScg. A RAW-to-RAW repack remains sensor-linear and must retain
 * its source bit depth. The contract is compute-backend neutral: CPU reference output and a parity-validated
 * GPU acceleration path are equally valid.
 */
internal object EliteConversionQualityContract {

    const val VERSION: String = "ELITE_CONVERSION_QUALITY_V2"

    enum class Delivery {
        VIDEO,
        PRO,
        RAW,
        NEOLOG,
        NDR10_PLUS,
        NIVUKX_VISION
    }

    enum class SourceDomain {
        P010_VIDEO,
        RAW_SENSOR
    }

    enum class OutputTransport(val compressed: Boolean) {
        R210_RGB_444(false),
        V210_YUV_422(false),
        HEVC_MAIN10_420(true),
        HEVC_MAIN10_422(true),
        RAW_PACKED(false)
    }

    enum class WorkingSpace {
        LINEAR_RGB,
        LINEAR_ACESCG,
        SENSOR_NATIVE_LINEAR
    }

    enum class SampleType {
        FLOAT,
        UNSIGNED_INTEGER
    }

    /** Gamut in which decoded sample values are semantically defined. */
    enum class SignalGamut {
        BT709,
        BT2020,
        ACES_AP1,
        SENSOR_NATIVE
    }

    /** Transfer function that gives the sample values their actual image meaning. */
    enum class SignalTransfer {
        SDR,
        NEOLOG,
        ST2084_PQ,
        SENSOR_LINEAR
    }

    /** Standardized QuickTime/codec carrier primaries; custom NeoLog remains declared separately. */
    enum class CarrierPrimaries {
        BT709,
        BT2020,
        SENSOR_NATIVE
    }

    enum class CarrierTransfer {
        BT709,
        ST2084_PQ,
        SENSOR_LINEAR
    }

    enum class Matrix {
        BT709_NCL,
        BT2020_NCL,
        /** BT.709 `nclx` compatibility value used by the full-range r210 NeoLog carrier. */
        BT709_CARRIER,
        /** BT.2020-NCL `nclx` value used as a full-range r210 carrier tag. */
        BT2020_CARRIER,
        RGB_IDENTITY,
        SENSOR_NATIVE
    }

    enum class Range {
        LIMITED,
        FULL
    }

    enum class Preservation {
        PRESERVED,
        NOT_PRESENT,
        LOST
    }

    enum class Status {
        PASS,
        FAIL
    }

    enum class CompanionColorTransform {
        NEOLOG_ACES_AP1_TO_REC709_LUT
    }

    data class WorkingPipelineEvidence(
        val space: WorkingSpace,
        val sampleType: SampleType,
        val precisionBits: Int,
        val signalIsLinear: Boolean
    )

    /**
     * Counts actual colour operations, not helper calls. Tone mapping may be absent, but it may never be
     * applied twice. Every developed delivery performs exactly one output-transfer encode and one final
     * quantization. RAW uses no display transfer; its one final boundary is the lossless integer repack.
     */
    data class TransformEvidence(
        val toneMapApplications: Int,
        val outputTransferApplications: Int,
        val finalQuantizationApplications: Int
    )

    data class SemanticColor(
        val gamut: SignalGamut,
        val transfer: SignalTransfer
    )

    data class CarrierColorTags(
        val primaries: CarrierPrimaries,
        val transfer: CarrierTransfer,
        val matrix: Matrix,
        val range: Range
    )

    /**
     * [semantic] describes the pixels. [expectedCarrier] describes only the standardized compatibility
     * tags written to the container. NeoLog intentionally uses a BT.2020/BT.709 carrier because `nclx`
     * has no NeoLog identifier; [companionColorTransform] proves that the ACES AP1 + NeoLog meaning was
     * preserved by the required companion viewing LUT instead of being mislabeled as BT.709 imagery.
     */
    data class ColorEvidence(
        val semantic: SemanticColor,
        val expectedCarrier: CarrierColorTags?,
        val containerCarrier: CarrierColorTags?,
        val sidecarCarrier: CarrierColorTags?,
        val companionDeclaration: Preservation,
        val companionColorTransform: CompanionColorTransform?
    )

    /**
     * [NOT_PRESENT] is honest and accepted when a source genuinely did not contain that item. [LOST]
     * always fails. Frame timing is intrinsic to video and therefore must be [PRESERVED]. HDR mastering
     * evidence is mandatory for NDR/NIVUKX PQ delivery.
     */
    data class MetadataEvidence(
        val declaredDelivery: Delivery,
        val captureMetadata: Preservation,
        val whiteBalance: Preservation,
        val exposure: Preservation,
        val orientation: Preservation,
        val detailProfile: Preservation,
        val frameTiming: Preservation,
        val colorSidecar: Preservation,
        val hdrMastering: Preservation
    )

    data class SignalEvidence(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val sourceFps: Int,
        val outputFps: Int,
        val sourceFrameCount: Long?,
        val outputFrameCount: Long?
    )

    /**
     * The minimum is supplied by the existing codec/quality policy; this contract never lowers or invents
     * it. Encoded outputs must prove their configured bitrate, and may also supply a measured file bitrate.
     */
    data class BitrateEvidence(
        val minimumRequiredBps: Long,
        val configuredBps: Long,
        val measuredBps: Long? = null,
        /** Device-independent ideal target retained even when hardware is capability-bound. */
        val policyTargetBps: Long? = null,
        /** Advertised maximum of the selected encoder at admission time. */
        val advertisedCeilingBps: Long? = null,
        /** True only when the exact codec ceiling, rather than the ideal target, was configured. */
        val capabilityBound: Boolean? = null
    )

    data class Evidence(
        val delivery: Delivery,
        val sourceDomain: SourceDomain,
        val sourceBitDepth: Int,
        val outputBitDepth: Int,
        val outputTransport: OutputTransport,
        val workingPipeline: WorkingPipelineEvidence,
        val transforms: TransformEvidence,
        val color: ColorEvidence,
        val metadata: MetadataEvidence,
        val signal: SignalEvidence,
        val bitrate: BitrateEvidence?,
        /**
         * Result of configuring the encoder's optional elite controls (quality, complexity, QP ceiling,
         * operating rate, and maximum bitrate). Compressed publication requires an explicit true value;
         * null is reserved for uncompressed/RAW transports and false is a visible quality fallback.
         */
        val advancedEncoderControlsAccepted: Boolean? = null,
        val usedEightBitFallback: Boolean,
        val qualityReducedForSpeed: Boolean,
        val destructiveFallbackUsed: Boolean
    )

    data class Audit(
        val status: Status,
        val failureReasons: List<String>,
        val evidence: Evidence
    ) {
        val passed: Boolean
            get() = status == Status.PASS

        fun toMetadata(): Map<String, String> = buildMap {
            put("contractVersion", VERSION)
            put("status", status.name)
            put("failureReasons", failureReasons.joinToString("; "))
            put("delivery", evidence.delivery.name)
            put("sourceDomain", evidence.sourceDomain.name)
            put("sourceBitDepth", evidence.sourceBitDepth.toString())
            put("outputBitDepth", evidence.outputBitDepth.toString())
            put("outputTransport", evidence.outputTransport.name)
            put("workingSpace", evidence.workingPipeline.space.name)
            put("workingSampleType", evidence.workingPipeline.sampleType.name)
            put("workingPrecisionBits", evidence.workingPipeline.precisionBits.toString())
            put("workingSignalLinear", evidence.workingPipeline.signalIsLinear.toString())
            put("toneMapApplications", evidence.transforms.toneMapApplications.toString())
            put("outputTransferApplications", evidence.transforms.outputTransferApplications.toString())
            put("finalQuantizationApplications", evidence.transforms.finalQuantizationApplications.toString())
            put("semanticSignalGamut", evidence.color.semantic.gamut.name)
            put("semanticSignalTransfer", evidence.color.semantic.transfer.name)
            put("expectedCarrierColorTags", evidence.color.expectedCarrier?.asMetadataValue().orEmpty())
            put("containerCarrierColorTags", evidence.color.containerCarrier?.asMetadataValue().orEmpty())
            put("sidecarCarrierColorTags", evidence.color.sidecarCarrier?.asMetadataValue().orEmpty())
            put("companionColorDeclaration", evidence.color.companionDeclaration.name)
            put("companionColorTransform", evidence.color.companionColorTransform?.name.orEmpty())
            put("declaredDelivery", evidence.metadata.declaredDelivery.name)
            put("captureMetadata", evidence.metadata.captureMetadata.name)
            put("whiteBalance", evidence.metadata.whiteBalance.name)
            put("exposure", evidence.metadata.exposure.name)
            put("orientation", evidence.metadata.orientation.name)
            put("detailProfile", evidence.metadata.detailProfile.name)
            put("frameTiming", evidence.metadata.frameTiming.name)
            put("colorSidecar", evidence.metadata.colorSidecar.name)
            put("hdrMastering", evidence.metadata.hdrMastering.name)
            put("sourceGeometry", "${evidence.signal.sourceWidth}x${evidence.signal.sourceHeight}")
            put("outputGeometry", "${evidence.signal.outputWidth}x${evidence.signal.outputHeight}")
            put("sourceFps", evidence.signal.sourceFps.toString())
            put("outputFps", evidence.signal.outputFps.toString())
            put("sourceFrameCount", evidence.signal.sourceFrameCount?.toString().orEmpty())
            put("outputFrameCount", evidence.signal.outputFrameCount?.toString().orEmpty())
            put("minimumBitrateBps", evidence.bitrate?.minimumRequiredBps?.toString().orEmpty())
            put("configuredBitrateBps", evidence.bitrate?.configuredBps?.toString().orEmpty())
            put("measuredBitrateBps", evidence.bitrate?.measuredBps?.toString().orEmpty())
            put("policyTargetBitrateBps", evidence.bitrate?.policyTargetBps?.toString().orEmpty())
            put("advertisedBitrateCeilingBps", evidence.bitrate?.advertisedCeilingBps?.toString().orEmpty())
            put("encoderCapabilityBound", evidence.bitrate?.capabilityBound?.toString() ?: "NOT_APPLICABLE")
            put(
                "advancedEncoderControlsAccepted",
                evidence.advancedEncoderControlsAccepted?.toString() ?: "NOT_APPLICABLE"
            )
            put("usedEightBitFallback", evidence.usedEightBitFallback.toString())
            put("qualityReducedForSpeed", evidence.qualityReducedForSpeed.toString())
            put("destructiveFallbackUsed", evidence.destructiveFallbackUsed.toString())
            put("resolutionPreserved", evidence.signal.geometryPreserved().toString())
            put("frameRatePreserved", (evidence.signal.sourceFps == evidence.signal.outputFps).toString())
            put("frameCountPreserved", evidence.signal.frameCountPreserved().toString())
            put("bitrateFloorSatisfied", evidence.bitrateFloorSatisfied().toString())
            put("gpuRequired", "false")
            put("computePathPolicy", "CPU_OR_PARITY_VALIDATED_GPU_WITH_IDENTICAL_QUALITY_CONTRACT")
        }
    }

    /** Returns a complete audit without throwing, suitable for diagnostics and certificate composition. */
    fun audit(evidence: Evidence): Audit {
        val reasons = validate(evidence)
        return Audit(
            status = if (reasons.isEmpty()) Status.PASS else Status.FAIL,
            failureReasons = reasons,
            evidence = evidence
        )
    }

    /** Fail-closed publication gate. The returned audit is always a passing audit. */
    fun enforce(evidence: Evidence): Audit {
        val audit = audit(evidence)
        check(audit.passed) {
            "$VERSION rejected ${evidence.delivery}: ${audit.failureReasons.joinToString("; ")}"
        }
        return audit
    }

    private fun validate(evidence: Evidence): List<String> = buildList {
        validateBitDepthAndTransport(evidence)
        validateWorkingPipeline(evidence)
        validateTransforms(evidence)
        validateColor(evidence)
        validateMetadata(evidence)
        validateSignal(evidence)
        validateBitrate(evidence)
        validateAdvancedEncoderControls(evidence)

        if (evidence.usedEightBitFallback) add("8-bit fallback is forbidden")
        if (evidence.qualityReducedForSpeed) add("quality reduction for conversion speed is forbidden")
        if (evidence.destructiveFallbackUsed) add("destructive quality fallback is forbidden")
    }

    private fun MutableList<String>.validateBitDepthAndTransport(evidence: Evidence) {
        val minimumSourceDepth = if (evidence.sourceDomain == SourceDomain.RAW_SENSOR) 12 else 10
        if (evidence.sourceBitDepth < minimumSourceDepth) {
            add("${evidence.sourceDomain.name} source must be at least $minimumSourceDepth-bit")
        }
        if (evidence.delivery == Delivery.RAW) {
            if (evidence.sourceDomain != SourceDomain.RAW_SENSOR) {
                add("RAW delivery requires a RAW_SENSOR source")
            }
            if (evidence.outputTransport != OutputTransport.RAW_PACKED) {
                add("RAW delivery requires RAW_PACKED output")
            }
            if (evidence.outputBitDepth < evidence.sourceBitDepth) {
                add("RAW delivery must preserve source bit depth")
            }
        } else {
            if (evidence.outputTransport == OutputTransport.RAW_PACKED) {
                add("developed delivery cannot use RAW_PACKED output")
            }
            if (evidence.outputBitDepth != 10) {
                add("${evidence.delivery.name} delivery requires an actual 10-bit output")
            }
        }
    }

    private fun MutableList<String>.validateWorkingPipeline(evidence: Evidence) {
        val working = evidence.workingPipeline
        if (!working.signalIsLinear) add("working signal must remain linear until the single output transfer")

        if (evidence.delivery == Delivery.RAW) {
            if (working.space != WorkingSpace.SENSOR_NATIVE_LINEAR) {
                add("RAW delivery must remain in sensor-native linear space")
            }
            if (working.sampleType != SampleType.UNSIGNED_INTEGER) {
                add("RAW sensor-native working samples must remain unsigned integer samples")
            }
            if (working.precisionBits < evidence.sourceBitDepth) {
                add("RAW working precision must preserve source bit depth")
            }
            return
        }

        if (working.sampleType != SampleType.FLOAT || working.precisionBits < 32) {
            add("developed delivery requires at least Float32 working precision")
        }
        if (working.space != WorkingSpace.LINEAR_RGB && working.space != WorkingSpace.LINEAR_ACESCG) {
            add("developed delivery requires a linear RGB working space")
        }
        if (evidence.sourceDomain == SourceDomain.RAW_SENSOR && working.space != WorkingSpace.LINEAR_ACESCG) {
            add("RAW sensor develop requires a linear ACEScg Float32 working plane")
        }
    }

    private fun MutableList<String>.validateTransforms(evidence: Evidence) {
        val transforms = evidence.transforms
        if (transforms.toneMapApplications !in 0..1) {
            add("tone mapping must be applied at most once")
        }
        val requiredToneMaps = when (evidence.delivery) {
            Delivery.NDR10_PLUS,
            Delivery.NIVUKX_VISION -> 1
            Delivery.RAW,
            Delivery.NEOLOG -> 0
            Delivery.VIDEO,
            Delivery.PRO -> null
        }
        if (requiredToneMaps != null && transforms.toneMapApplications != requiredToneMaps) {
            add("${evidence.delivery.name} requires exactly $requiredToneMaps tone-map application(s)")
        }

        val requiredTransfers = if (evidence.delivery == Delivery.RAW) 0 else 1
        if (transforms.outputTransferApplications != requiredTransfers) {
            add("${evidence.delivery.name} requires exactly $requiredTransfers output-transfer application(s)")
        }
        if (transforms.finalQuantizationApplications != 1) {
            add("conversion requires exactly one final quantization/packing boundary")
        }
    }

    private fun MutableList<String>.validateColor(evidence: Evidence) {
        val color = evidence.color
        when (evidence.delivery) {
            Delivery.VIDEO,
            Delivery.PRO -> {
                if (color.semantic.transfer != SignalTransfer.SDR) {
                    add("VIDEO/PRO delivery requires an SDR semantic transfer")
                }
                if (color.semantic.gamut != SignalGamut.BT709 && color.semantic.gamut != SignalGamut.BT2020) {
                    add("VIDEO/PRO delivery requires BT.709 or BT.2020 semantic gamut")
                }
                validateStandardizedCarrier(color.semantic, color.expectedCarrier)
            }
            Delivery.RAW -> {
                if (color.semantic.transfer != SignalTransfer.SENSOR_LINEAR ||
                    color.semantic.gamut != SignalGamut.SENSOR_NATIVE
                ) {
                    add("RAW delivery requires sensor-native linear semantic color")
                }
                if (color.expectedCarrier != null || color.containerCarrier != null) {
                    add("RAW packed delivery must not invent standardized container color tags")
                }
            }
            Delivery.NEOLOG -> validateNeoLogColor(color)
            Delivery.NDR10_PLUS,
            Delivery.NIVUKX_VISION -> {
                if (color.semantic.transfer != SignalTransfer.ST2084_PQ ||
                    color.semantic.gamut != SignalGamut.BT2020
                ) {
                    add("NDR/NIVUKX delivery requires ST2084 PQ with BT.2020 semantic gamut")
                }
                validateStandardizedCarrier(color.semantic, color.expectedCarrier)
            }
        }

        color.expectedCarrier?.let { validateMatrixAndRange(it) }
        if (evidence.delivery != Delivery.RAW) {
            val expectedCarrier = color.expectedCarrier
            if (expectedCarrier == null) {
                add("developed delivery requires declared container carrier tags")
            } else if (color.containerCarrier == null) {
                add("published container carrier color tags are required")
            } else if (color.containerCarrier != expectedCarrier) {
                add("container carrier color tags do not match the declared carrier tags")
            }
        }

        when (evidence.metadata.colorSidecar) {
            Preservation.PRESERVED -> {
                if (color.sidecarCarrier == null) {
                    add("preserved color sidecar must contain carrier color tags")
                } else if (color.sidecarCarrier != color.expectedCarrier) {
                    add("sidecar carrier color tags do not match the declared carrier tags")
                }
            }
            Preservation.NOT_PRESENT -> {
                if (color.sidecarCarrier != null) {
                    add("color sidecar tags were supplied but sidecar state is NOT_PRESENT")
                }
            }
            Preservation.LOST -> Unit
        }

        if (evidence.delivery != Delivery.NEOLOG) {
            if (color.companionDeclaration != Preservation.NOT_PRESENT ||
                color.companionColorTransform != null
            ) {
                add("companion NeoLog color transform is only valid for NeoLog delivery")
            }
        }
    }

    private fun MutableList<String>.validateNeoLogColor(color: ColorEvidence) {
        if (color.semantic.transfer != SignalTransfer.NEOLOG || color.semantic.gamut != SignalGamut.ACES_AP1) {
            add("NeoLog delivery requires ACES AP1 gamut with the semantic NeoLog transfer")
        }
        val carrier = color.expectedCarrier
        if (carrier == null ||
            carrier.primaries != CarrierPrimaries.BT2020 ||
            carrier.transfer != CarrierTransfer.BT709
        ) {
            add("NeoLog requires the truthful BT.2020/BT.709 compatibility carrier tags")
        }
        if (color.companionDeclaration != Preservation.PRESERVED ||
            color.companionColorTransform != CompanionColorTransform.NEOLOG_ACES_AP1_TO_REC709_LUT
        ) {
            add("NeoLog requires a preserved ACES AP1 NeoLog-to-Rec.709 companion LUT declaration")
        }
    }

    private fun MutableList<String>.validateStandardizedCarrier(
        semantic: SemanticColor,
        carrier: CarrierColorTags?
    ) {
        if (carrier == null) return
        val expectedPrimaries = when (semantic.gamut) {
            SignalGamut.BT709 -> CarrierPrimaries.BT709
            SignalGamut.BT2020 -> CarrierPrimaries.BT2020
            SignalGamut.ACES_AP1,
            SignalGamut.SENSOR_NATIVE -> null
        }
        val expectedTransfer = when (semantic.transfer) {
            SignalTransfer.SDR -> CarrierTransfer.BT709
            SignalTransfer.ST2084_PQ -> CarrierTransfer.ST2084_PQ
            SignalTransfer.SENSOR_LINEAR -> CarrierTransfer.SENSOR_LINEAR
            SignalTransfer.NEOLOG -> null
        }
        if (expectedPrimaries == null || carrier.primaries != expectedPrimaries) {
            add("container carrier primaries do not match the standardized semantic gamut")
        }
        if (expectedTransfer == null || carrier.transfer != expectedTransfer) {
            add("container carrier transfer does not match the standardized semantic transfer")
        }
    }

    private fun MutableList<String>.validateMatrixAndRange(tags: CarrierColorTags) {
        when (tags.matrix) {
            Matrix.BT709_NCL -> {
                if (tags.primaries != CarrierPrimaries.BT709) add("BT.709 matrix requires BT.709 primaries")
                if (tags.range != Range.LIMITED) add("YCbCr matrix output must use limited range")
            }
            Matrix.BT2020_NCL -> {
                if (tags.primaries != CarrierPrimaries.BT2020) add("BT.2020 matrix requires BT.2020 primaries")
                if (tags.range != Range.LIMITED) add("YCbCr matrix output must use limited range")
            }
            Matrix.BT709_CARRIER -> {
                if (tags.primaries == CarrierPrimaries.SENSOR_NATIVE) {
                    add("BT.709 compatibility carrier cannot declare sensor-native primaries")
                }
                if (tags.range != Range.FULL) add("r210 BT.709 compatibility carrier must use full range")
            }
            Matrix.BT2020_CARRIER -> {
                if (tags.primaries != CarrierPrimaries.BT2020) {
                    add("BT.2020 compatibility carrier requires BT.2020 primaries")
                }
                if (tags.range != Range.FULL) add("r210 BT.2020 compatibility carrier must use full range")
            }
            Matrix.RGB_IDENTITY -> {
                if (tags.primaries == CarrierPrimaries.SENSOR_NATIVE) {
                    add("sensor-native delivery must use the sensor-native matrix tag")
                }
                if (tags.range != Range.FULL) add("RGB identity output must use full range")
            }
            Matrix.SENSOR_NATIVE -> {
                if (tags.primaries != CarrierPrimaries.SENSOR_NATIVE) {
                    add("sensor-native matrix requires sensor-native primaries")
                }
                if (tags.range != Range.FULL) add("sensor-native output must use full range")
            }
        }
    }

    private fun MutableList<String>.validateMetadata(evidence: Evidence) {
        if (evidence.metadata.declaredDelivery != evidence.delivery) {
            add("metadata delivery profile does not match the conversion delivery")
        }
        metadataStates(evidence.metadata).forEach { (name, state) ->
            if (state == Preservation.LOST) add("$name metadata was lost")
        }
        if (evidence.metadata.frameTiming != Preservation.PRESERVED) {
            add("frame timing metadata must be preserved")
        }
        if ((evidence.delivery == Delivery.NDR10_PLUS || evidence.delivery == Delivery.NIVUKX_VISION) &&
            evidence.metadata.hdrMastering != Preservation.PRESERVED
        ) {
            add("NDR/NIVUKX delivery requires preserved HDR mastering metadata")
        }
    }

    private fun metadataStates(metadata: MetadataEvidence): List<Pair<String, Preservation>> = listOf(
        "capture" to metadata.captureMetadata,
        "white balance" to metadata.whiteBalance,
        "exposure" to metadata.exposure,
        "orientation" to metadata.orientation,
        "detail profile" to metadata.detailProfile,
        "frame timing" to metadata.frameTiming,
        "color sidecar" to metadata.colorSidecar,
        "HDR mastering" to metadata.hdrMastering
    )

    private fun MutableList<String>.validateSignal(evidence: Evidence) {
        val signal = evidence.signal
        if (signal.sourceWidth <= 0 || signal.sourceHeight <= 0) add("source geometry must be positive")
        if (signal.outputWidth <= 0 || signal.outputHeight <= 0) add("output geometry must be positive")
        if (!signal.geometryPreserved()) add("conversion must preserve the exact source resolution")
        if (signal.sourceFps <= 0 || signal.outputFps <= 0) add("source and output frame rates must be positive")
        if (signal.sourceFps != signal.outputFps) add("conversion must preserve the exact source frame rate")

        val sourceFrames = signal.sourceFrameCount
        val outputFrames = signal.outputFrameCount
        if ((sourceFrames == null) != (outputFrames == null)) {
            add("source and output frame counts must be evidenced together")
        } else if (sourceFrames != null && outputFrames != null) {
            if (sourceFrames <= 0L || outputFrames <= 0L) add("evidenced frame counts must be positive")
            if (sourceFrames != outputFrames) add("conversion must preserve every source frame")
        }
    }

    private fun MutableList<String>.validateBitrate(evidence: Evidence) {
        val bitrate = evidence.bitrate
        if (evidence.outputTransport.compressed) {
            if (bitrate == null) {
                add("compressed output requires bitrate-floor evidence")
                return
            }
            if (bitrate.minimumRequiredBps <= 0L) add("minimum required bitrate must be positive")
            if (bitrate.configuredBps < bitrate.minimumRequiredBps) {
                add("configured bitrate is below the required quality floor")
            }
            bitrate.policyTargetBps?.let { policyTarget ->
                if (policyTarget < bitrate.minimumRequiredBps) {
                    add("policy target bitrate is below the minimum viable quality floor")
                }
            }
            bitrate.advertisedCeilingBps?.let { advertisedCeiling ->
                if (advertisedCeiling <= 0L) add("advertised codec bitrate ceiling must be positive")
                if (bitrate.configuredBps > advertisedCeiling) {
                    add("configured bitrate exceeds the advertised codec ceiling")
                }
            }
            if (bitrate.capabilityBound != null) {
                val policyTarget = bitrate.policyTargetBps
                val advertisedCeiling = bitrate.advertisedCeilingBps
                if (policyTarget == null || advertisedCeiling == null) {
                    add("capability-bound bitrate evidence requires policy target and advertised ceiling")
                } else {
                    val expectedCapabilityBound = bitrate.configuredBps < policyTarget
                    if (bitrate.capabilityBound != expectedCapabilityBound) {
                        add("capability-bound bitrate evidence is inconsistent with configured and policy rates")
                    }
                    if (bitrate.capabilityBound && bitrate.configuredBps != advertisedCeiling) {
                        add("capability-bound bitrate must saturate the exact advertised codec ceiling")
                    }
                }
            }
            bitrate.measuredBps?.let { measured ->
                if (measured < bitrate.minimumRequiredBps) {
                    add("measured output bitrate is below the required quality floor")
                }
            }
        } else if (bitrate != null) {
            add("uncompressed/RAW output must not claim an encoded bitrate contract")
        }
    }

    private fun MutableList<String>.validateAdvancedEncoderControls(evidence: Evidence) {
        if (evidence.outputTransport.compressed) {
            if (evidence.advancedEncoderControlsAccepted != true) {
                add("compressed output requires accepted elite encoder quality controls")
            }
        } else if (evidence.advancedEncoderControlsAccepted != null) {
            add("uncompressed/RAW output must not claim encoder quality-control acceptance")
        }
    }

    private fun CarrierColorTags.asMetadataValue(): String =
        "${primaries.name}/${transfer.name}/${matrix.name}/${range.name}"

    private fun SignalEvidence.geometryPreserved(): Boolean =
        sourceWidth == outputWidth && sourceHeight == outputHeight

    private fun SignalEvidence.frameCountPreserved(): Boolean =
        sourceFrameCount == null && outputFrameCount == null || sourceFrameCount == outputFrameCount

    private fun Evidence.bitrateFloorSatisfied(): Boolean {
        if (!outputTransport.compressed) return bitrate == null
        val evidence = bitrate ?: return false
        if (evidence.minimumRequiredBps <= 0L || evidence.configuredBps < evidence.minimumRequiredBps) {
            return false
        }
        return evidence.measuredBps?.let { it >= evidence.minimumRequiredBps } ?: true
    }
}
