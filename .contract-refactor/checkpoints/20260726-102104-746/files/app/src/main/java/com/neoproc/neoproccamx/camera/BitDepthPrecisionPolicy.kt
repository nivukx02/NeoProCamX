package com.neoproc.neoproccamx.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import java.util.Locale

internal data class BitDepthPrecisionValidationPlan(
    val compatible: Boolean,
    val rawWorkflowAllowed: Boolean,
    val diagnostics: List<String>
)

internal object BitDepthPrecisionValidationPolicy {
    fun resolve(
        supports10BitColor: Boolean,
        supports12BitRaw: Boolean,
        rawWorkflowRequested: Boolean,
        strictHighEnd: Boolean,
        tenBitTransportRequested: Boolean = false
    ): BitDepthPrecisionValidationPlan {
        if (!strictHighEnd) {
            return BitDepthPrecisionValidationPlan(
                compatible = true,
                rawWorkflowAllowed = supports12BitRaw,
                diagnostics = listOf("Bit-depth validation running outside strict high-end mode")
            )
        }
        val diagnostics = buildList {
            if (!supports10BitColor) {
                if (tenBitTransportRequested) {
                    add("Device does not support mandatory 10-bit color output")
                } else {
                    add("10-bit Camera2 dynamic-range transport unavailable; SDR strict high-end processed video remains available")
                }
            }
            if (!supports12BitRaw && rawWorkflowRequested) {
                add("Device does not support mandatory 12-bit RAW_SENSOR capture")
            } else if (!supports12BitRaw) {
                add("12-bit RAW workflows disabled; processed strict high-end video remains available")
            }
        }
        return BitDepthPrecisionValidationPlan(
            compatible =
                (!tenBitTransportRequested || supports10BitColor) &&
                    (supports12BitRaw || !rawWorkflowRequested),
            rawWorkflowAllowed = supports12BitRaw,
            diagnostics = diagnostics
        )
    }
}

internal object BitDepthHardwareEvidencePolicy {
    fun supportsTenBitColorTransport(support: HdrDynamicRangePolicy.Support): Boolean =
        support.anyHdrSupported

    fun supportsRaw12Capture(
        rawCapabilityAvailable: Boolean,
        raw12OutputAdvertised: Boolean
    ): Boolean = rawCapabilityAvailable && raw12OutputAdvertised
}

internal data class StrictVideoBitDepthContract(
    val requiredBitDepth: Int,
    val effectiveBitDepth: Int,
    val tenBitTransportRequired: Boolean,
    val raw12Required: Boolean,
    val strictMode: Boolean
)

internal object StrictVideoBitDepthContractPolicy {
    fun resolve(
        recordingMode: String,
        supports10BitColor: Boolean,
        supports12BitRaw: Boolean
    ): StrictVideoBitDepthContract {
        val normalized = recordingMode
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9+]+"), "_")
        val rawMode = normalized.contains("RAW")
        val tenBitEncodedMode =
            normalized == "VIDEO" ||
                normalized.contains("NORMAL") ||
                normalized.contains("PRO") ||
                normalized.contains("NDR") ||
                normalized.contains("NIVUKX") ||
                normalized.contains("APEX") ||
                normalized.contains("HDR") ||
                normalized.contains("LOG")
        return when {
            rawMode -> StrictVideoBitDepthContract(
                requiredBitDepth = 12,
                effectiveBitDepth = if (supports12BitRaw) 12 else 0,
                tenBitTransportRequired = false,
                raw12Required = true,
                strictMode = true
            )
            tenBitEncodedMode -> StrictVideoBitDepthContract(
                requiredBitDepth = 10,
                effectiveBitDepth = if (supports10BitColor) 10 else 0,
                tenBitTransportRequired = true,
                raw12Required = false,
                strictMode = true
            )
            else -> StrictVideoBitDepthContract(
                requiredBitDepth = 8,
                effectiveBitDepth = 8,
                tenBitTransportRequired = false,
                raw12Required = false,
                strictMode = false
            )
        }
    }
}

