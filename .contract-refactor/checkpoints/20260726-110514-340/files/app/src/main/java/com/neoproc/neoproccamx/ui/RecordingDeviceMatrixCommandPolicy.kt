package com.neoproc.neoproccamx.ui

internal data class RecordingDeviceMatrixAdbCommand(
    val action: String,
    val component: String,
    val validationLabel: String,
    val arguments: List<String>
) {
    fun toPowerShellCommand(adbPath: String, serial: String): String =
        buildString {
            append("& '")
            append(adbPath.replace("'", "''"))
            append("' -s ")
            append(serial)
            append(' ')
            append(arguments.joinToString(separator = " ") { it.powerShellArgument() })
        }

    private fun String.powerShellArgument(): String =
        if (all { it.isLetterOrDigit() || it in ".:/_-+" }) {
            this
        } else {
            "'${replace("'", "''")}'"
        }
}

internal object RecordingDeviceMatrixCommandPolicy {
    const val DEBUG_ACTION = "com.neoproc.neoproccamx.DEBUG_COMMAND"

    fun renderBroadcast(
        row: RecordingDeviceMatrixRow,
        packageName: String
    ): RecordingDeviceMatrixAdbCommand {
        val component = "$packageName/.ui.DebugCommandReceiver"
        val extras = row.debugExtras
        val arguments = buildList {
            add("shell")
            add("am")
            add("broadcast")
            add("-a")
            add(DEBUG_ACTION)
            add("-n")
            add(component)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_PRESET, extras.preset)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_ACTION, extras.action)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_DURATION_MS, extras.durationMs)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VALIDATION_LABEL, extras.validationLabel)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_ASPECT, extras.aspect)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_COLOR_PROFILE, extras.colorProfile)
            addBooleanExtra(DebugCommandIntentReader.EXTRA_DEBUG_RAW, extras.raw)
            addBooleanExtra(DebugCommandIntentReader.EXTRA_DEBUG_LOG, extras.log)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VIDEO_HDR_MODE, extras.videoHdrMode)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VIDEO_COLOR_SPACE, extras.videoColorSpace)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VIDEO_RESOLUTION, extras.videoResolution)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VIDEO_FPS, extras.videoFps)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_VIDEO_MODE, extras.videoMode)
            addStringExtra(DebugCommandIntentReader.EXTRA_DEBUG_RAW_COMPRESSION, extras.rawCompression)
        }
        return RecordingDeviceMatrixAdbCommand(
            action = DEBUG_ACTION,
            component = component,
            validationLabel = row.validationLabel,
            arguments = arguments
        )
    }

    private fun MutableList<String>.addStringExtra(key: String, value: String?) {
        if (value.isNullOrBlank()) return
        add("--es")
        add(key)
        add(value)
    }

    private fun MutableList<String>.addBooleanExtra(key: String, value: Boolean?) {
        if (value == null) return
        add("--ez")
        add(key)
        add(value.toString())
    }
}
