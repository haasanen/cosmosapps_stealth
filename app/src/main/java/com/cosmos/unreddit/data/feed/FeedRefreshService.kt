package com.cosmos.unreddit.data.feed

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cosmos.unreddit.MainActivity
import com.cosmos.unreddit.R
import com.cosmos.unreddit.util.extension.createNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot foreground service that keeps a feed refresh running in the
 * background (v2.5.54).
 *
 * Design (per the 2026-09-06 ask): a pull-to-refresh that the user leaves —
 * app switch, another screen, post detail — must CONTINUE until complete or
 * external failure. It must NOT wait for the app to come back to the
 * foreground, and it must NOT run on any schedule. The mechanism:
 *
 *  - while this service is foregrounded, Android keeps the process (and
 *    therefore the refresh coroutines AND the process's network) alive. A
 *    plain backgrounded process on this device gets its network suspended,
 *    which is what burned the v2.5.52 retry budget with 153 x
 *    UnknownHostException while the user was in another app.
 *  - it shows ONE temporary notification (LOW importance: no sound) whose
 *    text follows the refresh. It is a transient status line: the final
 *    state is shown for a couple of seconds, then the notification is
 *    dismissed and the service stops itself. There is no lingering tile.
 *  - it is started ONCE per refresh episode (the fan-out, and any failed-sub
 *    retry chain that follows it) and always stops itself: on completion,
 *    on budget exhaustion, or on a confirmed CF block. It never polls and
 *    never reschedules.
 *
 * If the process is killed anyway (user swipe, low memory), the pending
 * retry is already persisted to disk by [FeedCoordinator]; the next launch
 * restores it and restarts this service.
 *
 * Hilt singleton: [FeedCoordinator] (also a singleton) holds the one
 * instance.
 */
@AndroidEntryPoint
class FeedRefreshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val finishJob = Job()
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance.set(this)
        ensureChannel()
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: applicationContext.getString(R.string.feed_refresh_started)
        val finishing = intent?.getBooleanExtra(EXTRA_FINISH, false) == true
        if (!foregrounded) {
            // Within ~5 s of startForegroundService, or Android kills us.
            startForeground(NOTIFICATION_ID, buildNotification(message))
            foregrounded = true
        } else {
            showNotification(message)
        }
        if (finishing) {
            stopAfterDelay()
        }
        return START_NOT_STICKY
    }

    /** Swiping the app away kills the service; the persisted pending retry covers resume. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        cancelNotification()
        stopSelf()
    }

    override fun onDestroy() {
        finishJob.cancel()
        scope.cancel()
        instance.set(null)
        super.onDestroy()
    }

    private fun showNotification(message: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

    private fun cancelNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    }

    /** Show the final message for a moment, then dismiss the notification and stop. */
    private fun stopAfterDelay() {
        finishJob.cancel()
        finishJob.apply {
            scope.launch {
                delay(FINISH_DELAY_MS)
                cancelNotification()
                stopSelf()
            }
        }
    }

    private fun ensureChannel() {
        val context = applicationContext
        context.createNotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.feed_refresh_channel_name),
            context.getString(R.string.feed_refresh_channel_description),
            NotificationManagerCompat.IMPORTANCE_LOW
        )
    }

    private fun buildNotification(message: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false) // transient: the user may dismiss it; the refresh continues
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 4242
        private const val EXTRA_MESSAGE = "msg"
        private const val EXTRA_FINISH = "finish"
        private const val CHANNEL_ID = "feed_refresh"
        private const val FINISH_DELAY_MS = 2_500L

        private val instance = AtomicReference<FeedRefreshService?>()

        /**
         * Start (or refresh) the background-refresh notification with [message].
         * Safe to call repeatedly for one episode: the first call starts the
         * service, later calls just update the text.
         */
        fun notify(context: Context, message: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, FeedRefreshService::class.java)
                        .putExtra(EXTRA_MESSAGE, message)
                )
            }
        }

        /**
         * Final state: show [message] briefly, then dismiss the notification and
         * stop the service. If the service is already gone (process death during
         * the episode), a fresh one is started just to show the final line.
         */
        fun finish(context: Context, message: String) {
            val intent = Intent(context, FeedRefreshService::class.java)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_FINISH, true)
            runCatching {
                if (instance.get() != null) {
                    context.startService(intent)
                } else {
                    context.startForegroundService(intent)
                }
            }
        }
    }
}
