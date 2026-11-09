package com.neoproc.neoproccamx.rendering

import android.content.Context
import android.os.Build

data class NeuralFlowEstimatorStatus(
    val state: String,
    val reason: String,
    val inferenceMs: Float,
    val confidence: Float,
    val zeroCopy: Boolean
) {
    companion object {
        fun parse(raw: String): NeuralFlowEstimatorStatus {
            val fields = raw
                .split(';')
                .mapNotNull { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) null else item.substring(0, index) to item.substring(index + 1)
                }
                .toMap()
            return NeuralFlowEstimatorStatus(
                state = fields["state"].orEmpty(),
                reason = fields["reason"].orEmpty(),
                inferenceMs = fields["inferenceMs"]?.toFloatOrNull() ?: Float.POSITIVE_INFINITY,
                confidence = fields["confidence"]?.toFloatOrNull() ?: 0f,
                zeroCopy = fields["zeroCopy"].toBoolean()
            )
        }
    }
}

object NeuralFlowEstimatorBridge {
    private const val DEFAULT_MODEL_ASSET = OpticalFlowModelAssetPolicy.DEFAULT_ASSET_PATH

    init {
        System.loadLibrary("neoproc_vulkan")
    }

    fun modelAssetPresent(
        context: Context,
        assetPath: String = DEFAULT_MODEL_ASSET
    ): Boolean =
        validateModelAsset(context, assetPath).valid

    fun validateModelAsset(
        context: Context,
        assetPath: String = DEFAULT_MODEL_ASSET
    ): OpticalFlowModelAssetValidation =
        runCatching {
            context.assets.open(assetPath).use { input ->
                OpticalFlowModelAssetPolicy.validateStream(
                    assetPath = assetPath,
                    fileName = assetPath.substringAfterLast('/'),
                    input = input
                )
            }
        }.getOrElse {
            OpticalFlowModelAssetValidation(
                valid = false,
                reason = "model missing",
                assetPath = assetPath,
                sizeBytes = 0L,
                sha256 = "",
                tfliteFlatbuffer = false
            )
        }

    fun configure(
        modelPath: String,
        modelAssetPresent: Boolean,
        nnapiDelegatePreferred: Boolean,
        inputSize: Int = 256,
        maxInferenceMs: Float = 3.0f
    ): NeuralFlowEstimatorStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return NeuralFlowEstimatorStatus(
                state = "FALLBACK_UNSUPPORTED",
                reason = "api<29",
                inferenceMs = Float.POSITIVE_INFINITY,
                confidence = 0f,
                zeroCopy = false
            )
        }
        return NeuralFlowEstimatorStatus.parse(
            nativeConfigure(
                modelPath,
                modelAssetPresent,
                nnapiDelegatePreferred,
                inputSize,
                maxInferenceMs
            )
        )
    }

    fun estimateAsync(
        currentNativeBufferHandle: Long,
        previousNativeBufferHandle: Long,
        width: Int,
        height: Int,
        timestampNs: Long
    ): NeuralFlowEstimatorStatus =
        NeuralFlowEstimatorStatus.parse(
            nativeEstimateAsync(
                currentNativeBufferHandle,
                previousNativeBufferHandle,
                width,
                height,
                timestampNs
            )
        )

    fun status(): NeuralFlowEstimatorStatus =
        NeuralFlowEstimatorStatus.parse(nativeStatus())

    private external fun nativeConfigure(
        modelPath: String,
        modelAssetPresent: Boolean,
        nnapiDelegatePreferred: Boolean,
        inputSize: Int,
        maxInferenceMs: Float
    ): String

    private external fun nativeEstimateAsync(
        currentNativeBufferHandle: Long,
        previousNativeBufferHandle: Long,
        width: Int,
        height: Int,
        timestampNs: Long
    ): String

    private external fun nativeStatus(): String
}
