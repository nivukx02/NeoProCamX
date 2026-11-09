package com.neoproc.neoproccamx.ui

internal data class RecordingDeviceMatrixLogClassification(
    val lines: List<String>,
    val platformNoise: List<String>,
    val blockingIssues: List<String>
)

internal object RecordingDeviceMatrixLogClassifier {
    private val platformNoisePatterns = listOf(
        Regex("VideoCapabilities: (Unsupported mime|Unrecognized profile)", RegexOption.IGNORE_CASE),
        Regex("AudioCapabilities: (Unsupported mediaType|Unsupported mime)", RegexOption.IGNORE_CASE),
        Regex("AString\\s*: ctor got NULL", RegexOption.IGNORE_CASE),
        Regex("MediaCodec: no metrics handle found", RegexOption.IGNORE_CASE),
        Regex("AIBinder_linkToDeath", RegexOption.IGNORE_CASE),
        Regex("ColorUtils: expected specified color aspects", RegexOption.IGNORE_CASE),
        Regex("Failed to query component interface for required system resources", RegexOption.IGNORE_CASE),
        Regex("GraphicBufferSource: released unpopulated slots", RegexOption.IGNORE_CASE),
        Regex("\\bVSPA\\b", RegexOption.IGNORE_CASE),
        Regex("\\bashmem\\b.*Pinning is deprecated", RegexOption.IGNORE_CASE),
        Regex("VivoJsonResourceManager: mmap ok", RegexOption.IGNORE_CASE),
        Regex("libEGL.*no current context", RegexOption.IGNORE_CASE),
        Regex("eglQueryStringImpl display is EGL_NO_DISPLAY", RegexOption.IGNORE_CASE),
        Regex("Access denied finding property", RegexOption.IGNORE_CASE),
        Regex("InteractionJankMonitor: Initializing without READ_DEVICE_CONFIG", RegexOption.IGNORE_CASE)
    )

    private val blockingPatterns = listOf(
        Regex("FATAL EXCEPTION", RegexOption.IGNORE_CASE),
        Regex("\\bAndroidRuntime\\b", RegexOption.IGNORE_CASE),
        Regex("RecordingManager:.*\\b(Failed|Exception|Error|error)\\b", RegexOption.IGNORE_CASE),
        Regex("CameraDevice.*\\berror\\b", RegexOption.IGNORE_CASE),
        Regex("CameraAccessException", RegexOption.IGNORE_CASE),
        Regex("MediaCodec.*CodecException", RegexOption.IGNORE_CASE)
    )

    fun classify(lines: List<String>): RecordingDeviceMatrixLogClassification {
        val platformNoise = mutableListOf<String>()
        val blockingIssues = mutableListOf<String>()
        lines.forEach { line ->
            when {
                platformNoisePatterns.any { it.containsMatchIn(line) } -> platformNoise += line
                blockingPatterns.any { it.containsMatchIn(line) } -> blockingIssues += line
            }
        }
        return RecordingDeviceMatrixLogClassification(
            lines = lines,
            platformNoise = platformNoise,
            blockingIssues = blockingIssues
        )
    }
}
