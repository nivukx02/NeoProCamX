package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * SNCE v2.0 HVC — Stage 07 (MCAS-7): memory colour anchoring + skin-tone precision.
 *
 * Two cooperating pieces:
 *  - [SnceMemoryColor]: the 7-category canonical colour database (directive 07.1) and
 *    the confidence-gated anchor blend (07.2). All anchors are CAM16 {J,C,h,M} at the
 *    L_A = 64 reference condition.
 *  - [SnceSkinTone]: the directive 07.3 skin precision engine plus the PDF's Model 4c
 *    ITA (Individual Typology Angle) skin classifier -> Fitzpatrick I-VI / Monk 1-10 ->
 *    per-tier CAM16 anchor. Pure deterministic maths (no model file), so it is fully
 *    implemented here, not merely hooked.
 */
object SnceMemoryColor {

    enum class Category { SKIN, SKY, FOLIAGE, SNOW, FIRE, WATER, EARTH }

    /** Canonical memory colour anchor in CAM16 correlates at L_A = 64 cd/m^2. */
    class MemoryAnchor(
        val name: String,
        val category: Category,
        val j: Float,
        val c: Float,
        val h: Float,
        val m: Float
    )

    /** Directive 07.1 — the canonical memory colour database. */
    val DATABASE: List<MemoryAnchor> = listOf(
        MemoryAnchor("Caucasian Skin", Category.SKIN, 60f, 28f, 45f, 18f),
        MemoryAnchor("South Asian Skin", Category.SKIN, 52f, 32f, 38f, 20f),
        MemoryAnchor("East Asian Skin", Category.SKIN, 65f, 22f, 48f, 14f),
        MemoryAnchor("African Skin", Category.SKIN, 35f, 30f, 32f, 18f),
        MemoryAnchor("Clear Sky (noon)", Category.SKY, 72f, 45f, 220f, 30f),
        MemoryAnchor("Golden Hour Sky", Category.SKY, 68f, 72f, 55f, 48f),
        MemoryAnchor("Foliage / Grass", Category.FOLIAGE, 48f, 52f, 143f, 32f),
        MemoryAnchor("Healthy Foliage", Category.FOLIAGE, 44f, 58f, 140f, 36f),
        MemoryAnchor("Snow / Clouds", Category.SNOW, 92f, 4f, 220f, 2.5f),
        MemoryAnchor("Fire / Flame", Category.FIRE, 55f, 80f, 42f, 55f),
        MemoryAnchor("Water / Ocean", Category.WATER, 50f, 38f, 210f, 24f),
        MemoryAnchor("Earth / Soil", Category.EARTH, 35f, 28f, 52f, 18f)
    )

    fun anchorsFor(category: Category): List<MemoryAnchor> = DATABASE.filter { it.category == category }

    /** CAM16 appearance distance to an anchor (directive 07.2 delta_E_cam). */
    fun anchorDistance(app: SnceCam16Appearance, anchor: MemoryAnchor): Float {
        val dj = anchor.j - app.j
        val dc = anchor.c - app.c
        val dh = SnceColorPrimitives.hueDifferenceDegrees(app.h, anchor.h)
        val chromaArc = app.c * dh * (Math.PI.toFloat() / 180f)
        return sqrt(dj * dj + dc * dc + chromaArc * chromaArc)
    }

    /**
     * Confidence-gated blend toward an anchor (directive 07.2). The blend weight grows
     * with detection confidence and with deviation, capped at 0.40 so anchoring nudges
     * rather than overrides measured colour.
     */
    fun blendToward(
        app: SnceCam16Appearance,
        anchor: MemoryAnchor,
        detectionConfidence: Float,
        maxWeight: Float = 0.40f
    ): SnceCam16Appearance {
        val deltaE = anchorDistance(app, anchor)
        val w = (detectionConfidence.coerceIn(0f, 1f) * tanh(deltaE / 8f) * maxWeight).coerceIn(0f, maxWeight)
        val dj = anchor.j - app.j
        val dc = anchor.c - app.c
        val dh = SnceColorPrimitives.hueDifferenceDegrees(app.h, anchor.h)
        return app.copy(
            j = app.j + w * dj,
            c = app.c + w * dc,
            h = SnceColorPrimitives.wrapHueDegrees(app.h + w * dh)
        )
    }
}

