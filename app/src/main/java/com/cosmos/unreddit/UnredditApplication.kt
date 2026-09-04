package com.cosmos.unreddit

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cosmos.unreddit.data.model.preferences.UiPreferences
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.worker.FeedRefreshWorker
import com.cosmos.unreddit.util.FileUncaughtExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class UnredditApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    var appTheme: Int = -1
        set(mode) {
            field = if (!UiPreferences.NightMode.isAmoled(mode)) {
                AppCompatDelegate.setDefaultNightMode(mode)
                R.style.AppTheme
            } else {
                // Force dark mode when amoled is set
                AppCompatDelegate.setDefaultNightMode(UiPreferences.NightMode.DARK.mode)
                R.style.AmoledAppTheme
            }
        }

    override fun onCreate() {
        super.onCreate()
        com.cosmos.unreddit.ui.postlist.FeedDebug.init(this)
        com.cosmos.unreddit.ui.postlist.FeedDebug.log("Application.onCreate: begin")

        val dsT0 = SystemClock.elapsedRealtime()
        try {
            val nightMode = runBlocking { preferencesRepository.getNightMode().first() }
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("DataStore nightMode read: ${nightMode} (${SystemClock.elapsedRealtime() - dsT0}ms)")
            appTheme = nightMode
        } catch (t: Throwable) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("DataStore nightMode read FAILED: $t")
        }

        Thread.setDefaultUncaughtExceptionHandler(object : Thread.UncaughtExceptionHandler {
            private val inner = FileUncaughtExceptionHandler(this@UnredditApplication)
            override fun uncaughtException(t: Thread, e: Throwable) {
                com.cosmos.unreddit.ui.postlist.FeedDebug.log("UNCAUGHT on ${t.name}: ${e.javaClass.name}: ${e.message}")
                // TEMP: full cause chain — the outer frame alone never identifies the root cause.
                var cause: Throwable? = e.cause
                var depth = 1
                while (cause != null && depth <= 8) {
                    val st = cause.stackTrace
                    com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                        "  CAUSE#$depth ${cause.javaClass.name}: ${cause.message} " +
                                "at ${st.take(4).joinToString(" / ") { "${it.className}.${it.methodName}" }}"
                    )
                    cause = cause.cause
                    depth++
                }
                com.cosmos.unreddit.ui.postlist.FeedDebug.log("    at ${e.stackTraceOrNull(3)}")
                inner.uncaughtException(t, e)
            }
        })
        // ISSUE C: durable periodic feed refresh so the cache warms while the app is
        // closed (survives process death). No-op internally unless the official
        // source is active. KEEP policy: enqueuing here never resets an existing
        // schedule.
        try {
            FeedRefreshWorker.ensureScheduled(this)
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("feed worker: ensureScheduled ok")
        } catch (t: Throwable) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("feed worker: ensureScheduled FAILED: $t")
        }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log("Application.onCreate: done")
    }

    private fun Throwable.stackTraceOrNull(n: Int): String {
        val st = stackTrace ?: return "(no stack)"
        return st.take(n).joinToString(" / ") { "${it.className}.${it.methodName}" }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}
