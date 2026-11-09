package com.neoproc.neoproccamx.recording

import android.content.ContentResolver
import java.io.File

/** Direct pending-MediaStore NPV10 conversion path; null means use the established staged fallback. */
internal object Npv10DirectMovWorkflow {
    fun convertOrNull(
        resolver: ContentResolver,
        clip: Npv10MasterClip,
        proposedOutput: File,
        outputFormat: Npv10MovConverter.OutputFormat,
        gradeLut: Pair<FloatArray, Int>?,
        onFrame: ((Int) -> Unit)?,
        onPublished: (String) -> Unit
    ): String? {
        ConversionCancellationContext.throwIfCancellationRequested()
        Npv10VerifiedMasterPublication.requireVerifiedForConversion(clip.file)
        val plan = Npv10MovPublishPolicy.resolveConvertedMov(proposedOutput)
        val direct = SeekableMediaStoreMovOutput.openOrNull(
            resolver = resolver,
            displayName = plan.displayName,
            relativePath = plan.relativePath
        ) ?: return null
        direct.use { target ->
            val audit = Npv10MovConverter.convertWithAudit(
                input = clip.file,
                output = target.outputFile,
                gradeLut = gradeLut,
                onFrame = { framesDone ->
                    ConversionCancellationContext.throwIfCancellationRequested()
                    onFrame?.invoke(framesDone)
                    ConversionCancellationContext.throwIfCancellationRequested()
                },
                outputFormat = outputFormat
            )
            check(audit.auditStatus == Npv10AuditStatus.PASS) {
                "NPV10 MOV conversion audit failed (${audit.auditStatus}); " +
                    "master retained: ${clip.file.absolutePath}"
            }
            ConversionCancellationContext.throwIfCancellationRequested()
            val uri = target.commit()
            onPublished(uri.toString())
            return plan.displayName
        }
    }
}