object SnceSkinTone {

    /** Per-tier skin anchor in CAM16 (PDF Model 4c, L_A = 64). */
    class SkinAnchor(val j: Float, val c: Float, val h: Float)

    /** Fitzpatrick I-VI -> CAM16 skin anchor (PDF SKIN_ANCHORS table). */
    val FITZPATRICK_ANCHORS: Map<Int, SkinAnchor> = mapOf(
        1 to SkinAnchor(72f, 20f, 48f),
        2 to SkinAnchor(65f, 25f, 46f),
        3 to SkinAnchor(58f, 30f, 43f),
        4 to SkinAnchor(50f, 33f, 40f),
        5 to SkinAnchor(40f, 32f, 35f),
        6 to SkinAnchor(28f, 28f, 30f)
    )

    /** ITA = atan2(L*-50, b*) in degrees (PDF Model 4c). */
    fun computeIta(lab: FloatArray): Float {
        val lStar = lab[0]
        val bStar = lab[2]
        return SnceColorPrimitives.toDegrees(atan2(lStar - 50f, bStar))
    }

    fun itaToFitzpatrick(ita: Float): Int = when {
        ita > 55f -> 1
        ita > 28f -> 2
        ita > 10f -> 3
        ita > -30f -> 4
        ita > -55f -> 5
        else -> 6
    }

    /** Monk 1 (lightest) .. 10 (darkest), linear in ITA across the skin range. */
    fun itaToMonk(ita: Float): Int {
        val clamped = ita.coerceIn(-60f, 55f)
        val t = (55f - clamped) / (55f - (-60f)) // 0 at lightest, 1 at darkest
        return (1 + Math.round(t * 9f)).coerceIn(1, 10)
    }

    fun anchorFor(fitzpatrick: Int): SkinAnchor =
        FITZPATRICK_ANCHORS[fitzpatrick.coerceIn(1, 6)] ?: FITZPATRICK_ANCHORS.getValue(3)

    /**
     * Directive 07.3 skin precision constraints: keep skin in the warm golden-brown band,
     * never let it wash out or push toward blue. Applied AFTER ethnicity/ITA anchoring.
     */
    fun applySkinPrecision(app: SnceCam16Appearance): SnceCam16Appearance {
        val h = SnceColorPrimitives.wrapHueDegrees(app.h)
        // Clamp hue into the 33..50 deg warm band the directive specifies for skin.
        val clampedH = when {
            h in 33f..50f -> h
            // choose the nearer band edge for hues just outside the band
            shortestArc(h, 33f) <= shortestArc(h, 50f) -> 33f
            else -> 50f
        }
        val clampedC = app.c.coerceAtLeast(15f)   // chroma floor: no desaturated skin
        val clampedJ = app.j.coerceIn(30f, 75f)   // no clipped highlights / crushed shadows
        return app.copy(j = clampedJ, c = clampedC, h = clampedH)
    }

    private fun shortestArc(a: Float, b: Float): Float =
        kotlin.math.abs(SnceColorPrimitives.hueDifferenceDegrees(a, b))

    /**
     * Full skin pass: classify by ITA, anchor toward the matching Fitzpatrick tier, then
     * enforce the precision band. [labForIta] is the measured skin patch in CIE Lab.
     */
    fun refineSkin(
        app: SnceCam16Appearance,
        labForIta: FloatArray,
        detectionConfidence: Float
    ): SnceCam16Appearance {
        val ita = computeIta(labForIta)
        val anchor = anchorFor(itaToFitzpatrick(ita))
        val memAnchor = SnceMemoryColor.MemoryAnchor(
            "ITA tier", SnceMemoryColor.Category.SKIN, anchor.j, anchor.c, anchor.h, anchor.c
        )
        val anchored = SnceMemoryColor.blendToward(app, memAnchor, detectionConfidence)
        return applySkinPrecision(anchored)
    }
}
