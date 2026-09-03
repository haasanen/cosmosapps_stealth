package com.cosmos.unreddit.ui.postlist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TEMP: blank-launch diagnostics (removed once the root cause is found).
 *
 * Three channels, because one of them may be dead depending on where launch stalls:
 *  1. An always-visible panel in the activity (top of screen) with the milestone
 *     trail and live counters — only works if the activity draws a frame.
 *  2. Logcat tag [TAG] (`adb logcat -s FeedDbg`).
 *  3. A plain-text file in the app's external files dir (works even if no frame
 *     is ever drawn — a main-thread stall before the first frame is itself a
 *     hypothesis, so the trail must survive it):
 *     /sdcard/Android/data/com.cosmos.unreddit/files/launch_diag.txt
 *
 * Milestones carry ms-since-app-start timestamps, so the GAP between two lines
 * shows exactly where launch is stuck.
 *
 * JVM-safe: unit tests may construct classes that call [log]; the timestamp and
 * logcat call degrade to no-ops off-device.
 */
object FeedDebug {

    const val TAG = "FeedDbg"

    private val t0 = System.currentTimeMillis()
    private val events = CopyOnWriteArrayList<String>()
    private var logFile: File? = null

    val refreshCalls = AtomicInteger(0)
    val fanOutEmissions = AtomicInteger(0)
    val feedStates = AtomicInteger(0)
    val legacyEmissions = AtomicInteger(0)
    val lastTrigger = AtomicReference("none")
    val lastRefreshArgs = AtomicReference("none")
    val lastSourcePref = AtomicReference("none")

    /** Call once from Application.onCreate, before any other milestone. */
    fun init(context: Context) {
        try {
            logFile = File(context.getExternalFilesDir(null), "launch_diag.txt")
            // Stamp the build identity: two releases can carry the same version name,
            // so a log without a version code cannot tell which build produced it
            // (2026-09-02: v29 and v30 both read "2.5.0").
            logFile?.appendText(
                "\n=== launch ${System.currentTimeMillis()} " +
                    "build ${com.cosmos.unreddit.BuildConfig.VERSION_NAME} " +
                    "(${com.cosmos.unreddit.BuildConfig.VERSION_CODE}) ===\n"
            )
        } catch (t: Throwable) {
            logFile = null
        }
    }

    fun log(msg: String) {
        val now = System.currentTimeMillis()
        val line = "[${now - t0}ms] $msg"
        events.add(line)
        if (events.size > 80) events.removeAt(0)
        try {
            Log.i(TAG, line)
        } catch (t: Throwable) {
            // Off-device (JVM unit tests): no Log impl. Keep the in-memory trail only.
        }
        try {
            logFile?.appendText(line + "\n")
        } catch (t: Throwable) {
            // File channel is best-effort.
        }
    }

    /**
     * Log a throwable with class, message and a bounded stack trace. Message-less
     * exceptions (NullPointerException et al.) are invisible in a `class: message`
     * line alone — the trace is the only way to find the throwing line. The full
     * stack goes to the file; the event list keeps the first lines only.
     */
    fun logException(prefix: String, t: Throwable) {
        val sw = java.io.StringWriter()
        try {
            t.printStackTrace(java.io.PrintWriter(sw))
        } catch (u: Throwable) {
            // printStackTrace is total; defensive only.
        }
        val full = sw.toString()
        val first = full.lineSequence().take(12).joinToString("\n") { it.take(300) }
        log("$prefix: ${t.javaClass.name} — ${t.message ?: "(no message)"}\n$first")
        try {
            logFile?.appendText(full + "\n")
        } catch (u: Throwable) {
            // File channel is best-effort.
        }
    }

    /** Starts updating [view] with the milestone trail. Main thread only. */
    fun startPanel(view: TextView) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                view.text = summary()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    private fun summary(): String {
        val tail = events.takeLast(9).joinToString("\n")
        return "DIAG (ms since launch)\n$tail\n" +
            "refresh=${refreshCalls.get()} fanoutPages=${fanOutEmissions.get()} " +
            "feedStates=${feedStates.get()} legacy=${legacyEmissions.get()} " +
            "src=${lastSourcePref.get()} args=${lastRefreshArgs.get()} trig=${lastTrigger.get()}"
    }
}
