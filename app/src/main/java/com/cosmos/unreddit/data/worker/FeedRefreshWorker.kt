package com.cosmos.unreddit.data.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cosmos.unreddit.BuildConfig
import com.cosmos.unreddit.MainActivity
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.feed.FeedCoordinator
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.util.extension.cancelNotification
import com.cosmos.unreddit.util.extension.createNotificationChannel
import com.cosmos.unreddit.util.extension.showNotification
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Headless feed refresh (ISSUE C).
 *
 * The [FeedCoordinator] is a process-lifetime singleton, so a refresh started from the UI
 * dies when the process is killed (user closes the app, OS reclaims memory). This worker
 * refreshes the local cache on a periodic, network-constrained, DURABLE schedule — the
 * work survives process death and WorkManager reschedules it — so the cache is warm
 * before the user reopens the app. While it runs it posts a notification (email-app
 * style) so the background activity is visible, not silent.
 *
 * Only meaningful for the official reddit.com source: the coordinator's progressive
 * feed (cache + per-subreddit atomic replace) is what this warms. For Arctic/Atom the
 * coordinator is not used, so the worker is a no-op.
 */
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: FeedCoordinator,
    private val preferencesRepository: PreferencesRepository,
    private val postListRepository: PostListRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // The coordinator only drives the official-source feed.
        val source = preferencesRepository.getRedditSource().first()
        if (source != DataPreferences.RedditSource.REDDIT_OFFICIAL.value) {
            return Result.success()
        }
        val profileId = preferencesRepository.getCurrentProfile().first()
        val subs = postListRepository.getSubscriptionsNames(profileId).first()
            .filter { it.isNotBlank() }
        if (subs.isEmpty()) return Result.success()

        val nsfw = preferencesRepository.getContentPreferences().first().showNsfw
        val ttlMs = preferencesRepository.getCacheTtlHours().first() * 3_600_000L
        val historyIds = postListRepository.getHistoryIds(profileId).first()
        val savedIds = postListRepository.getSavedPostIds(profileId).first()

        // Visible notification while the (potentially slow, CF-challenged) fan-out runs.
        // WorkManager 2.7.1: startForeground is NOT a Worker method (that landed in
        // 2.8.0), so the notification is posted/cancelled by hand around the cycle.
        val notification = createNotification(ctx)
        ctx.showNotification(FEED_REFRESH_NOTIFICATION_ID, notification)
        try {
            // manual = true: a background pass must hit the network even when a fresh-
            // enough cache exists (the UI's cache-first policy would skip the fan-out
            // and the worker would do nothing).
            val job = coordinator.refresh(
                profileId = profileId,
                subs = subs,
                sort = Sort.HOT,
                historyIds = historyIds,
                savedIds = savedIds,
                showNsfw = nsfw,
                ttlMs = ttlMs,
                manual = true
            )
            // Join as a suspend call: keeps this worker (and thus the process) alive
            // for the whole cycle; the coordinator does its own persistence.
            val done = withTimeoutOrNull(MAX_REFRESH_MS) { job.join() }
            return if (done == null) {
                // Timeout: the next periodic tick retries; the cache is unchanged.
                com.cosmos.unreddit.ui.postlist.FeedDebug.log("feed worker: refresh timed out")
                Result.retry()
            } else {
                Result.success()
            }
        } finally {
            ctx.cancelNotification(FEED_REFRESH_NOTIFICATION_ID)
        }
    }

    private fun createNotification(ctx: Context): android.app.Notification {
        ctx.createNotificationChannel(
            FEED_REFRESH_CHANNEL_ID,
            R.string.notification_feed_channel_name,
            R.string.notification_feed_channel_description,
            androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW
        )
        val contentIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(ctx, FEED_REFRESH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stealth)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.notification_feed_refreshing))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val WORK_NAME = "com.cosmos.unreddit.feed_refresh"
        private const val FEED_REFRESH_CHANNEL_ID =
            "${BuildConfig.APPLICATION_ID}.FEED_REFRESH_CHANNEL"
        private const val FEED_REFRESH_NOTIFICATION_ID = 731
        private const val MAX_REFRESH_MS = 10 * 60_000L // 10 min: CF-worst-case fan-out cap

        /**
         * Schedule (or re-schedule) the periodic refresh. Idempotent: [keep] preserves
         * the existing schedule when one is already queued, so app-start enqueues don't
         * reset the countdown.
         */
        fun ensureScheduled(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
                30, TimeUnit.MINUTES // WorkManager minimum periodic interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
