package com.neoproc.neoproccamx.ui

import java.io.File
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopStatusPanelLayoutContractTest {
    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val dimens = File("src/main/res/values/dimens.xml").readText()

    @Test
    fun `top status panel grows around both text rows instead of clipping its health chips`() {
        val panel = layout
            .substringAfter("android:id=\"@+id/topStatusPanel\"")
            .substringBefore("android:id=\"@+id/topWarningPanel\"")

        assertTrue(panel.contains("android:layout_height=\"wrap_content\""))
        assertTrue(panel.contains("android:minHeight=\"@dimen/top_sensor_dock_height\""))
        assertTrue(panel.contains("android:paddingVertical=\"4dp\""))
        assertFalse(panel.contains("android:layout_height=\"@dimen/top_sensor_dock_height\""))
        assertTrue(dimenDp("top_sensor_dock_height") >= 40)
    }

    @Test
    fun `every runtime health chip preserves full glyph metrics and a centered minimum height`() {
        val chipIds = listOf("chipProvCore", "chipP010", "chipLab", "chipNative")

        assertTrue(dimenDp("runtime_health_chip_min_height") >= 20)
        chipIds.forEachIndexed { index, id ->
            val nextId = chipIds.getOrNull(index + 1)
            val chip = layout
                .substringAfter("android:id=\"@+id/$id\"")
                .let { block ->
                    if (nextId == null) block.substringBefore("</LinearLayout>")
                    else block.substringBefore("android:id=\"@+id/$nextId\"")
                }

            assertTrue("$id must center its glyph", chip.contains("android:gravity=\"center\""))
            assertTrue("$id must retain complete font metrics", chip.contains("android:includeFontPadding=\"true\""))
            assertTrue(
                "$id must reserve a non-clipping height",
                chip.contains("android:minHeight=\"@dimen/runtime_health_chip_min_height\"")
            )
            assertEquals(1, chip.countOccurrences("android:paddingVertical=\"2dp\""))
        }
    }

    @Test
    fun `status text remains unclipped at normal and enlarged Android font scales`() {
        val panelTag = startTagForId("topStatusPanel")
        val primaryTag = startTagForId("tvStatusPrimary")
        val healthRowTag = startTagForId("healthIndicatorRow")
        val normalFontScale = 1.0
        val enlargedFontScale = 2.0

        assertUnboundedWrapContent(panelTag, "topStatusPanel")
        assertUnboundedWrapContent(primaryTag, "tvStatusPrimary")
        assertUnboundedWrapContent(healthRowTag, "healthIndicatorRow")

        listOf("chipProvCore", "chipP010", "chipLab", "chipNative").forEach { id ->
            val chipTag = startTagForId(id)
            val textSizeSp = spValue(chipTag, "textSize")
            val verticalPaddingDp = dpValue(chipTag, "paddingVertical")
            val minimumHeightDp = dimenDp("runtime_health_chip_min_height")
            val normalContentHeightDp = ceil(textSizeSp * normalFontScale + 2 * verticalPaddingDp).toInt()
            val enlargedContentHeightDp = ceil(textSizeSp * enlargedFontScale + 2 * verticalPaddingDp).toInt()

            assertTrue("$id minimum must cover normal-scale text and padding", minimumHeightDp >= normalContentHeightDp)
            assertTrue("font-scale check must exercise growth beyond the minimum", enlargedContentHeightDp > minimumHeightDp)
            assertUnboundedWrapContent(chipTag, id)
            assertTrue("$id must include complete top and bottom font metrics", chipTag.contains("android:includeFontPadding=\"true\""))
        }
    }

    private fun dimenDp(name: String): Int =
        Regex("""<dimen name="$name">(\d+)dp</dimen>""")
            .find(dimens)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("Missing integer dp dimension: $name")

    private fun startTagForId(id: String): String =
        layout.substringAfter("android:id=\"@+id/$id\"").substringBefore(">")

    private fun spValue(tag: String, attribute: String): Double =
        Regex("""android:$attribute="([0-9.]+)sp"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("Missing sp attribute: $attribute")

    private fun dpValue(tag: String, attribute: String): Double =
        Regex("""android:$attribute="([0-9.]+)dp"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("Missing dp attribute: $attribute")

    private fun assertUnboundedWrapContent(tag: String, id: String) {
        assertTrue("$id must measure from its content", tag.contains("android:layout_height=\"wrap_content\""))
        assertFalse("$id must not cap enlarged text", tag.contains("android:maxHeight="))
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, step = 1).count { it == token }
}
