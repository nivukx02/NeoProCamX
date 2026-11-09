package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Honesty meta-test for the AINeuralEngine model registry (s7-f08 / CP98).
 *
 * The registry must never silently imply a capability the app does not ship. Every alias in
 * MODEL_ALIAS_REGISTRY must be EITHER a live consumer that loads through this engine
 * (object_detection via ObjectTracker, denoising via NeuralDenoiseEngine) OR carry an explicit,
 * TRUTHFUL classification annotation in source:
 *   - owned-by-<subsystem>  : packaged asset owned by a self-loading SDK/engine.
 *   - active-but-unconsumed : resolves to a packaged binary but no consumer loads it here.
 *   - dormant-no-binary     : no asset packaged, no consumer.
 * loadModel must keep gating non-.tflite and non-ACTIVE assets so status can never synthesize a
 * 'loaded' for a missing or foreign asset.
 *
 * Source-text characterization test (no device, no TFLite runtime), matching the established pattern
 * of AINeuralEngineAssetDiscoveryContractTest.
 */
class AINeuralEngineRegistryHonestyTest {

    /** Aliases that actually flow through AINeuralEngine.loadModel/runInference. */
    private val liveConsumerAliases = setOf("object_detection", "denoising")

    /** Aliases resolving to a packaged asset owned by a self-loading subsystem (MediaPipe / own Interpreter). */
    private val subsystemOwnedAliases = setOf(
        "depth_estimation",
        "segmentation",
        "face_detection",
        "pose_estimation"
    )

    /** Aliases that reconcile ACTIVE (binary present) but have NO consumer through this engine. */
    private val activeNoConsumerAliases = setOf("image_enhancement")

    /** Aliases that ship no binary and have no consumer; must be annotated dormant. */
    private val dormantNoBinaryAliases = setOf(
        "super_resolution",
        "scene_classification",
        "hdr_merge"
    )

    @Test
    fun everyRegistryAliasIsEitherLiveConsumerOrExplicitlyAnnotated() {
        val source = aiSource("AINeuralEngine.kt").readText()
        val aliases = registryAliases(source)
        assertTrue("Registry must declare aliases", aliases.isNotEmpty())

        val classified = liveConsumerAliases + subsystemOwnedAliases + activeNoConsumerAliases + dormantNoBinaryAliases
        aliases.forEach { alias ->
            assertTrue(
                "Alias '$alias' is unclassified; classify it (live/owned-by/active-but-unconsumed/dormant-no-binary) — registry-honesty violation",
                classified.contains(alias)
            )
        }
    }

    @Test
    fun subsystemOwnedAliasesCarryOwnershipAnnotation() {
        val source = aiSource("AINeuralEngine.kt").readText()
        subsystemOwnedAliases.forEach { alias ->
            assertTrue(
                "Alias '$alias' must carry an owned-by ownership annotation in source",
                annotatedLineFor(source, alias)?.contains("owned-by") == true
            )
        }
    }

    @Test
    fun activeButUnconsumedAliasesCarryTruthfulAnnotationNotFalseOwner() {
        val source = aiSource("AINeuralEngine.kt").readText()
        activeNoConsumerAliases.forEach { alias ->
            val line = annotatedLineFor(source, alias)
            assertTrue(
                "Alias '$alias' must be annotated 'active-but-unconsumed'",
                line?.contains("active-but-unconsumed") == true
            )
            assertTrue(
                "Alias '$alias' must NOT claim a false 'owned-by' owner",
                line?.contains("owned-by") != true
            )
        }
    }

    @Test
    fun dormantNoBinaryAliasesCarryDormantAnnotation() {
        val source = aiSource("AINeuralEngine.kt").readText()
        dormantNoBinaryAliases.forEach { alias ->
            assertTrue(
                "Alias '$alias' ships no packaged binary and must carry a dormant-no-binary annotation",
                annotatedLineFor(source, alias)?.contains("dormant-no-binary") == true
            )
        }
    }

    @Test
    fun loadModelStillGatesNonTfliteAndNonActiveAssetsSoStatusCannotLieLoaded() {
        val source = aiSource("AINeuralEngine.kt").readText()
        // Non-ACTIVE alias is skipped (never marked loaded).
        assertTrue(source.contains("if (modelStatusMap[modelName] != ModelStatus.ACTIVE)"))
        assertTrue(source.contains("Skipping unavailable model"))
        // A resolved foreign .task asset is refused (subsystem loader owns it).
        assertTrue(source.contains("if (!resolvedModelFile.endsWith(\".tflite\", ignoreCase = true))"))
        assertTrue(source.contains("subsystem loader owns it"))
        // Loaded status is only ever set after a successful Interpreter construction + wrapper insert.
        assertTrue(source.contains("loadedModels[modelName] = wrapper"))
    }

    private fun registryAliases(source: String): List<String> {
        val start = source.indexOf("MODEL_ALIAS_REGISTRY = mapOf(")
        require(start >= 0) { "MODEL_ALIAS_REGISTRY not found" }
        val end = source.indexOf("\n        )", start)
        require(end > start) { "MODEL_ALIAS_REGISTRY end not found" }
        val block = source.substring(start, end)
        val regex = Regex("\"([a-z_]+)\"\\s+to\\s+listOf\\(")
        return regex.findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun annotatedLineFor(source: String, alias: String): String? =
        source.lineSequence().firstOrNull { it.contains("\"$alias\" to listOf(") }

    private fun aiSource(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        val rel = "app/src/main/java/com/neoproc/neoproccamx/ai/$name"
        repeat(6) {
            val candidate = File(dir, rel)
            if (candidate.isFile) return candidate
            val appRelative = File(dir, rel.removePrefix("app/"))
            if (appRelative.isFile) return appRelative
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate $name from ${File(".").absolutePath}")
    }
}
