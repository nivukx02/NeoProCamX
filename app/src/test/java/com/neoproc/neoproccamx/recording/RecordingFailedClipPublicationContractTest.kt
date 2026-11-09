package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFailedClipPublicationContractTest {
    private val managerSource =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()
    private val viewModelSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

    @Test
    fun sensorLinearFatalIngressPoisonsPublicationBeforeEncoderStop() {
        val stopBlock = viewModelSource
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private suspend fun stopNpv10RecordingSession")
        val failureRead = stopBlock.indexOf("sensorLinearNdrRecordingFailure.get()?.let")
        val poison = stopBlock.indexOf("gpuRecording.reportFatalPipelineFailure(", failureRead)
        val encoderStop = stopBlock.indexOf("val path = gpuRecording.stop()", poison)

        assertTrue(failureRead >= 0)
        assertTrue(poison > failureRead)
        assertTrue(encoderStop > poison)
    }

    @Test
    fun failedStopVerdictAbortsBeforeAnyFinalizeOrCertificateWrite() {
        val stopBlock = managerSource
            .substringAfter("fun stop(): String?")
            .substringBefore("// VIDEO DRAIN LOOP")
        val abort = stopBlock.indexOf("closeDecision.abortBackend ->")
        val backendAbort = stopBlock.indexOf("movSessionController?.abort()", abort)
        val publicationGate = stopBlock.indexOf(
            "check(closeDecision.permitsPublication || salvageDevelopMaster)",
            backendAbort
        )
        val finalize = stopBlock.indexOf("finalizeOutputTarget(finalOutputDescription)")
        val certificate = stopBlock.indexOf("writeQualityCertificateForFinalizedRecording(path, finalizedOutput)")
        val failureCleanup = stopBlock.indexOf("cleanupFailedOutputTarget()", finalize)
        val errorState = stopBlock.indexOf(
            "_state.value = GpuRecordingState.Error(",
            failureCleanup
        )

        assertTrue(abort >= 0)
        assertTrue(backendAbort > abort)
        assertTrue(publicationGate > backendAbort)
        assertTrue(finalize > publicationGate)
        assertTrue(certificate > finalize)
        assertTrue(failureCleanup > certificate)
        assertTrue(errorState > failureCleanup)
        assertFalse(
            stopBlock.substring(abort, publicationGate).contains("finalizeOutputTarget(")
        )
    }

    @Test
    fun terminalWriterFailureIsRecheckedAfterRetirementBeforeBackendOrPublication() {
        val stopBlock = managerSource
            .substringAfter("fun stop(): String?")
            .substringBefore("// VIDEO DRAIN LOOP")
        val retire = stopBlock.indexOf("retireMuxerWriter(reason = \"stop_finalize\")")
        val finalPoisonRead = stopBlock.indexOf(
            "fatalPipelineFailureAtStop = fatalPipelineFailure.get()",
            retire
        )
        val finalDecision = stopBlock.indexOf(
            "closeDecision = RecordingStopFinalizationPolicy.resolveCloseDecision(",
            finalPoisonRead
        )
        val backendFinalize = stopBlock.indexOf("// Finalize the active recording container.")
        val publicationGate = stopBlock.indexOf(
            "check(closeDecision.permitsPublication || salvageDevelopMaster)",
            backendFinalize
        )

        assertTrue(retire >= 0)
        assertTrue(finalPoisonRead > retire)
        assertTrue(finalDecision > finalPoisonRead)
        assertTrue(backendFinalize > finalDecision)
        assertTrue(publicationGate > backendFinalize)
        assertTrue(
            stopBlock.substring(finalDecision, backendFinalize)
                .contains("fatalPipelineFailure = fatalPipelineFailureAtStop != null")
        )
    }

    @Test
    fun strictAndFatalVerdictsCannotUsePrivateMasterSalvage() {
        val stopFunction = managerSource
            .substringAfter("fun stop(): String?")
            .substringBefore("// VIDEO DRAIN LOOP")
        val stopBlock = stopFunction
            .substringAfter("val salvageDevelopMaster =")
            .substringBefore("if (salvageDevelopMaster)")
        val finalDecision = stopFunction.lastIndexOf(
            "closeDecision = RecordingStopFinalizationPolicy.resolveCloseDecision(",
            stopFunction.indexOf("val salvageDevelopMaster =")
        )
        val salvage = stopFunction.indexOf("val salvageDevelopMaster =")

        assertTrue(stopBlock.contains("fatalPipelineFailureAtStop == null"))
        assertTrue(stopBlock.contains("strictStopFrameGateEvidence.required"))
        assertTrue(stopBlock.contains("!strictStopFrameGateEvidence.integrityPassed"))
        assertTrue(finalDecision >= 0 && finalDecision < salvage)
        val finalDecisionBlock = stopFunction.substring(finalDecision, salvage)
        assertTrue(
            finalDecisionBlock.contains(
                "strictMinimumVideoSamples = strictStopFrameGateEvidence.expectedVideoSamples"
            )
        )
        assertTrue(
            finalDecisionBlock.contains(
                "strictFrameIntegrityPassed = strictStopFrameGateEvidence.integrityPassed"
            )
        )
        assertTrue(
            finalDecisionBlock.contains("fatalPipelineFailure = fatalPipelineFailureAtStop != null")
        )
    }

    @Test
    fun aNewPreparedSessionClearsThePreviousSessionPoisonBeforeAcceptingOutput() {
        val prepareBlock = managerSource
            .substringAfter("fun prepare(): Surface?")
            .substringBefore("private fun configureVideoCodec")
        val release = prepareBlock.indexOf("releaseInternal()")
        val clearPoison = prepareBlock.indexOf("fatalPipelineFailure.reset()", release)
        val createOutput = prepareBlock.indexOf("createOutputTarget()", clearPoison)

        assertTrue(release >= 0)
        assertTrue(clearPoison > release)
        assertTrue(createOutput > clearPoison)
    }
}
