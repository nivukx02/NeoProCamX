package com.neoproc.neoproccamx.camera

import android.view.Surface
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAMERA SESSION TARGETS
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade target validation and management with detailed diagnostics,
// session health monitoring, and intelligent fallback handling.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Target match result with detailed status.
 */
internal enum class TargetMatchStatus {
    /** All targets match session configuration */
    FULL_MATCH,
    /** Some targets match, partial functionality */
    PARTIAL_MATCH,
    /** No targets match - session invalid */
    NO_MATCH,
    /** Session not configured */
    SESSION_NOT_CONFIGURED,
    /** Targets contain invalid surfaces */
    TARGETS_INVALID
}

/**
 * Detailed target validation result.
 */
internal data class TargetValidationResult(
    val status: TargetMatchStatus,
    val matchedTargets: List<Surface>,
    val unmatchedTargets: List<Surface>,
    val invalidTargets: List<Surface>,
    val diagnosticMessage: String
)

/**
 * Session target health metrics.
 */
internal data class TargetHealthMetrics(
    val totalTargets: Int,
    val validTargets: Int,
    val configuredTargets: Int,
    val healthScore: Float, // 0.0 - 1.0
    val needsReconfiguration: Boolean
)

internal object CameraSessionTargets {
    private const val STANDARD_RECONFIGURE_HEALTH_THRESHOLD = 0.5f
    private const val STRICT_RECONFIGURE_HEALTH_THRESHOLD = 0.85f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun isSurfaceValid(surface: Surface): Boolean {
        val defaultValidity = !strictHighEndEnabled()
        return runCatching { surface.isValid }.getOrDefault(defaultValidity)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun targetsMatchSession(
        configuredTargets: Set<Surface>,
        requestedTargets: List<Surface>
    ): Boolean {
        if (configuredTargets.isEmpty()) return false
        val distinctTargets = requestedTargets.distinct()
        if (distinctTargets.isEmpty()) return false
        return distinctTargets.all { target ->
            isSurfaceValid(target) && configuredTargets.contains(target)
        }
    }
    
    /**
     * Validate targets with detailed results.
     */
    fun validateTargets(
        configuredTargets: Set<Surface>,
        requestedTargets: List<Surface>
    ): TargetValidationResult {
        if (configuredTargets.isEmpty()) {
            return TargetValidationResult(
                status = TargetMatchStatus.SESSION_NOT_CONFIGURED,
                matchedTargets = emptyList(),
                unmatchedTargets = requestedTargets,
                invalidTargets = emptyList(),
                diagnosticMessage = "No session targets configured"
            )
        }
        
        val distinctTargets = requestedTargets.distinct()
        if (distinctTargets.isEmpty()) {
            return TargetValidationResult(
                status = TargetMatchStatus.NO_MATCH,
                matchedTargets = emptyList(),
                unmatchedTargets = emptyList(),
                invalidTargets = emptyList(),
                diagnosticMessage = "No targets requested"
            )
        }
        
        val matched = mutableListOf<Surface>()
        val unmatched = mutableListOf<Surface>()
        val invalid = mutableListOf<Surface>()
        
        for (target in distinctTargets) {
            val isValid = isSurfaceValid(target)
            when {
                !isValid -> invalid.add(target)
                configuredTargets.contains(target) -> matched.add(target)
                else -> unmatched.add(target)
            }
        }
        
        val status = when {
            invalid.isNotEmpty() -> TargetMatchStatus.TARGETS_INVALID
            matched.size == distinctTargets.size -> TargetMatchStatus.FULL_MATCH
            matched.isNotEmpty() -> TargetMatchStatus.PARTIAL_MATCH
            else -> TargetMatchStatus.NO_MATCH
        }
        
        val message = buildDiagnosticMessage(matched.size, unmatched.size, invalid.size, distinctTargets.size)
        
        return TargetValidationResult(
            status = status,
            matchedTargets = matched,
            unmatchedTargets = unmatched,
            invalidTargets = invalid,
            diagnosticMessage = message
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HEALTH MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate session target health metrics.
     */
    fun calculateHealthMetrics(
        configuredTargets: Set<Surface>,
        activeTargets: List<Surface>
    ): TargetHealthMetrics {
        val totalTargets = configuredTargets.size
        val validTargets = configuredTargets.count { 
            isSurfaceValid(it)
        }
        val configuredInActive = activeTargets.count { configuredTargets.contains(it) }
        
        val healthScore = when {
            totalTargets == 0 -> 0f
            validTargets == 0 -> 0f
            else -> (validTargets.toFloat() / totalTargets) * 
                    (configuredInActive.toFloat() / activeTargets.size.coerceAtLeast(1))
        }
        
        return TargetHealthMetrics(
            totalTargets = totalTargets,
            validTargets = validTargets,
            configuredTargets = configuredInActive,
            healthScore = healthScore.coerceIn(0f, 1f),
            needsReconfiguration =
                validTargets < totalTargets ||
                    healthScore < if (strictHighEndEnabled()) {
                        STRICT_RECONFIGURE_HEALTH_THRESHOLD
                    } else {
                        STANDARD_RECONFIGURE_HEALTH_THRESHOLD
                    }
        )
    }
    
    /**
     * Check if immediate session reconfiguration is needed.
     */
    fun needsImmediateReconfiguration(
        configuredTargets: Set<Surface>,
        criticalTargets: List<Surface>
    ): Boolean {
        return criticalTargets.any { target ->
            !configuredTargets.contains(target) ||
                !isSurfaceValid(target)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Select best available targets when some are invalid.
     */
    fun selectFallbackTargets(
        configuredTargets: Set<Surface>,
        preferredTargets: List<Surface>,
        fallbackTargets: List<Surface>
    ): List<Surface> {
        // Try preferred targets first
        val validPreferred = preferredTargets.filter { target ->
            configuredTargets.contains(target) &&
                isSurfaceValid(target)
        }
        
        if (validPreferred.isNotEmpty()) return validPreferred
        
        // Fall back to alternates
        val validFallback = fallbackTargets.filter { target ->
            configuredTargets.contains(target) &&
                isSurfaceValid(target)
        }
        
        return validFallback
    }
    
    /**
     * Get minimal valid target set for emergency operation.
     */
    fun getMinimalValidTargets(
        configuredTargets: Set<Surface>,
        priorityOrder: List<Surface>
    ): List<Surface> {
        for (target in priorityOrder) {
            if (configuredTargets.contains(target) &&
                isSurfaceValid(target)
            ) {
                return listOf(target)
            }
        }
        
        // Last resort - any valid configured target
        return configuredTargets
            .filter(::isSurfaceValid)
            .take(1)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SURFACE SET OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate difference between configured and requested targets.
     */
    fun calculateTargetDelta(
        configuredTargets: Set<Surface>,
        requestedTargets: Set<Surface>
    ): Pair<Set<Surface>, Set<Surface>> {
        val toAdd = requestedTargets - configuredTargets
        val toRemove = configuredTargets - requestedTargets
        return toAdd to toRemove
    }
    
    /**
     * Check if target set change requires session rebuild.
     */
    fun requiresSessionRebuild(
        currentTargets: Set<Surface>,
        newTargets: Set<Surface>
    ): Boolean {
        // Any removed targets require rebuild
        if ((currentTargets - newTargets).isNotEmpty()) return true
        
        // Adding targets may require rebuild depending on count
        val addedTargets = newTargets - currentTargets
        return addedTargets.isNotEmpty()
    }
    
    /**
     * Merge target sets with validation.
     */
    fun mergeTargetSets(
        vararg targetSets: List<Surface>
    ): List<Surface> {
        return targetSets
            .flatMap { it }
            .distinct()
            .filter(::isSurfaceValid)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildDiagnosticMessage(
        matched: Int,
        unmatched: Int,
        invalid: Int,
        total: Int
    ): String {
        return buildString {
            append("$matched/$total targets configured")
            if (unmatched > 0) append(", $unmatched not in session")
            if (invalid > 0) append(", $invalid invalid/released")
            if (strictHighEndEnabled()) append(", strict-high-end")
        }
    }
}
