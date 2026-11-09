package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.BitrateEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierColorTags
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierPrimaries
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierTransfer
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.ColorEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CompanionColorTransform
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Delivery
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Evidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Matrix
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.MetadataEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.OutputTransport
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Preservation
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Range
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SampleType
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SemanticColor
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalGamut
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalTransfer
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SourceDomain
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.TransformEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.WorkingPipelineEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.WorkingSpace
import java.io.File

/**
 * Converts facts from the two production conversion paths into the shared fail-closed contract.
 *
 * This adapter contains no image processing and cannot select a lower-quality route. Its only side effect
 * is rejection when the route that actually ran disagrees with the declared 10-bit/color/metadata contract.
 */
internal object EliteConversionQualityEvidenceFactory {

    fun writeCertificate(anchorPath: String, audit: EliteConversionQualityContract.Audit): File {
        val output = File("$anchorPath.elite-conversion-certificate.txt")
        output.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "Failed to create elite conversion certificate directory"
            }
        }
        output.writeText(
            buildString {
                appendLine("NeoProCamX Elite Conversion Quality Certificate")
                audit.toMetadata().toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
            }
        )
        return output
    }

    fun enforceRawDevelop(
        meta: RawDevelopMetadata,
        options: RawToNeoLogMovConverter.Options,
        framesConverted: Long,
        containerColor: QuickTimeColorAtomConfig,
        companionLutPresent: Boolean,
        minimumBitrateBps: Long? = null,
        configuredBitrateBps: Long? = null,
        policyTargetBitrateBps: Long? = null,
        advertisedBitrateCeilingBps: Long? = null,
        encoderCapabilityBound: Boolean? = null,
        advancedEncoderControlsAccepted: Boolean? = null
    ): EliteConversionQualityContract.Audit {
        val delivery = when (options.deliveryProfile) {
            RawToNeoLogMovConverter.DeliveryProfile.NEOLOG -> Delivery.NEOLOG
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS -> Delivery.NDR10_PLUS
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION -> Delivery.NIVUKX_VISION
        }
        val transport = when (options.format) {
            RawToNeoLogMovConverter.OutputFormat.MASTER_444 -> OutputTransport.R210_RGB_444
            RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 -> OutputTransport.V210_YUV_422
            RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420 -> OutputTransport.HEVC_MAIN10_420
        }
        val expectedCarrier = rawExpectedCarrier(delivery, transport)
        val isNeoLog = delivery == Delivery.NEOLOG
        return EliteConversionQualityContract.enforce(
            Evidence(
                delivery = delivery,
                sourceDomain = SourceDomain.RAW_SENSOR,
                sourceBitDepth = meta.storageBitDepth,
                outputBitDepth = 10,
                outputTransport = transport,
                workingPipeline = WorkingPipelineEvidence(
                    space = WorkingSpace.LINEAR_ACESCG,
                    sampleType = SampleType.FLOAT,
                    precisionBits = Float.SIZE_BITS,
                    signalIsLinear = true
                ),
                transforms = TransformEvidence(
                    toneMapApplications = if (isNeoLog) 0 else 1,
                    outputTransferApplications = 1,
                    finalQuantizationApplications = 1
                ),
                color = ColorEvidence(
                    semantic = when (delivery) {
                        Delivery.NEOLOG -> SemanticColor(SignalGamut.ACES_AP1, SignalTransfer.NEOLOG)
                        Delivery.NDR10_PLUS,
                        Delivery.NIVUKX_VISION -> SemanticColor(SignalGamut.BT2020, SignalTransfer.ST2084_PQ)
                        else -> error("Unsupported RAW developed delivery $delivery")
                    },
                    expectedCarrier = expectedCarrier,
                    containerCarrier = carrierFromContainer(containerColor),
                    sidecarCarrier = null,
                    companionDeclaration = if (isNeoLog && companionLutPresent) {
                        Preservation.PRESERVED
                    } else if (isNeoLog) {
                        Preservation.LOST
                    } else {
                        Preservation.NOT_PRESENT
                    },
                    companionColorTransform = if (isNeoLog && companionLutPresent) {
                        CompanionColorTransform.NEOLOG_ACES_AP1_TO_REC709_LUT
                    } else {
                        null
                    }
                ),
                metadata = MetadataEvidence(
                    declaredDelivery = delivery,
                    captureMetadata = Preservation.PRESERVED,
                    whiteBalance = Preservation.PRESERVED,
                    exposure = Preservation.PRESERVED,
                    orientation = Preservation.PRESERVED,
                    detailProfile = Preservation.NOT_PRESENT,
                    frameTiming = Preservation.PRESERVED,
                    colorSidecar = Preservation.NOT_PRESENT,
                    hdrMastering = if (isNeoLog) {
                        Preservation.NOT_PRESENT
                    } else if (containerColor.masteringDisplay != null) {
                        Preservation.PRESERVED
                    } else {
                        Preservation.LOST
                    }
                ),
                signal = SignalEvidence(
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    outputWidth = meta.width,
                    outputHeight = meta.height,
                    sourceFps = meta.fps,
                    outputFps = meta.fps,
                    sourceFrameCount = framesConverted,
                    outputFrameCount = framesConverted
                ),
                bitrate = bitrateEvidence(
                    transport = transport,
                    minimumBitrateBps = minimumBitrateBps,
                    configuredBitrateBps = configuredBitrateBps,
                    policyTargetBitrateBps = policyTargetBitrateBps,
                    advertisedBitrateCeilingBps = advertisedBitrateCeilingBps,
                    encoderCapabilityBound = encoderCapabilityBound
                ),
                advancedEncoderControlsAccepted = advancedEncoderControlsAccepted,
                usedEightBitFallback = false,
                qualityReducedForSpeed = false,
                destructiveFallbackUsed = false
            )
        )
    }

    fun enforceRecordDevelop(
        sourceKind: RecordDevelopMasterKind,
        target: DevelopTarget,
        sourceBitDepth: Int,
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Long,
        deliveryFormat: RecordDevelopDeliveryFormat,
        containerColor: QuickTimeColorAtomConfig,
        sourceSidecar: VideoDevelopColorSidecar.VideoDevelopColorMetadata?,
        minimumBitrateBps: Long? = null,
        configuredBitrateBps: Long? = null,
        policyTargetBitrateBps: Long? = null,
        advertisedBitrateCeilingBps: Long? = null,
        encoderCapabilityBound: Boolean? = null,
        advancedEncoderControlsAccepted: Boolean? = null
    ): EliteConversionQualityContract.Audit {
        val delivery = if (target == DevelopTarget.NDR_PQ_BT2020 || sourceKind == RecordDevelopMasterKind.NDR) {
            Delivery.NDR10_PLUS
        } else {
            // VIDEO and PRO share this exact record-develop route; no mode-specific processing is bypassed.
            Delivery.VIDEO
        }
        val transport = when (deliveryFormat) {
            RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV -> OutputTransport.R210_RGB_444
            RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV -> OutputTransport.V210_YUV_422
            RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV -> OutputTransport.HEVC_MAIN10_420
            RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV -> OutputTransport.HEVC_MAIN10_422
        }
        val containerCarrier = carrierFromContainer(containerColor)
        val semantic = SemanticColor(
            gamut = when (containerColor.colorPrimaries) {
                QuickTimeColorAtomPolicy.PRIMARIES_BT2020 -> SignalGamut.BT2020
                else -> SignalGamut.BT709
            },
            transfer = if (delivery == Delivery.NDR10_PLUS) SignalTransfer.ST2084_PQ else SignalTransfer.SDR
        )
        return EliteConversionQualityContract.enforce(
            Evidence(
                delivery = delivery,
                sourceDomain = SourceDomain.P010_VIDEO,
                sourceBitDepth = sourceBitDepth,
                outputBitDepth = 10,
                outputTransport = transport,
                workingPipeline = WorkingPipelineEvidence(
                    space = WorkingSpace.LINEAR_RGB,
                    sampleType = SampleType.FLOAT,
                    precisionBits = Float.SIZE_BITS,
                    signalIsLinear = true
                ),
                transforms = TransformEvidence(
                    toneMapApplications = if (delivery == Delivery.NDR10_PLUS) 1 else 0,
                    outputTransferApplications = 1,
                    finalQuantizationApplications = 1
                ),
                color = ColorEvidence(
                    semantic = semantic,
                    expectedCarrier = containerCarrier,
                    containerCarrier = containerCarrier,
                    sidecarCarrier = null,
                    companionDeclaration = Preservation.NOT_PRESENT,
                    companionColorTransform = null
                ),
                metadata = MetadataEvidence(
                    declaredDelivery = delivery,
                    captureMetadata = presentWhen(sourceSidecar != null),
                    whiteBalance = presentWhen(sourceSidecar != null),
                    exposure = presentWhen(sourceSidecar != null),
                    orientation = presentWhen(sourceSidecar != null),
                    detailProfile = presentWhen(!sourceSidecar?.detailProfileTag.isNullOrBlank()),
                    frameTiming = Preservation.PRESERVED,
                    colorSidecar = Preservation.NOT_PRESENT,
                    hdrMastering = if (delivery == Delivery.NDR10_PLUS) {
                        presentWhen(containerColor.masteringDisplay != null, missingMeansLost = true)
                    } else {
                        Preservation.NOT_PRESENT
                    }
                ),
                signal = SignalEvidence(
                    sourceWidth = width,
                    sourceHeight = height,
                    outputWidth = width,
                    outputHeight = height,
                    sourceFps = fps,
                    outputFps = fps,
                    sourceFrameCount = frameCount,
                    outputFrameCount = frameCount
                ),
                bitrate = bitrateEvidence(
                    transport = transport,
                    minimumBitrateBps = minimumBitrateBps,
                    configuredBitrateBps = configuredBitrateBps,
                    policyTargetBitrateBps = policyTargetBitrateBps,
                    advertisedBitrateCeilingBps = advertisedBitrateCeilingBps,
                    encoderCapabilityBound = encoderCapabilityBound
                ),
                advancedEncoderControlsAccepted = advancedEncoderControlsAccepted,
                usedEightBitFallback = false,
                qualityReducedForSpeed = false,
                destructiveFallbackUsed = false
            )
        )
    }

    private fun rawExpectedCarrier(delivery: Delivery, transport: OutputTransport): CarrierColorTags {
        val rgb = transport == OutputTransport.R210_RGB_444
        return CarrierColorTags(
            primaries = CarrierPrimaries.BT2020,
            transfer = if (delivery == Delivery.NEOLOG) CarrierTransfer.BT709 else CarrierTransfer.ST2084_PQ,
            matrix = when {
                rgb && delivery == Delivery.NEOLOG -> Matrix.BT709_CARRIER
                rgb -> Matrix.RGB_IDENTITY
                else -> Matrix.BT2020_NCL
            },
            range = if (rgb) Range.FULL else Range.LIMITED
        )
    }

    private fun carrierFromContainer(color: QuickTimeColorAtomConfig): CarrierColorTags = CarrierColorTags(
        primaries = when (color.colorPrimaries) {
            QuickTimeColorAtomPolicy.PRIMARIES_BT2020 -> CarrierPrimaries.BT2020
            QuickTimeColorAtomPolicy.PRIMARIES_BT709 -> CarrierPrimaries.BT709
            else -> CarrierPrimaries.SENSOR_NATIVE
        },
        transfer = when (color.transferCharacteristics) {
            QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084 -> CarrierTransfer.ST2084_PQ
            QuickTimeColorAtomPolicy.TRANSFER_BT709 -> CarrierTransfer.BT709
            else -> CarrierTransfer.SENSOR_LINEAR
        },
        matrix = when (color.matrixCoefficients) {
            QuickTimeColorAtomPolicy.MATRIX_RGB_IDENTITY -> Matrix.RGB_IDENTITY
            QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL ->
                if (color.fullRange) Matrix.BT2020_CARRIER else Matrix.BT2020_NCL
            QuickTimeColorAtomPolicy.MATRIX_BT709 ->
                if (color.fullRange) Matrix.BT709_CARRIER else Matrix.BT709_NCL
            else -> Matrix.SENSOR_NATIVE
        },
        range = if (color.fullRange) Range.FULL else Range.LIMITED
    )

    private fun bitrateEvidence(
        transport: OutputTransport,
        minimumBitrateBps: Long?,
        configuredBitrateBps: Long?,
        policyTargetBitrateBps: Long?,
        advertisedBitrateCeilingBps: Long?,
        encoderCapabilityBound: Boolean?
    ): BitrateEvidence? {
        if (!transport.compressed) return null
        if (minimumBitrateBps == null || configuredBitrateBps == null) return null
        return BitrateEvidence(
            minimumRequiredBps = minimumBitrateBps,
            configuredBps = configuredBitrateBps,
            policyTargetBps = policyTargetBitrateBps,
            advertisedCeilingBps = advertisedBitrateCeilingBps,
            capabilityBound = encoderCapabilityBound
        )
    }

    private fun presentWhen(value: Boolean, missingMeansLost: Boolean = false): Preservation = when {
        value -> Preservation.PRESERVED
        missingMeansLost -> Preservation.LOST
        else -> Preservation.NOT_PRESENT
    }
}
