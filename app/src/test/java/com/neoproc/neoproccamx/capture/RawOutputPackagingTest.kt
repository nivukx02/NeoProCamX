package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class RawOutputPackagingTest {

    @Test
    fun metadataDescribesUnifiedNativeRawPackage() {
        val metadata = RawOutputPackaging.buildMetadata(
            RawPackageMetadata(
                sourceType = "native_raw_capture",
                workflow = "NATIVE_RAW_COMPANION",
                captureTag = "RAW_DNG",
                nativeRawSupported = true,
                nativeDngEmbedded = true,
                includesSourceJpeg = false,
                includesReferenceJpeg = false,
                lensFacing = "BACK",
                sensorOrientation = 90,
                jpegOrientation = 90,
                outputAspect = 4f / 3f,
                sceneMetadata = "CamX mode=RAW strategy=RAW_SINGLE",
                colorProfile = "Natural",
                logEnabled = false,
                lutEnabled = false,
                lutLabel = "OFF",
                iso = 100,
                exposureNs = 10_000_000L,
                focusDistance = 0.5f,
                timestampMs = 1234L
            )
        )

        assertTrue(metadata.contains("format=CAMX_RAW_PACKAGE_V4_CINEMA"))
        assertTrue(metadata.contains("nativeDngEmbedded=true"))
        assertTrue(metadata.contains("sourceType=native_raw_capture"))
        assertTrue(metadata.contains("workflow=NATIVE_RAW_COMPANION"))
        assertTrue(metadata.contains("captureTag=RAW_DNG"))
    }

    @Test
    fun metadataDescribesSoftwareRawFallbackPackage() {
        val metadata = RawOutputPackaging.buildMetadata(
            RawPackageMetadata(
                sourceType = "single_jpeg_fallback",
                workflow = "SOFTWARE_RAW_FALLBACK",
                captureTag = "RAW_PKG",
                nativeRawSupported = false,
                nativeDngEmbedded = false,
                includesSourceJpeg = true,
                includesReferenceJpeg = false,
                lensFacing = "FRONT",
                sensorOrientation = 270,
                jpegOrientation = 270,
                outputAspect = 16f / 9f,
                sceneMetadata = "CamX mode=RAW_SOFTWARE strategy=RAW_SINGLE",
                colorProfile = "Sony S-Log3",
                logEnabled = true,
                lutEnabled = true,
                lutLabel = "Neutral Film",
                iso = 400,
                exposureNs = 20_000_000L,
                focusDistance = 1.0f,
                timestampMs = 5678L
            )
        )

        assertTrue(metadata.contains("nativeDngEmbedded=false"))
        assertTrue(metadata.contains("includesSourceJpeg=true"))
        assertTrue(metadata.contains("lensFacing=FRONT"))
        assertTrue(metadata.contains("colorProfile=Sony S-Log3"))
        assertTrue(metadata.contains("lutLabel=Neutral Film"))
    }

    @Test
    fun metadataRecordsDistortionCorrectionGeometryPolicy() {
        val metadata = RawOutputPackaging.buildMetadata(
            RawPackageMetadata(
                sourceType = "native_raw_capture",
                workflow = "NATIVE_RAW_COMPANION",
                captureTag = "RAW_DNG",
                nativeRawSupported = true,
                nativeDngEmbedded = true,
                includesSourceJpeg = false,
                includesReferenceJpeg = false,
                lensFacing = "BACK",
                sensorOrientation = 90,
                jpegOrientation = 90,
                outputAspect = 4f / 3f,
                sceneMetadata = "CamX mode=RAW",
                colorProfile = "Natural",
                logEnabled = false,
                lutEnabled = false,
                lutLabel = "OFF",
                iso = 100,
                exposureNs = 10_000_000L,
                focusDistance = 0.5f,
                timestampMs = 1234L,
                distortionPolicy = "SENSOR_NATIVE",
                ispBypassVerified = "PROVEN"
            )
        )

        assertTrue(metadata.contains("distortionPolicy=SENSOR_NATIVE"))
        assertTrue(metadata.contains("ispBypassVerified=PROVEN"))
    }

    @Test
    fun metadataDistortionPolicyDefaultsToUnknown() {
        val metadata = RawOutputPackaging.buildMetadata(
            RawPackageMetadata(
                sourceType = "single_jpeg_fallback",
                workflow = "SOFTWARE_RAW_FALLBACK",
                captureTag = "RAW_PKG",
                nativeRawSupported = false,
                nativeDngEmbedded = false,
                includesSourceJpeg = true,
                includesReferenceJpeg = false,
                lensFacing = "BACK",
                sensorOrientation = 90,
                jpegOrientation = 90,
                outputAspect = 4f / 3f,
                sceneMetadata = "CamX mode=RAW_SOFTWARE",
                colorProfile = "Natural",
                logEnabled = false,
                lutEnabled = false,
                lutLabel = "OFF",
                iso = 100,
                exposureNs = 1_000_000L,
                focusDistance = 1.0f,
                timestampMs = 1L
            )
        )

        assertTrue(metadata.contains("distortionPolicy=UNKNOWN"))
        assertTrue(metadata.contains("ispBypassVerified=NOT_APPLICABLE"))
    }

    @Test
    fun metadataIncludesStrictHighEndRuntimeFlag() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val metadata = RawOutputPackaging.buildMetadata(
                RawPackageMetadata(
                    sourceType = "single_jpeg_fallback",
                    workflow = "SOFTWARE_RAW_FALLBACK",
                    captureTag = "RAW_PKG",
                    nativeRawSupported = false,
                    nativeDngEmbedded = false,
                    includesSourceJpeg = true,
                    includesReferenceJpeg = false,
                    lensFacing = "BACK",
                    sensorOrientation = 90,
                    jpegOrientation = 90,
                    outputAspect = 4f / 3f,
                    sceneMetadata = "CamX mode=RAW_SOFTWARE",
                    colorProfile = "Natural",
                    logEnabled = false,
                    lutEnabled = false,
                    lutLabel = "OFF",
                    iso = 100,
                    exposureNs = 1_000_000L,
                    focusDistance = 1.0f,
                    timestampMs = 1L
                )
            )

            assertTrue(metadata.contains("strictHighEnd=true"))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
