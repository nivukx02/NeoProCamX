package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCodecCreationPlanPolicyTest {

    @Test
    fun namedCreationTriesSelectedEncoderBeforeOtherRankedCandidates() {
        val plan = RecordingCodecCreationPlanPolicy.orderedCodecNames(
            selectedCodecName = "c2.mtk.hevc.encoder",
            selectedCreateByName = true,
            rankedCandidateNames = listOf(
                "c2.android.hevc.encoder",
                "c2.mtk.hevc.encoder",
                "OMX.MTK.VIDEO.ENCODER.HEVC"
            )
        )

        assertEquals(
            listOf(
                "c2.mtk.hevc.encoder",
                "c2.android.hevc.encoder",
                "OMX.MTK.VIDEO.ENCODER.HEVC"
            ),
            plan
        )
    }

    @Test
    fun blankOrDisabledSelectedEncoderDoesNotBlockOtherNamedCandidates() {
        val plan = RecordingCodecCreationPlanPolicy.orderedCodecNames(
            selectedCodecName = " ",
            selectedCreateByName = false,
            rankedCandidateNames = listOf(
                "",
                "c2.mtk.hevc.encoder",
                "c2.mtk.hevc.encoder",
                "OMX.MTK.VIDEO.ENCODER.HEVC"
            )
        )

        assertEquals(
            listOf(
                "c2.mtk.hevc.encoder",
                "OMX.MTK.VIDEO.ENCODER.HEVC"
            ),
            plan
        )
    }
}
