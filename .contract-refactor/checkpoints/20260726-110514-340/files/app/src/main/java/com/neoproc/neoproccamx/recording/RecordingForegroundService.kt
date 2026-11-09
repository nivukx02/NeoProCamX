package com.neoproc.neoproccamx.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

class RecordingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "RecordingFgService"
        private const val CHANNEL_ID = "camx_recording"
        private const val CHANNEL_NAME = "CamX Recording"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        private fun strictHighEndEnabled(): Boolean =
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

        fun buildIntent(
            context: Context,
            title: String = "Recording in progress",
            text: String = "CamX is capturing video"
        ): Intent = Intent(context, RecordingForegroundService::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
        }

        fun stop(context: Context) {
            context.stopService(buildIntent(context))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Recording in progress"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "CamX is capturing video"
        acquireWakeLock()
        startRecordingForeground(title, text)
        return START_NOT_STICKY
    }

    /**
     * Enter the foreground with a service type that matches the permissions actually held.
     *
     * The manifest declares `foregroundServiceType="camera|microphone"`. On Android 14+ the
     * 2-arg [startForeground] applies the *union* of the declared types, and starting the
     * `microphone` type without [Manifest.permission.RECORD_AUDIO] throws a SecurityException
     * that would crash the process during a video-only (mic-denied) recording. We therefore
     * advertise only the types we are actually entitled to: always `camera` (guaranteed granted
     * on any recording path), plus `microphone` whenever RECORD_AUDIO is held — so audio
     * recording is never downgraded, only the unusable mic *type* is dropped when denied. A
     * backstop try/catch converts any residual platform rejection into a clean stop instead of
     * a fatal crash.
     */
    @SuppressLint("InlinedApi")
    private fun startRecordingForeground(title: String, text: String) {
        val hasAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (hasAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(title, text),
                fgsType
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed; stopping recording service", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            if (strictHighEndEnabled()) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground notification for active recording"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (strictHighEndEnabled()) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CamX:Recording"
        ).apply {
            setReferenceCounted(false)
            acquire(if (strictHighEndEnabled()) 3 * 60 * 60 * 1000L else 2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }
}