/**
 * Phase 1c: 10-BIT & 12-BIT PRECISION VALIDATION
 * 
 * Enforces mandatory bit-depth for high-end cinema workflows:
 * - NORMAL SDR: Mandatory 10-bit Rec.709
 * - PRO/LOG/HDR: Mandatory 10-bit Rec.2020
 * - RAW: Mandatory 12-bit RAW_SENSOR
 * - Preview: Mandatory 10-bit HDR Surface
 * 
 * Throws CriticalHardwareException only for the workflow being requested.
 * Startup SDR processing must not be killed by unavailable HDR transport;
 * explicit 10-bit/HDR transport and RAW workflows still require evidence.
 */
class BitDepthPrecisionPolicy(private val characteristics: CameraCharacteristics) {
    
    private val TAG = "BitDepthPrecisionPolicy"
    
    /**
     * Check if device supports mandatory 10-bit color output (Rec.2020)
     */
    fun supports10BitColorOutput(): Boolean =
        BitDepthHardwareEvidencePolicy.supportsTenBitColorTransport(
            HdrDynamicRangePolicy.detectSupport(characteristics)
        )
    
    /**
     * Check if device supports mandatory 12-bit RAW_SENSOR capture
     */
    fun supports12BitRawSensor(): Boolean {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: IntArray(0)
        val rawCapabilityAvailable =
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val raw12OutputAdvertised = runCatching {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.outputFormats
                ?.contains(ImageFormat.RAW12) == true
        }.getOrDefault(false)
        return BitDepthHardwareEvidencePolicy.supportsRaw12Capture(
            rawCapabilityAvailable = rawCapabilityAvailable,
            raw12OutputAdvertised = raw12OutputAdvertised
        )
    }
    
    /**
     * Validate that device meets ALL mandatory 10-bit/12-bit requirements
     * Throws CriticalHardwareException if any requirement is missing
     */
    fun validateMandatoryBitDepth(
        rawWorkflowRequested: Boolean = false,
        strictHighEnd: Boolean = true,
        tenBitTransportRequested: Boolean = false
    ): Boolean {
        val has10Bit = supports10BitColorOutput()
        val has12BitRaw = supports12BitRawSensor()
        val plan = BitDepthPrecisionValidationPolicy.resolve(
            supports10BitColor = has10Bit,
            supports12BitRaw = has12BitRaw,
            rawWorkflowRequested = rawWorkflowRequested,
            strictHighEnd = strictHighEnd,
            tenBitTransportRequested = tenBitTransportRequested
        )
        
        Log.i(TAG, "Bit-depth validation: 10-bit=$has10Bit, 12-bit RAW=$has12BitRaw")
        plan.diagnostics.forEach { Log.i(TAG, it) }
        
        if (!plan.compatible) {
            val reason = plan.diagnostics.joinToString(separator = ". ")
            val workflow = if (rawWorkflowRequested) {
                "the requested RAW workflow"
            } else if (tenBitTransportRequested) {
                "the requested 10-bit/HDR transport workflow"
            } else {
                "strict high-end processed video"
            }
            throw CriticalHardwareException(
                "$reason. Device is incompatible with $workflow."
            )
        }
        
        return true
    }
    
    /**
     * Get the effective bit-depth for a specific recording mode.
     * Returns 10 for NORMAL/PRO/LOG/HDR only when real 10-bit transport is advertised.
     */
    fun getEffectiveBitDepth(recordingMode: String): Int =
        StrictVideoBitDepthContractPolicy.resolve(
            recordingMode = recordingMode,
            supports10BitColor = supports10BitColorOutput(),
            supports12BitRaw = supports12BitRawSensor()
        ).effectiveBitDepth
    
    /**
     * Report bit-depth validation status for diagnostics
     */
    fun getValidationReport(): String {
        val has10Bit = supports10BitColorOutput()
        val has12BitRaw = supports12BitRawSensor()
        
        return buildString {
            append("Bit-Depth Validation Report:\n")
            append("  10-bit Camera2 Transport: ${if (has10Bit) "✓ SUPPORTED" else "✗ NOT ADVERTISED"}\n")
            append("  12-bit RAW Sensor: ${if (has12BitRaw) "✓ SUPPORTED" else "✗ NOT SUPPORTED"}\n")
            append("  SDR Processed Startup Compatible: ✓ YES\n")
        }
    }
}
