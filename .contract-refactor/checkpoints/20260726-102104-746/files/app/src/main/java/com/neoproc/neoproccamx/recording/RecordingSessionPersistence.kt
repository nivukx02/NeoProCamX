package com.neoproc.neoproccamx.recording

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.util.UUID

data class RecordingSessionState(
    val sessionId: String = UUID.randomUUID().toString(),
    val startTimeMs: Long = System.currentTimeMillis(),
    val outputPath: String = "",
    val videoDurationMs: Long = 0L,
    val audioEnabled: Boolean = true,
    val videoCodec: String = "H.264",
    val videoBitrateMbps: Int = 100,
    val audioSampleRate: Int = 48000,
    val audioChannels: Int = 2,
    val frameRate: Int = 25,
    val resolution: String = "1920x1080",
    val crashed: Boolean = false,
    val recoveryAttempted: Boolean = false
) {
    val ageMs: Long get() = System.currentTimeMillis() - startTimeMs
}

object RecordingSessionPersistence {
    private const val PREFS_NAME = "recording_session_state"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_START_TIME_MS = "start_time_ms"
    private const val KEY_OUTPUT_PATH = "output_path"
    private const val KEY_VIDEO_DURATION_MS = "video_duration_ms"
    private const val KEY_AUDIO_ENABLED = "audio_enabled"
    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_VIDEO_BITRATE_MBPS = "video_bitrate_mbps"
    private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
    private const val KEY_AUDIO_CHANNELS = "audio_channels"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_CRASHED = "crashed"
    private const val KEY_RECOVERY_ATTEMPTED = "recovery_attempted"
    private const val MAX_SESSION_AGE_MS = 24 * 3600 * 1000L

    fun saveSession(context: Context, state: RecordingSessionState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SESSION_ID, state.sessionId)
            putLong(KEY_START_TIME_MS, state.startTimeMs)
            putString(KEY_OUTPUT_PATH, state.outputPath)
            putLong(KEY_VIDEO_DURATION_MS, state.videoDurationMs)
            putBoolean(KEY_AUDIO_ENABLED, state.audioEnabled)
            putString(KEY_VIDEO_CODEC, state.videoCodec)
            putInt(KEY_VIDEO_BITRATE_MBPS, state.videoBitrateMbps)
            putInt(KEY_AUDIO_SAMPLE_RATE, state.audioSampleRate)
            putInt(KEY_AUDIO_CHANNELS, state.audioChannels)
            putInt(KEY_FRAME_RATE, state.frameRate)
            putString(KEY_RESOLUTION, state.resolution)
            putBoolean(KEY_CRASHED, state.crashed)
            putBoolean(KEY_RECOVERY_ATTEMPTED, state.recoveryAttempted)
        }
    }

    fun loadSession(context: Context): RecordingSessionState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startTimeMs = prefs.getLong(KEY_START_TIME_MS, 0L)
        if (startTimeMs == 0L) return null
        
        val state = RecordingSessionState(
            sessionId = prefs.getString(KEY_SESSION_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString(),
            startTimeMs = startTimeMs,
            outputPath = prefs.getString(KEY_OUTPUT_PATH, "") ?: "",
            videoDurationMs = prefs.getLong(KEY_VIDEO_DURATION_MS, 0L),
            audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true),
            videoCodec = prefs.getString(KEY_VIDEO_CODEC, "H.264") ?: "H.264",
            videoBitrateMbps = prefs.getInt(KEY_VIDEO_BITRATE_MBPS, 100),
            audioSampleRate = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, 48000),
            audioChannels = prefs.getInt(KEY_AUDIO_CHANNELS, 2),
            frameRate = prefs.getInt(KEY_FRAME_RATE, 25),
            resolution = prefs.getString(KEY_RESOLUTION, "1920x1080") ?: "1920x1080",
            crashed = prefs.getBoolean(KEY_CRASHED, false),
            recoveryAttempted = prefs.getBoolean(KEY_RECOVERY_ATTEMPTED, false)
        )
        
        if (state.ageMs > MAX_SESSION_AGE_MS) {
            clearSession(context)
            return null
        }
        return state
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { clear() }
    }

    fun markCrashed(context: Context) {
        val session = loadSession(context)?.copy(crashed = true) ?: return
        saveSession(context, session)
    }

    fun canRecover(context: Context): Boolean {
        val session = loadSession(context) ?: return false
        return session.crashed && !session.recoveryAttempted && session.ageMs < MAX_SESSION_AGE_MS
    }

    fun markRecoveryAttempted(context: Context) {
        val session = loadSession(context)?.copy(recoveryAttempted = true) ?: return
        saveSession(context, session)
    }

    fun createBackupPath(context: Context, originalPath: String): String {
        val backupDir = File(context.filesDir, "recording_backups")
        backupDir.mkdirs()
        val backup = File(backupDir, "backup_${System.currentTimeMillis()}.mp4")
        return backup.absolutePath
    }

    fun listOrphanedBackups(context: Context): List<File> {
        val backupDir = File(context.filesDir, "recording_backups")
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles()?.filter {
            it.isFile && System.currentTimeMillis() - it.lastModified() > MAX_SESSION_AGE_MS
        } ?: emptyList()
    }

    fun cleanupOrphanedBackups(context: Context) {
        listOrphanedBackups(context).forEach { it.delete() }
    }
}
