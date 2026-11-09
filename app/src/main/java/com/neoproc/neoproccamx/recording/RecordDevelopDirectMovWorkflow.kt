package com.neoproc.neoproccamx.recording

import android.content.ContentResolver
import java.io.File
import java.util.Locale

/** Direct pending-MediaStore publish path for record-develop masters; null means use staged fallback. */
internal object RecordDevelopDirectMovWorkflow {
    fun convertOrNull(
        resolver: ContentResolver,
        clip: RecordDevelopMasterClip,
        deliveryFormat: RecordDevelopDeliveryFormat,
        sidecar: VideoDevelopColorSidecar.VideoDevelopColorMetadata?,
        options: RecordDevelopConversionOptions,
        outputRoot: File,
        diagnosticsExternalRoot: File,
        onFrameFactory: () -> ((Int) -> Unit)?,
        onPublished: (String) -> Unit
    ): String? {
        ConversionCancellationContext.throwIfCancellationRequested()
        val displayName = "${clip.file.nameWithoutExtension}_" +
            "${clip.kind.name.lowercase(Locale.US)}_${deliveryFormat.fileSuffix}_dev.mov"
        val direct = SeekableMediaStoreMovOutput.openOrNull(resolver, displayName) ?: return null
        direct.use { target ->
            val certificateDirectory = File(diagnosticsExternalRoot, "record_develop_publication")
            val certificateBaseName = "${target.transactionId}_${displayName.take(96)}"
            val certificateFile = File(certificateDirectory, "$certificateBaseName.certificate.txt")
            val staleEliteCertificate = File(
                certificateDirectory,
                "$certificateBaseName.elite-conversion-certificate.txt"
            )
            var committed = false
            RecordDevelopConversionDiagnostics.logStart(
                clip,
                target.outputFile,
                options,
                sidecar != null,
                outputRoot,
                diagnosticsExternalRoot
            )
            try {
                val audit = RecordDevelopMovConverter.convertWithAudit(
                    master = clip.file,
                    output = target.outputFile,
                    sidecar = sidecar,
                    options = options,
                    sourceKind = clip.kind,
                    readerFactory = ::RecordDevelopMediaCodecP010Reader,
                    onFrame = onFrameFactory().let { reporter ->
                        { framesDone ->
                            ConversionCancellationContext.throwIfCancellationRequested()
                            reporter?.invoke(framesDone)
                            ConversionCancellationContext.throwIfCancellationRequested()
                        }
                    },
                    gpuAcceleratorFactory = ::NativeRecordDevelopGpuAccelerator,
                    outputOwnership = MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR
                )
                RecordDevelopConversionDiagnostics.logDone(audit, target.outputFile)
                check(
                    audit.frameCount > 0L &&
                        audit.inputMasterHashUnchanged &&
                        target.lengthBytes() > 0L
                ) {
                    "Record-develop failed verification; source retained: ${clip.file.absolutePath}"
                }
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = target.outputFile,
                    certificateFile = certificateFile,
                    expectation = RecordDevelopMovConverter.publicationExpectation(
                        audit = audit,
                        rotationDegrees = sidecar?.orientationHintDegrees ?: 0
                    ),
                    declaredPipelineAuditText = audit.toCertificateText(),
                    additionalStaleCertificates = listOf(staleEliteCertificate),
                    outputOwnership = MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR
                )
                ConversionCancellationContext.throwIfCancellationRequested()
                val uri = target.commit()
                committed = true
                onPublished(uri.toString())
                return displayName
            } catch (failure: Throwable) {
                if (!committed) {
                    listOf(certificateFile, staleEliteCertificate).forEach { certificate ->
                        if (certificate.exists() && (!certificate.delete() || certificate.exists())) {
                            failure.addSuppressed(
                                IllegalStateException(
                                    "Failed to remove unpublished conversion certificate " +
                                        certificate.absolutePath
                                )
                            )
                        }
                    }
                }
                RecordDevelopConversionDiagnostics.logFailure(
                    clip,
                    target.outputFile,
                    options,
                    outputRoot,
                    failure
                )
                throw failure
            }
        }
    }
}
