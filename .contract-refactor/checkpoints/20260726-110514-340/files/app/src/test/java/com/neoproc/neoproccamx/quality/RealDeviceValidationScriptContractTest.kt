package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealDeviceValidationScriptContractTest {

    @Test
    fun singularityStage4ToolsExposeCanonicalGatesScenesAndFailClosedDeviceContracts() {
        fun qualityFile(name: String): File =
            listOf(
                File("tools/quality/$name"),
                File("../tools/quality/$name")
            ).first { it.isFile }

        val release = qualityFile("run-singularity-release-gate.ps1").readText()
        val scene = qualityFile("run-singularity-scene-matrix.ps1").readText()
        val endurance = qualityFile("run-singularity-endurance.ps1").readText()
        val performance = qualityFile("run-singularity-performance-gate.ps1").readText()
        val deviceLab = qualityFile("run-singularity-device-lab.ps1").readText()
        val sceneManifest = qualityFile("singularity-scenes.json").readText()

        (1..16).forEach { id ->
            val gateId = "QG${id.toString().padStart(2, '0')}"
            assertTrue(release.contains(gateId))
            assertTrue(scene.contains(gateId))
            assertTrue(sceneManifest.contains("\"$gateId\""))
        }
        assertTrue(release.contains("singularity-release-gate-report.json"))
        assertTrue(release.contains("SMF-SINGULARITY-1"))
        assertTrue(release.contains("\$notPassed.Count -eq 0"))
        assertTrue(release.contains("DeviceLabReportPath"))
        assertTrue(release.contains("SceneMatrixReportPath"))
        assertTrue(release.contains("EnduranceReportPath"))
        assertTrue(release.contains("PerformanceReportPath"))
        assertTrue(release.contains("SmfPath"))
        assertTrue(release.contains("Stage6NotPassed"))
        assertTrue(release.contains("LargeScreenReview"))
        assertTrue(release.contains("NativePath"))
        assertTrue(scene.contains("singularity-scene-matrix-report.json"))
        assertTrue(scene.contains("UnknownGateCoverageTargets"))
        assertTrue(scene.contains("MissingArtifactOutputs"))
        assertTrue(scene.contains("NOT_TESTED_ARTIFACTS_REQUIRED"))
        assertTrue(sceneManifest.contains("\"daylight face\""))
        assertTrue(sceneManifest.contains("\"moving texture target\""))
        assertTrue(sceneManifest.contains("\"20-minute thermal stress\""))
        assertTrue(sceneManifest.contains("\"NORMAL-SDR-SINGULARITY\""))
        assertTrue(sceneManifest.contains("\"PRO-SDR-SINGULARITY\""))
        assertTrue(sceneManifest.contains("\"requiredReviewSurfaces\""))
        assertTrue(sceneManifest.contains("\"artifactRequirements\""))
        assertTrue(endurance.contains("NOT_TESTED_DEVICE_REQUIRED"))
        assertTrue(endurance.contains("RequiredStartStopCycles"))
        assertTrue(endurance.contains("EvidenceJsonPath"))
        assertTrue(endurance.contains("ParameterFailures"))
        assertTrue(performance.contains("dumpsys gfxinfo"))
        assertTrue(performance.contains("top -b -n 1"))
        assertTrue(performance.contains("EvidenceJsonPath"))
        assertTrue(performance.contains("Stage6NativePathStatus"))
        assertTrue(deviceLab.contains("run-singularity-release-gate.ps1"))
        assertTrue(deviceLab.contains("run-singularity-scene-matrix.ps1"))
        assertTrue(deviceLab.contains("run-singularity-endurance.ps1"))
        assertTrue(deviceLab.contains("run-singularity-performance-gate.ps1"))
        assertTrue(deviceLab.contains("qg-results.json"))
        assertTrue(deviceLab.contains("Stage6ReportStatuses"))
        assertTrue(deviceLab.contains("LargeScreenReviewStatus"))
        assertTrue(deviceLab.contains("NativePathStatus"))
        assertTrue(deviceLab.contains("NOT_TESTED_DEVICE_REQUIRED"))
    }

    @Test
    fun v16ScriptCollectsGpuZeroCopyAndPerformanceEvidenceForRuntimeClaims() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("Get-PerformanceEvidence"))
        assertTrue(source.contains("dumpsys gfxinfo"))
        assertTrue(source.contains("top -b -n 1"))
        assertTrue(source.contains("GpuProcessingPath"))
        assertTrue(source.contains("ZeroCopyEvidencePath"))
        assertTrue(source.contains("PerformanceTelemetryValidated"))
        assertTrue(source.contains("CpuUsagePercent"))
        assertTrue(source.contains("MaxFrameTimeMs"))
        assertTrue(source.contains("TargetFrameTimeMs"))
    }

    @Test
    fun v16ScriptRequiresStrictP010IngressProofForByteBufferEncoderPath() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val evidenceBlock = source
            .substringAfter("function Test-EncoderEvidence")
            .substringBefore("\$script:Serial = \$Serial")

        assertTrue(evidenceBlock.contains("\$gpuProcessingPath -eq \"APP_P010_BYTEBUFFER_ENCODER\""))
        assertTrue(evidenceBlock.contains("ByteBufferP010Proof=true"))
        assertTrue(evidenceBlock.contains("ByteBufferP010FramesQueued > 0"))
        assertTrue(evidenceBlock.contains("ByteBufferP010FrameDrops = 0"))
        assertTrue(evidenceBlock.contains("P010 bytebuffer EOS"))
        assertTrue(evidenceBlock.contains("P010 bytebuffer evidence ->"))
        assertTrue(evidenceBlock.contains("[void]\$notProven.Add(\"APP_P010_BYTEBUFFER_ENCODER"))
    }

    @Test
    fun v16ScriptResolvesWingetMediaInfoCliWithoutShellRestart() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("Microsoft\\WinGet\\Packages"))
        assertTrue(source.contains("MediaArea.MediaInfo"))
        assertTrue(source.contains("MediaInfo.exe"))
    }

    @Test
    fun v16DryRunForwardsSelectedMatrixRowsAndLimitToV15() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val dryRunBlock = source
            .substringAfter("if (\$DryRun) {")
            .substringBefore("if ([string]::IsNullOrWhiteSpace(\$ffprobe))")

        assertTrue(dryRunBlock.contains("MaxRows"))
        assertTrue(dryRunBlock.contains("IncludeRows"))
    }

    @Test
    fun qualityGateWrappersPreserveMultipleIncludeRowsAcrossPowerShellProcessBoundary() {
        val v15 = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }.readText()
        val v16 = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }.readText()
        val v17 = listOf(
            File("tools/quality/run-v17-recorded-quality-repair-gate.ps1"),
            File("../tools/quality/run-v17-recorded-quality-repair-gate.ps1")
        ).first { it.isFile }.readText()

        assertTrue(v15.contains("function Normalize-IncludeRows"))
        assertTrue(v15.contains("-split \",\""))
        assertTrue(v16.contains("Normalize-IncludeRows -Rows \$IncludeRows"))
        assertTrue(v16.contains("\$dryRunArgs += @(\"-IncludeRows\", (\$IncludeRows -join \",\"))"))
        assertTrue(v16.contains("\$v15Args += @(\"-IncludeRows\", (\$IncludeRows -join \",\"))"))
        assertTrue(v17.contains("\$v16Args += @(\"-IncludeRows\", (\$IncludeRows -join \",\"))"))
        assertTrue(v17.contains("[switch]\$IsolateRows"))
        assertTrue(v17.contains("\$v16Args += \"-IsolateRows\""))
        assertTrue(v17.contains("\$v16Data = Get-Content \$v16JsonPath -Raw | ConvertFrom-Json"))
        assertTrue(v17.contains("foreach (\$item in \$v16Data)"))
        assertTrue(v17.contains("\$IncludeRows.Count -eq 0"))
        assertTrue(v17.contains("Where-Object { \$_ -match \"npv10\" }"))
    }

    @Test
    fun v16ScriptCanRunSelectedRowsInIsolatedColdAppSessions() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("[switch]\$IsolateRows"))
        assertTrue(source.contains("-IsolateRows requires -IncludeRows"))
        assertTrue(source.contains("foreach (\$rowId in \$IncludeRows)"))
        assertTrue(source.contains("am\", \"force-stop\", \$PackageName"))
        assertTrue(source.contains("\$rowV15Args += @(\"-IncludeRows\", \$rowId)"))
        assertTrue(source.contains("v15-run-\$rowId.stdout.txt"))
        assertTrue(source.contains("\$combinedV15Rows"))
    }

    @Test
    fun v15CertificateWritesProprietaryNoVendorCertificationFields() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val certificateBlock = source
            .substringAfter("function Write-V15Certificate")
            .substringBefore("\$script:ResolvedAdbPath")

        assertTrue(source.contains("Test-FalseMetadataFlag"))
        assertTrue(source.contains("Key = \"vendorHdrCertification\""))
        assertTrue(source.contains("Key = \"officialHdr10PlusMetadataWritten\""))
        assertTrue(source.contains("Key = \"vendorCertifiedHdr10Plus\""))
        assertTrue(source.contains("Key = \"vendorCertifiedDolbyVision\""))
        assertTrue(source.contains("Key = \"dolbyRpuWritten\""))
        assertTrue(source.contains("Key = \"externalHdrStandardSubstitution\""))
        assertTrue(source.contains("proprietaryProfilePreserved -ne \"true\""))
        assertTrue(certificateBlock.contains("vendorHdrCertification="))
        assertTrue(certificateBlock.contains("officialHdr10PlusMetadataWritten="))
        assertTrue(certificateBlock.contains("vendorCertifiedHdr10Plus="))
        assertTrue(certificateBlock.contains("vendorCertifiedDolbyVision="))
        assertTrue(certificateBlock.contains("dolbyRpuWritten="))
        assertTrue(certificateBlock.contains("externalHdrStandardSubstitution="))
        assertTrue(certificateBlock.contains("proprietaryProfilePreserved="))
        assertTrue(certificateBlock.contains("androidHdrTransportOnly="))
        assertTrue(certificateBlock.contains("syntheticGrainEnabled="))
    }

    @Test
    fun v16ScriptFailsRowsMissingProprietaryNoVendorCertificationEvidence() {
        val script = listOf(
            File("tools/quality/run-v16-real-device-encoder-validation.ps1"),
            File("../tools/quality/run-v16-real-device-encoder-validation.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val evidenceBlock = source
            .substringAfter("function Test-EncoderEvidence")
            .substringBefore("\$script:Serial = \$Serial")

        assertTrue(evidenceBlock.contains("vendorHdrCertification=false"))
        assertTrue(evidenceBlock.contains("officialHdr10PlusMetadataWritten=false"))
        assertTrue(evidenceBlock.contains("vendorCertifiedHdr10Plus=false"))
        assertTrue(evidenceBlock.contains("vendorCertifiedDolbyVision=false"))
        assertTrue(evidenceBlock.contains("dolbyRpuWritten=false"))
        assertTrue(evidenceBlock.contains("externalHdrStandardSubstitution=false"))
        assertTrue(evidenceBlock.contains("proprietaryProfilePreserved=true"))
        assertTrue(evidenceBlock.contains("androidHdrTransportOnly=true"))
    }

    @Test
    fun v17ScriptRequiresProcessedFrameAndSyntheticGrainTruthForRecordedQualityClaims() {
        val script = listOf(
            File("tools/quality/run-v17-recorded-quality-repair-gate.ps1"),
            File("../tools/quality/run-v17-recorded-quality-repair-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("run-v16-real-device-encoder-validation.ps1"))
        assertTrue(source.contains("ffprobe"))
        assertTrue(source.contains("Normal"))
        assertTrue(source.contains("Pro"))
        assertTrue(source.contains("N-Log"))
        assertTrue(source.contains("NDR10+"))
        assertTrue(source.contains("NIVUKX Vision"))
        assertTrue(source.contains("actualResolution"))
        assertTrue(source.contains("gpuProcessingPath"))
        assertTrue(source.contains("processedRecordingFramesValidated"))
        assertTrue(source.contains("syntheticGrainEnabled"))
        assertTrue(source.contains("\$syntheticGrainProofPresent"))
        assertTrue(source.contains("if (-not \$syntheticGrainProofPresent) { \$missingEvidence += \"syntheticGrainEnabled\" }"))
        assertTrue(source.contains("sourceBitDepth"))
        assertTrue(source.contains("storageBitDepth"))
        assertTrue(source.contains("vendorHdrCertification=false"))
        assertTrue(source.contains("officialHdr10PlusMetadataWritten=false"))
        assertTrue(source.contains("vendorCertifiedHdr10Plus=false"))
        assertTrue(source.contains("vendorCertifiedDolbyVision=false"))
        assertTrue(source.contains("dolbyRpuWritten=false"))
        assertTrue(source.contains("externalHdrStandardSubstitution=false"))
        assertTrue(source.contains("proprietaryProfilePreserved=true"))
        assertTrue(source.contains("PremiumComparison"))
        assertTrue(source.contains("NOT_PROVEN"))
    }

    @Test
    fun v15ScriptCapturesRotationMirrorAndBitrateEvidenceFromProbeOutput() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("stream_tags=rotate"))
        assertTrue(source.contains("stream_side_data=rotation"))
        assertTrue(source.contains("RotationDegrees"))
        assertTrue(source.contains("Mirrored"))
        assertTrue(source.contains("expected rotation"))
        assertTrue(source.contains("unexpected mirrored output"))
        assertTrue(source.contains("rotationDegrees="))
        assertTrue(source.contains("Result.RotationDegrees"))
        assertTrue(source.contains("mirrored="))
        assertTrue(source.contains("Result.Mirrored"))
    }

    @Test
    fun v15ScriptCapturesContinuousLogcatForEachRecordingRow() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("Start-ContinuousLogcatCapture"))
        assertTrue(source.contains("Stop-ContinuousLogcatCapture"))
        assertTrue(source.contains("Start-ContinuousLogcatCapture -OutputPath \$logPath"))
        assertTrue(source.contains("Start-ContinuousLogcatCapture -OutputPath \$logPath -FilterSpecs \$appLogFilterSpecs"))
        assertTrue(source.contains("Start-ContinuousLogcatCapture -OutputPath \$deviceLogPath"))
        assertTrue(source.contains("Get-Content -Path \$logPath"))
        assertTrue(source.contains("MainActivity:V"))
        assertTrue(source.contains("RecordingManager:V"))
        assertTrue(source.contains("AndroidRuntime:E"))
    }

    @Test
    fun v15ScriptPlumbsCapturedDeviceLogIntoTruthfulCertificateEvidence() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val rowEvaluation = source
            .substringAfter("function Test-V15Row")
            .substringBefore("function Write-V15Certificate")
        val certificateBlock = source
            .substringAfter("function Write-V15Certificate")
            .substringBefore("\$script:ResolvedAdbPath")

        assertTrue(rowEvaluation.contains("[string]\$DeviceLogText"))
        assertTrue(rowEvaluation.contains("\$deviceLogValidated"))
        assertTrue(rowEvaluation.contains("[regex]::Escape(\$Row.Label)"))
        assertTrue(rowEvaluation.contains("\"Recording saved ->\""))
        assertTrue(rowEvaluation.contains("\"Quality certificate ->\""))
        assertTrue(rowEvaluation.contains("missing complete per-recording device log proof"))
        assertTrue(rowEvaluation.contains("DeviceLogValidated = \$deviceLogValidated"))
        assertTrue(source.contains("-DeviceLogText \$deviceLogText"))
        assertTrue(source.contains("-DeviceLogPath \$deviceLogPath"))
        assertTrue(
            certificateBlock.contains(
                "deviceLogValidated=${'$'}(${'$'}Result.DeviceLogValidated)"
            )
        )
        assertTrue(certificateBlock.contains("deviceLogPath=\$DeviceLogPath"))
        assertFalse(certificateBlock.contains("adbLogPath=\$LogPath"))
    }

    @Test
    fun v15ScriptVerifiesForegroundBeforeDebugCommandDispatch() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val rowLoop = source
            .substringAfter("foreach (\$row in \$rows)")
            .substringBefore("Invoke-RecordCommand -Row \$row")
        val invokeBlock = source
            .substringAfter("function Invoke-RecordCommand")
            .substringBefore("function Wait-ForRecordingEvidence")

        assertTrue(source.contains("function Ensure-DeviceInteractive"))
        assertTrue(source.contains("\"svc\", \"power\", \"stayon\", \"usb\""))
        assertTrue(source.contains("KEYCODE_WAKEUP"))
        assertTrue(source.contains("dismiss-keyguard"))
        assertTrue(source.contains("function Wait-AppForeground"))
        assertTrue(source.contains("MainActivity did not reach foreground before DEBUG_COMMAND dispatch."))
        assertTrue(rowLoop.contains("Start-AppForeground"))
        assertTrue(rowLoop.contains("logcat\", \"-c"))
        assertTrue(invokeBlock.contains("Start-AppForeground"))
        assertTrue(invokeBlock.contains("\"am\", \"start\""))
        assertTrue(invokeBlock.contains("\"-W\""))
        assertTrue(invokeBlock.contains("\$PackageName/.ui.MainActivity"))
        assertTrue(invokeBlock.contains("\"-f\", \"0x34000000\""))
    }

    @Test
    fun v15ScriptUsesOnlyNewMediaStoreRowsForRepeatedValidationLabels() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("function Get-LatestMediaIdForRow"))
        assertTrue(source.contains("[long]\$MinimumMediaId = 0"))
        assertTrue(source.contains("\$where = \"\$where AND _id > \$MinimumMediaId\""))
        assertTrue(source.contains("\$preExistingMediaId = Get-LatestMediaIdForRow -Row \$row"))
        assertTrue(source.contains("Get-MediaStoreRow -Row \$row -MinimumMediaId \$preExistingMediaId"))
    }

    @Test
    fun v15ScriptAllowsDeviceOrientationRotationMetadata() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("AllowedRotationDegrees"))
        assertTrue(source.contains("0, 90, 180, 270"))
        assertTrue(source.contains("allowed rotations"))
    }

    @Test
    fun v15ScriptKeepsWaitingAfterDebugAutomationStartRetry() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()
        val waitBlock = source
            .substringAfter("function Wait-ForRecordingEvidence")
            .substringBefore("function Get-MediaStoreRow")

        assertFalse(waitBlock.contains("record_clip_start_failed"))
        assertTrue(waitBlock.contains("FATAL EXCEPTION|AndroidRuntime"))
    }

    @Test
    fun v15ScriptResolvesWingetMediaInfoCliWithoutShellRestart() {
        val script = listOf(
            File("tools/quality/run-v15-quality-evidence-gate.ps1"),
            File("../tools/quality/run-v15-quality-evidence-gate.ps1")
        ).first { it.isFile }
        val source = script.readText()

        assertTrue(source.contains("Microsoft\\WinGet\\Packages"))
        assertTrue(source.contains("MediaArea.MediaInfo"))
        assertTrue(source.contains("MediaInfo.exe"))
    }
}
