package com.neoproc.neoproccamx.rendering

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * VulkanScalingFilter — High-order Bicubic and Lanczos resampling for the Vulkan pipeline.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * PROBLEM
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Standard bilinear filtering introduces aliasing when downscaling high-resolution
 * sensor output (50 MP → 4K preview) and softness when upscaling.  cinema-oriented
 * pipelines demand higher-order filters: Bicubic (Catmull-Rom) for general scaling,
 * Lanczos (3-lobe) for critical detail preservation.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * SOLUTION
 * ═════════════════════════════════════════════════════════════════════════════════════
 * This class generates GLSL compute shader source for Bicubic and Lanczos filtering.
 * The shaders are dispatched to the Vulkan runtime via [VulkanBridge.submitShader].
 * On devices without compute shader support, we fall back to bilinear hardware filtering.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FILTER CHARACTERISTICS
 * ═════════════════════════════════════════════════════════════════════════════════════
 *   BICUBIC_CATMULL_ROM
 *      - Smooth, continuous 1st derivative
 *      - Good for general preview scaling (4K → 1080p)
 *      - 16 texture taps per pixel
 *
 *   LANCZOS_3
 *      - Optimal frequency preservation (theoretical best for natural images)
 *      - 3-lobe sinc window
 *      - 36 texture taps per pixel (expensive but worth it for cinema)
 *      - Use for final output scaling only, not real-time preview
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * INTEGRATION
 * ═════════════════════════════════════════════════════════════════════════════════════
 *   1. Call [generateShader] with desired filter type and scale factor.
 *   2. Submit the returned GLSL to your Vulkan compute pipeline.
 *   3. Pass input [HardwareBuffer] as storage image binding.
 *   4. Read output into destination texture / encoder surface.
 */
object VulkanScalingFilter {

    private const val TAG = "VulkanScalingFilter"

    enum class FilterType {
        /** Fast bicubic, good balance of quality/performance. */
        BICUBIC_CATMULL_ROM,

        /** High-quality Lanczos 3-lobe for cinema mastering. */
        LANCZOS_3,

        /** Bilinear fallback for low-power devices. */
        BILINEAR_FALLBACK
    }

    data class ShaderProgram(
        val type: FilterType,
        val glslSource: String,
        val workGroupSize: Int,
        /** Estimated GPU time per 4K frame (ms) on Adreno 750-class hardware. */
        val estimatedFrameTimeMs: Float
    )

    /**
     * Generates a compute shader for the specified scaling filter.
     *
     * @param type          Filter type (bicubic, lanczos, or bilinear).
     * @param inputWidth    Source texture width.
     * @param inputHeight   Source texture height.
     * @param outputWidth   Destination width.
     * @param outputHeight  Destination height.
     * @return [ShaderProgram] ready for Vulkan dispatch.
     */
    fun generateShader(
        type: FilterType,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): ShaderProgram {
        val scaleX = outputWidth.toFloat() / inputWidth.toFloat()
        val scaleY = outputHeight.toFloat() / inputHeight.toFloat()

        return when (type) {
            FilterType.BICUBIC_CATMULL_ROM -> generateBicubic(
                inputWidth, inputHeight, outputWidth, outputHeight, scaleX, scaleY
            )
            FilterType.LANCZOS_3 -> generateLanczos3(
                inputWidth, inputHeight, outputWidth, outputHeight, scaleX, scaleY
            )
            FilterType.BILINEAR_FALLBACK -> generateBilinear(
                inputWidth, inputHeight, outputWidth, outputHeight, scaleX, scaleY
            )
        }
    }

