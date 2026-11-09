package com.neoproc.neoproccamx.recording

import java.io.File

data class Npv10MovPublishPlan(
    val displayName: String,
    val mimeType: String,
    val relativePath: String
)

data class Npv10GalleryPublishDecision(
    val publishable: Boolean,
    val reason: String
)

object Npv10MovPublishPolicy {
    const val MIME_TYPE = "video/quicktime"
    const val RELATIVE_PATH = "Movies/CamXNPV10"

    fun resolve(input: File): Npv10MovPublishPlan {
        val displayName = "${input.nameWithoutExtension}.mov"
        return Npv10MovPublishPlan(
            displayName = displayName,
            mimeType = MIME_TYPE,
            relativePath = RELATIVE_PATH
        )
    }

    fun resolveConvertedMov(convertedMov: File): Npv10MovPublishPlan {
        val decision = galleryEligibility(convertedMov)
        require(decision.publishable) { decision.reason }
        return Npv10MovPublishPlan(
            displayName = convertedMov.name,
            mimeType = MIME_TYPE,
            relativePath = RELATIVE_PATH
        )
    }

    fun galleryEligibility(file: File): Npv10GalleryPublishDecision =
        when {
            file.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true) ->
                Npv10GalleryPublishDecision(
                    publishable = false,
                    reason = "NPV10 private masters stay private and require manual MOV conversion"
                )
            file.extension.equals(Npv10MovConverter.MOV_EXTENSION, ignoreCase = true) ->
                Npv10GalleryPublishDecision(
                    publishable = true,
                    reason = "Converted MOV is gallery publishable"
                )
            else -> Npv10GalleryPublishDecision(
                publishable = false,
                reason = "Only converted MOV exports are gallery publishable"
            )
        }
}
