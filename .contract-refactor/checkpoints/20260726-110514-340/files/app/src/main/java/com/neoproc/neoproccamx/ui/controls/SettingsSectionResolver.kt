package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.NdrFeatureGate
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.ui.AppMode

internal enum class SettingsSectionKey {
    VIDEO,
    RAW_CONVERTER,
    NPV10_CONVERTER,
    RECORD_DEVELOP_SDR,
    RECORD_DEVELOP_NDR,
    CAMERA,
    VIDEO_COLOR,
    FOCUS,
    SYSTEM
}

internal object SettingsSectionResolver {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /**
     * @param showRecordDevelopCards whether to surface the SDR/NDR develop cards. The composer passes
     *   `RecordDevelopModeFlag.enabled || <a light intermediate already exists>`, so the cards appear while
     *   the record-develop engine is active AND stay reachable for develop/delete after it is turned back
     *   off (no stranded, undeletable intermediates). Default reads the flag alone, preserving the pure
     *   no-downgrade contract for every caller that does not query the store: engine OFF + no clips ⇒ the
     *   UI is byte-identical to today's (the two keys are simply absent).
     */
    fun resolve(
        mode: AppMode,
        showRecordDevelopCards: Boolean = RecordDevelopModeFlag.enabled
    ): List<SettingsSectionKey> = buildList {
        add(SettingsSectionKey.VIDEO)
        add(SettingsSectionKey.RAW_CONVERTER)
        if (showRecordDevelopCards) {
            add(SettingsSectionKey.RECORD_DEVELOP_SDR)
            if (NdrFeatureGate.enabled) {
                add(SettingsSectionKey.RECORD_DEVELOP_NDR)
            }
        }
        add(SettingsSectionKey.CAMERA)
        add(SettingsSectionKey.VIDEO_COLOR)
        add(SettingsSectionKey.FOCUS)
        add(SettingsSectionKey.SYSTEM)
    }

    /**
     * Full-screen settings acts as a video-control hub; always keep VIDEO_COLOR visible there
     * even when the active shooting mode is PHOTO/PRO.
     */
    fun resolveForVideoSettings(
        mode: AppMode,
        showRecordDevelopCards: Boolean = RecordDevelopModeFlag.enabled
    ): List<SettingsSectionKey> {
        val sections = resolve(mode, showRecordDevelopCards).toMutableList()
        if (sections.contains(SettingsSectionKey.VIDEO_COLOR)) {
            return sections
        }
        val insertAt = (sections.indexOf(SettingsSectionKey.CAMERA) + 1)
            .coerceAtLeast(0)
            .coerceAtMost(sections.size)
        sections.add(insertAt, SettingsSectionKey.VIDEO_COLOR)
        if (!strictHighEndEnabled()) return sections
        val strictOrder = buildList {
            add(SettingsSectionKey.VIDEO)
            add(SettingsSectionKey.RAW_CONVERTER)
            add(SettingsSectionKey.RECORD_DEVELOP_SDR)
            if (NdrFeatureGate.enabled) {
                add(SettingsSectionKey.RECORD_DEVELOP_NDR)
            }
            add(SettingsSectionKey.CAMERA)
            add(SettingsSectionKey.VIDEO_COLOR)
            add(SettingsSectionKey.FOCUS)
            add(SettingsSectionKey.SYSTEM)
        }
        return strictOrder.filter { key -> sections.contains(key) }
    }
}