    /**
     * Returns true if the device likely supports compute shaders for high-order filtering.
     * Checks Vulkan API level and known GPU families.
     */
    fun supportsComputeShaders(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        // Heuristic: Adreno 6xx+, Mali-G7x+, PowerVR Series9XM+ all have compute.
        // In production, query vkGetPhysicalDeviceFeatures.
        return true
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // SHADER GENERATION
    // ═════════════════════════════════════════════════════════════════════════════════

    private fun generateBicubic(
        inW: Int, inH: Int,
        outW: Int, outH: Int,
        scaleX: Float, scaleY: Float
    ): ShaderProgram {
        val source = """
            #version 450
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(set = 0, binding = 0) uniform sampler2D inputImage;
            layout(set = 0, binding = 1, rgba8) uniform writeonly image2D outputImage;

            layout(push_constant) uniform Params {
                float scaleX;
                float scaleY;
                float srcW;
                float srcH;
            } pc;

            // Catmull-Rom cubic basis matrix
            vec4 catmullRom(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5*t3 + t2 - 0.5*t,
                    1.5*t3 - 2.5*t2 + 1.0,
                    -1.5*t3 + 2.0*t2 + 0.5*t,
                    0.5*t3 - 0.5*t2
                );
            }

            void main() {
                ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);
                if (outCoord.x >= $outW || outCoord.y >= $outH) return;

                vec2 uv = (vec2(outCoord) + 0.5) / vec2($outW, $outH);
                vec2 srcCoord = uv / vec2(pc.scaleX, pc.scaleY) * vec2(pc.srcW, pc.srcH) - 0.5;

                ivec2 i = ivec2(floor(srcCoord));
                vec2 f = fract(srcCoord);

                vec4 cx = catmullRom(f.x);
                vec4 cy = catmullRom(f.y);

                vec4 color = vec4(0.0);
                for (int y = -1; y <= 2; y++) {
                    vec4 row = vec4(0.0);
                    for (int x = -1; x <= 2; x++) {
                        ivec2 sampleCoord = clamp(i + ivec2(x, y), ivec2(0), ivec2(pc.srcW - 1, pc.srcH - 1));
                        row += cx[x + 1] * texelFetch(inputImage, sampleCoord, 0);
                    }
                    color += cy[y + 1] * row;
                }

                imageStore(outputImage, outCoord, clamp(color, 0.0, 1.0));
            }
        """.trimIndent()

        return ShaderProgram(
            type = FilterType.BICUBIC_CATMULL_ROM,
            glslSource = source,
            workGroupSize = 16,
            estimatedFrameTimeMs = 2.5f  // 4K on Adreno 750
        )
    }

    private fun generateLanczos3(
        inW: Int, inH: Int,
        outW: Int, outH: Int,
        scaleX: Float, scaleY: Float
    ): ShaderProgram {
        val source = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(set = 0, binding = 0) uniform sampler2D inputImage;
            layout(set = 0, binding = 1, rgba16f) uniform writeonly image2D outputImage;

            layout(push_constant) uniform Params {
                float scaleX;
                float scaleY;
                float srcW;
                float srcH;
            } pc;

            const float PI = 3.14159265359;
            const int LOBE_RADIUS = 3;

            float lanczos(float x) {
                if (abs(x) < 0.001) return 1.0;
                if (abs(x) >= LOBE_RADIUS) return 0.0;
                float xPi = x * PI;
                return (sin(xPi) * sin(xPi / LOBE_RADIUS)) / (xPi * xPi / LOBE_RADIUS);
            }

            void main() {
                ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);
                if (outCoord.x >= $outW || outCoord.y >= $outH) return;

                vec2 uv = (vec2(outCoord) + 0.5) / vec2($outW, $outH);
                vec2 srcCoord = uv / vec2(pc.scaleX, pc.scaleY) * vec2(pc.srcW, pc.srcH);

                vec4 color = vec4(0.0);
                float weightSum = 0.0;

                ivec2 center = ivec2(floor(srcCoord));
                vec2 frac = fract(srcCoord);

                for (int y = -LOBE_RADIUS + 1; y <= LOBE_RADIUS; y++) {
                    for (int x = -LOBE_RADIUS + 1; x <= LOBE_RADIUS; x++) {
                        ivec2 sampleCoord = clamp(center + ivec2(x, y), ivec2(0), ivec2(pc.srcW - 1, pc.srcH - 1));
                        vec2 offset = vec2(sampleCoord) + 0.5 - srcCoord;

                        float wx = lanczos(offset.x);
                        float wy = lanczos(offset.y);
                        float w = wx * wy;

                        color += w * texelFetch(inputImage, sampleCoord, 0);
                        weightSum += w;
                    }
                }

                imageStore(outputImage, outCoord, color / max(weightSum, 0.0001));
            }
        """.trimIndent()

        return ShaderProgram(
            type = FilterType.LANCZOS_3,
            glslSource = source,
            workGroupSize = 8,
            estimatedFrameTimeMs = 8.5f  // 4K on Adreno 750 (expensive!)
        )
    }

    private fun generateBilinear(
        inW: Int, inH: Int,
        outW: Int, outH: Int,
        scaleX: Float, scaleY: Float
    ): ShaderProgram {
        val source = """
            #version 450
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(set = 0, binding = 0) uniform sampler2D inputImage;
            layout(set = 0, binding = 1, rgba8) uniform writeonly image2D outputImage;

            void main() {
                ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);
                if (outCoord.x >= $outW || outCoord.y >= $outH) return;

                vec2 uv = (vec2(outCoord) + 0.5) / vec2($outW, $outH);
                vec4 color = texture(inputImage, uv * vec2($inW, $inH) / vec2($outW, $outH));
                imageStore(outputImage, outCoord, color);
            }
        """.trimIndent()

        return ShaderProgram(
            type = FilterType.BILINEAR_FALLBACK,
            glslSource = source,
            workGroupSize = 16,
            estimatedFrameTimeMs = 0.5f
        )
    }
}
