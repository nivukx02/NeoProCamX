package com.neoproc.neoproccamx.pipeline

import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * LAYER 2: CINEMATIC RESOURCE ORCHESTRATION ENGINE (CROE)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Real-time tile-based scheduling system. Segments the frame spatially into a
 * quadtree layout of different processing tiers based on the Cinematic Value Map V(x,y).
 *
 * Solves a bounded Knapsack problem under a hard real-time latency budget (e.g., 33.3ms for 4K30).
 * Integrates dynamic thermal telemetry to gracefully scale back compute density during high-heat states.
 */
class ResourceOrchestrator(private val powerManager: PowerManager?) {

    companion object {
        private const val TAG = "ResourceOrchestrator"

        // Estimated compute cost weights per pixel for different tiers
        private const val COST_TIER3 = 4.5f  // Multi-frame, detail-enhanced, custom sharpen
        private const val COST_TIER2 = 1.5f  // Standard fast demosaic + mid sharpening
        private const val COST_TIER1 = 0.3f  // Bilinear/bokeh bypass (highly efficient)

        // Threshold boundaries matching CinematicValueField
        private const val THRESHOLD_TIER3 = 0.75f
        private const val THRESHOLD_TIER2 = 0.35f
    }

    data class Tile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        var tier: Int,
        val averageSaliency: Float,
        val pixelCount: Int = width * height
    ) {
        val cost: Float
            get() = pixelCount.toFloat() * when (tier) {
                3 -> COST_TIER3
                2 -> COST_TIER2
                else -> COST_TIER1
            }
    }

    /**
     * Spatially segments the frame and solves the compute allocation Knapsack problem.
     *
     * @param valueField The active CinematicValueField containing the V(x,y) saliency map.
     * @param frameWidth The processing frame width.
     * @param frameHeight The processing frame height.
     * @param targetBudgetMs Hard target processing time budget (e.g. 33.3f ms).
     * @return List of classified Tiles for the GPU pipeline dispatch.
     */
    fun orchestrate(
        valueField: CinematicValueField,
        frameWidth: Int,
        frameHeight: Int,
        targetBudgetMs: Float = 33.3f
    ): List<Tile> {
        // 1. Query thermal telemetry to dynamically adjust normalized capacity
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager?.getThermalHeadroom(10) ?: 0.0f
        } else {
            0.0f
        }

        // Adjust processing capacity based on thermal state:
        // headroom >= 1.0 indicates thermal throttling, we aggressively scale down capacity.
        val thermalThrottleMultiplier = when {
            thermalHeadroom >= 0.95f -> 0.30f
            thermalHeadroom >= 0.85f -> 0.60f
            thermalHeadroom >= 0.70f -> 0.85f
            else -> 1.0f
        }

        // Normalized compute capacity budget corresponding to the physical millisecond budget
        val rawCapacityBudget = (frameWidth * frameHeight).toFloat() * COST_TIER2 * (targetBudgetMs / 33.3f)
        val capacityBudget = rawCapacityBudget * thermalThrottleMultiplier

        // 2. Generate Initial Quadrant Tiles
        // We start with a base 16x16 grid and construct hierarchical quadtree blocks
        val gridW = valueField.gridWidth
        val gridH = valueField.gridHeight
        val tilePixelW = frameWidth / gridW
        val tilePixelH = frameHeight / gridH

        val initialTiles = ArrayList<Tile>()

        for (gy in 0 until gridH step 2) {
            for (gx in 0 until gridW step 2) {
                // Read 2x2 sub-block in CinematicValueField to determine if we merge or sub-divide
                val v00 = valueField.getValueAt(gx, gy)
                val v10 = valueField.getValueAt(gx + 1, gy)
                val v01 = valueField.getValueAt(gx, gy + 1)
                val v11 = valueField.getValueAt(gx + 1, gy + 1)
                val avgV = (v00 + v10 + v01 + v11) / 4f

                val xPx = gx * tilePixelW
                val yPx = gy * tilePixelH
                val wPx = tilePixelW * 2
                val hPx = tilePixelH * 2

                if (avgV < THRESHOLD_TIER2) {
                    // Low saliency: Merge into a single large Tier 1 Bokeh-bypass Tile (128x128 approx)
                    initialTiles.add(Tile(xPx, yPx, wPx, hPx, tier = 1, averageSaliency = avgV))
                } else if (avgV >= THRESHOLD_TIER3) {
                    // Very high saliency: Divide into four fine 16x16 micro-tiles of Tier 3
                    initialTiles.add(Tile(gx * tilePixelW, gy * tilePixelH, tilePixelW, tilePixelH, tier = 3, averageSaliency = v00))
                    initialTiles.add(Tile((gx + 1) * tilePixelW, gy * tilePixelH, tilePixelW, tilePixelH, tier = 3, averageSaliency = v10))
                    initialTiles.add(Tile(gx * tilePixelW, (gy + 1) * tilePixelH, tilePixelW, tilePixelH, tier = 3, averageSaliency = v01))
                    initialTiles.add(Tile((gx + 1) * tilePixelW, (gy + 1) * tilePixelH, tilePixelW, tilePixelH, tier = 3, averageSaliency = v11))
                } else {
                    // Mid saliency: Retain as standard Tier 2 Tiles (32x32 approx)
                    initialTiles.add(Tile(xPx, yPx, wPx, hPx, tier = 2, averageSaliency = avgV))
                }
            }
        }

        // 3. Solve Bounded Knapsack (Greedy Approach)
        // Sort tiles by Saliency descending. Allocate Tier 3 where possible.
        // If the total cost exceeds our budget, degrade peripheral lower-saliency tiles to Tier 2 or Tier 1.
        val sortedTiles = initialTiles.sortedByDescending { it.averageSaliency }
        var currentCost = sortedTiles.sumOf { it.cost.toDouble() }.toFloat()

        for (tile in sortedTiles) {
            if (currentCost <= capacityBudget) break

            // If we are over budget, downgrade Tier 3 to Tier 2, or Tier 2 to Tier 1
            if (tile.tier == 3) {
                tile.tier = 2
                currentCost -= tile.pixelCount.toFloat() * (COST_TIER3 - COST_TIER2)
            } else if (tile.tier == 2) {
                tile.tier = 1
                currentCost -= tile.pixelCount.toFloat() * (COST_TIER2 - COST_TIER1)
            }
        }

        if (thermalHeadroom >= 0.85f && Math.random() < 0.05) {
            Log.w(TAG, "Thermal headroom elevated: ${"%.2f".format(thermalHeadroom)}, cost-limited to ${capacityBudget / rawCapacityBudget * 100}% cap")
        }

        return sortedTiles
    }
}
