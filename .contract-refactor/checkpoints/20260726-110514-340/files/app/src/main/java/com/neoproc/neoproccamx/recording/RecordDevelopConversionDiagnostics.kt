package com.neoproc.neoproccamx.recording

import android.util.Log
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.io.File

internal object RecordDevelopConversionDiagnostics {
    private const val TAG = "RecordDevelopConvert"

    fun logStart(
        clip: RecordDevelopMasterClip,
        output: File,
        options: RecordDevelopConversionOptions,
        sidecarPresent: Boolean,
        outputRoot: File,
        externalRoot: File
    ) {
        val estimatedOutputBytes = RecordDevelopOutputEstimatePolicy.estimateOutputBytes(
            clip.file,
            options.resolvedDeliveryFormat()
        )
        NeoLogger.i(
            TAG,
            "START kind=${clip.kind} source=${clip.file.absolutePath} sourceBytes=${clip.file.length()} " +
                "output=${output.absolutePath} format=${options.resolvedDeliveryFormat().auditLabel} " +
                "legacyFormat=${options.outputFormat.name} sidecar=$sidecarPresent " +
                "strictGpu=${options.requireGpuDevelop} " +
                "estimatedOutputBytes=$estimatedOutputBytes " +
                "outputUsableBytes=${RecordDevelopOutputEstimatePolicy.usableBytes(outputRoot)} " +
                "externalUsableBytes=${RecordDevelopOutputEstimatePolicy.usableBytes(externalRoot)}"
        )
    }

    fun logDone(audit: RecordDevelopConversionAudit, output: File) {
        NeoLogger.i(
            TAG,
            "DONE frames=${audit.frameCount} bytes=${audit.outputBytes} kind=${audit.sourceKind} " +
                "target=${audit.developTarget} delivery=${audit.deliveryFormat.auditLabel} " +
                "format=${audit.outputFormat} workers=${audit.workerCount} " +
                "enhancePath=${audit.enhanceProcessingPath} gpuEnhanceFrames=${audit.gpuEnhanceFrames} " +
                "colorGpu=${audit.gpuAccelerated} output=${output.absolutePath}"
        )
    }

    fun logFailure(
        clip: RecordDevelopMasterClip,
        output: File,
        options: RecordDevelopConversionOptions,
        outputRoot: File,
        throwable: Throwable
    ) {
        val message = "FAILED kind=${clip.kind} source=${clip.file.absolutePath} " +
            "sourceBytes=${clip.file.length()} output=${output.absolutePath} outputBytes=${output.length()} " +
            "format=${options.resolvedDeliveryFormat().auditLabel} legacyFormat=${options.outputFormat.name} " +
            "strictGpu=${options.requireGpuDevelop} " +
            "outputUsableBytes=${RecordDevelopOutputEstimatePolicy.usableBytes(outputRoot)} " +
            "message=${throwable.message}"
        Log.e(TAG, message, throwable)
        NeoLogger.e(TAG, message, throwable)
    }
}
