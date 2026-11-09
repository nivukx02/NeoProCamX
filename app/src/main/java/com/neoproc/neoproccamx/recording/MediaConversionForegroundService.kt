package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.neoproc.neoproccamx.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground execution owner for explicit, user-started media conversion.
 *
 * A partial wake lock keeps a sleeping device awake, but it does not prevent Android from moving a
 * background app into a restricted CPU set. Conversion is therefore represented to Android as the
 * work it really is: media processing on Android 15+, and data sync on older supported releases.
 * The caller waits for [ServiceCompat.startForeground] to succeed before beginning pixel work, so a
 * rejected foreground-service start can never be mistaken for protected conversion.
 */
internal class MediaConversionForegroundService : Service() {
    private var serviceToken = NO_TOKEN

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getLongExtra(EXTRA_TOKEN, NO_TOKEN) ?: NO_TOKEN
        if (intent?.action != ACTION_START || token == NO_TOKEN) {
            completeStart(token, started = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val started = runCatching {
            startMediaConversionForeground(buildNotification())
            serviceToken = token
            activeToken.set(token)
            Log.i(TAG, "Media-conversion foreground execution active token=$token")
            true
        }.onFailure { failure ->
            Log.e(TAG, "Android rejected media-conversion foreground execution", failure)
        }.getOrDefault(false)

        completeStart(token, started)
        if (!started) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeToken.compareAndSet(serviceToken, NO_TOKEN)
        serviceToken = NO_TOKEN
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Android timed out active media conversion; protection is no longer valid")
        activeToken.compareAndSet(serviceToken, NO_TOKEN)
        stopSelf(startId)
    }

    private fun ensureNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and execution protection for media conversion"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Converting media")
            .setContentText("NIV99 processing is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("InlinedApi")
    private fun startMediaConversionForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // AndroidX Core 1.17 masks foreground-service bits to 0x40000fff, which excludes the
            // API-35 mediaProcessing bit (0x2000) and becomes the prohibited type "none". Call the
            // version-gated platform API so Android receives the exact manifest-declared type.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private class StartRequest {
        val completion = CountDownLatch(1)
        val started = AtomicBoolean(false)
    }

    companion object {
        private const val TAG = "MediaConversionFgs"
        private const val CHANNEL_ID = "niv99_media_conversion"
        private const val CHANNEL_NAME = "Media conversion"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START =
            "com.neoproc.neoproccamx.action.START_MEDIA_CONVERSION"
        private const val EXTRA_TOKEN = "conversion_execution_token"
        private const val NO_TOKEN = 0L
        private const val START_TIMEOUT_SECONDS = 5L

        private val nextToken = AtomicLong(1L)
        private val activeToken = AtomicLong(NO_TOKEN)
        private val pendingStarts = ConcurrentHashMap<Long, StartRequest>()

        /** Starts the service and returns only after Android accepted foreground execution. */
        fun acquire(context: Context): Long {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Media conversion must acquire foreground execution off the main thread"
            }
            check(activeToken.get() == NO_TOKEN) {
                "A media-conversion foreground lease is already active"
            }
            val token = nextToken.getAndIncrement().let { if (it == NO_TOKEN) nextToken.getAndIncrement() else it }
            val request = StartRequest()
            check(pendingStarts.putIfAbsent(token, request) == null)
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, MediaConversionForegroundService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_TOKEN, token)
                )
                check(request.completion.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Timed out waiting for media-conversion foreground execution"
                }
                val reportedStarted = request.started.get()
                val reportedActiveToken = activeToken.get()
                check(reportedStarted && reportedActiveToken == token) {
                    "Android rejected media-conversion foreground execution " +
                        "(started=$reportedStarted activeToken=$reportedActiveToken requestToken=$token)"
                }
                return token
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                release(context, token)
                throw IllegalStateException(
                    "Interrupted while starting media-conversion foreground execution",
                    failure
                )
            } catch (failure: Throwable) {
                release(context, token)
                throw failure
            } finally {
                pendingStarts.remove(token, request)
            }
        }

        fun release(context: Context, token: Long) {
            pendingStarts.remove(token)?.let { request ->
                request.started.set(false)
                request.completion.countDown()
            }
            if (activeToken.compareAndSet(token, NO_TOKEN)) {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, MediaConversionForegroundService::class.java)
                )
            }
        }

        fun isActive(token: Long): Boolean = token != NO_TOKEN && activeToken.get() == token

        internal fun hasActiveLeaseForTest(): Boolean = activeToken.get() != NO_TOKEN

        private fun completeStart(token: Long, started: Boolean) {
            pendingStarts[token]?.let { request ->
                request.started.set(started)
                request.completion.countDown()
            }
        }
    }
}
