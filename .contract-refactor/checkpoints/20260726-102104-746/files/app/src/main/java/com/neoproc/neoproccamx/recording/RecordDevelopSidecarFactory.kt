package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import kotlin.math.PI

/**
 * Assembles a [VideoDevelopColorSidecar.VideoDevelopColorMetadata] from the capture-time facts available at
 * record-stop for the record-then-develop LIGHT lane. Pure (no Android deps) so it is unit-testable and safe
 * to call from any thread; the caller (CameraViewModel stop-site) unpacks its Android/camera state into these
 * plain parameters.
 *
 * Colour-model choices:
 *  - Working matrix is chosen by the intermediate's colour SPACE (standard primaries → XYZ), NOT the sensor
 *    CCM: Rec.709 → [SnceIlluminantEstimator.REC709_TO_XYZ], Rec.2020 → [SnceIlluminantEstimator.REC2020_TO_XYZ].
 *  - Transfer: SDR when HDR is off. For an HDR mode it is PQ when the capture actually negotiated PQ
 *    (HDR10/HDR10_PLUS — [captureTransferIsPq], driven by the same NdrPqDecodeFlag the LIVE tone operator
 *    uses to PQ-decode the camera input), otherwise HLG. #4 fix: the flat LIGHT intermediate stores the raw
 *    camera code values un-tone-mapped, so on a PQ-capturing device those codes are PQ; hard-coding HLG here
 *    made the offline develop decode PQ-as-HLG → the multi-hue red-cast + crushed highlights that
 *    NdrPqDecodeFlag fixed for preview. Tying this to the same flag keeps the developed master WYSIWYG with
 *    the monitor and reversible together (debug.ndr_pq_decode).
 *  - Adapting luminance is derived from the scene lux estimate with the same lux→L_A convention the live colour
 *    controller uses (lux / π, clamped 1..1000). 0 ⇒ absent ⇒ the develop pass uses its default scene L_A.
 */
object RecordDevelopSidecarFactory {

    fun build(
        width: Int,
        height: Int,
        fps: Int,
        storageBitDepth: Int,
        /** e.g. "Rec.709" / "Rec.2020". */
        colorSpaceLabel: String,
        /** e.g. "OFF" / "NDR10+" / "NIVUKXVISION". */
        hdrModeLabel: String,
        colorProfile: String,
        detailProfile: String = "",
        wbR: Float = 1f,
        wbGr: Float = 1f,
        wbGb: Float = 1f,
        wbB: Float = 1f,
        kelvin: Int? = null,
        tint: Int? = null,
        estimatedLux: Float = 0f,
        /** #4: true when the HDR capture negotiated PQ (HDR10/HDR10_PLUS) — pass NdrPqDecodeFlag.enabled. */
        captureTransferIsPq: Boolean = false,
        orientationHintDegrees: Int = 0
    ): VideoDevelopColorSidecar.VideoDevelopColorMetadata {
        val space = if (colorSpaceLabel.contains("2020", ignoreCase = true)) {
            VideoDevelopColorSidecar.DevelopColorSpace.REC2020
        } else {
            VideoDevelopColorSidecar.DevelopColorSpace.REC709
        }
        val workingRgbToXyz = if (space == VideoDevelopColorSidecar.DevelopColorSpace.REC2020) {
            SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf()
        } else {
            SnceIlluminantEstimator.REC709_TO_XYZ.copyOf()
        }
        // LIGHT lane: SDR when HDR is off; for an HDR mode, PQ when the capture actually negotiated PQ (the
        // flat intermediate holds the raw camera codes un-tone-mapped), else HLG. #4 fix.
        val transfer = when {
            hdrModeLabel.isBlank() || hdrModeLabel.trim().equals("OFF", ignoreCase = true) ->
                VideoDevelopColorSidecar.DevelopColorTransfer.SDR
            captureTransferIsPq -> VideoDevelopColorSidecar.DevelopColorTransfer.PQ
            else -> VideoDevelopColorSidecar.DevelopColorTransfer.HLG
        }
        val adaptingLuminance = if (estimatedLux > 0f) {
            (estimatedLux / PI.toFloat()).coerceIn(1f, 1000f)
        } else {
            0f
        }
        val tag = "$colorSpaceLabel $colorProfile".trim().ifBlank { colorProfile }
        return VideoDevelopColorSidecar.VideoDevelopColorMetadata(
            width = width,
            height = height,
            storageBitDepth = storageBitDepth,
            fps = fps,
            whiteBalanceR = wbR,
            whiteBalanceGr = wbGr,
            whiteBalanceGb = wbGb,
            whiteBalanceB = wbB,
            workingRgbToXyz = workingRgbToXyz,
            adaptingLuminance = adaptingLuminance,
            cctKelvin = kelvin?.toFloat() ?: 5500f,
            tint = tint?.toFloat() ?: 0f,
            colorSpace = space,
            colorTransfer = transfer,
            colorProfileTag = tag,
            detailProfileTag = detailProfile,
            orientationHintDegrees = orientationHintDegrees
        )
    }
}
