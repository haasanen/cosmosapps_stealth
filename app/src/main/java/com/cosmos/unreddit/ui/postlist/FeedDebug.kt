package com.cosmos.unreddit.ui.postlist

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostic logging channel: every line goes to the SYSTEM LOG (logcat) under
 * tag [TAG] — `adb logcat -s FeedDbg`, or the OS built-in per-app log export
 * (GrapheneOS shares app logs without a dev machine).
 *
 * History: this object also used to append a private file
 * (Android/data/.../launch_diag.txt), because logcat's buffer rotates in
 * minutes and the old blank-launch investigation needed a trail surviving
 * restarts and pre-first-frame stalls. That stall is fixed and the file once
 * reached 84.5 MB, so the owner ruled on 2026-11-27: system log only, the
 * custom file is gone. The ms-since-app-start prefix stays: the GAP between
 * two milestones is still how a stall or slow fetch is located.
 *
 * Owner rule 2026-11-27 (NEVER swallow): every catch logs its throwable with
 * the FULL stack via [logException] — logcat carries traces natively.
 *
 * JVM-safe: unit tests may construct classes that call [log]; the timestamp and
 * logcat call degrade to no-ops off-device.
 */
object FeedDebug {

    const val TAG = "FeedDbg"

    private val t0 = System.currentTimeMillis()

    val refreshCalls = AtomicInteger(0)
    val fanOutEmissions = AtomicInteger(0)
    val feedStates = AtomicInteger(0)
    val legacyEmissions = AtomicInteger(0)
    val lastTrigger = AtomicReference("none")
    val lastRefreshArgs = AtomicReference("none")

    /**
     * Call once from Application.onCreate, before any other milestone.
     * Stamps the build identity: two releases can carry the same version name,
     * so a log without a version code cannot tell which build produced it
     * (2026-09-02: v29 and v30 both read "2.5.0").
     */
    fun init(context: Context) {
        log(
            "=== app start, build ${com.cosmos.unreddit.BuildConfig.VERSION_NAME}" +
                " (${com.cosmos.unreddit.BuildConfig.VERSION_CODE}) ==="
        )
    }

    fun log(msg: String) {
        val line = "[${System.currentTimeMillis() - t0}ms] $msg"
        try {
            Log.i(TAG, line)
        } catch (t: Throwable) {
            // Off-device (JVM unit tests): no Log impl. The system log IS the
            // channel now; with no Log there is nowhere left to report — a
            // unit-test environment, where that is acceptable by definition.
        }
    }

    /**
     * Log a throwable with class, message and FULL stack trace: message-less
     * exceptions (NullPointerException et al.) are invisible in a
     * `class: message` line alone — the trace is the only way to find the
     * throwing line. The throwable overload hands the complete trace to
     * logcat, which paginates continuation lines correctly (one raw Log.i
     * string would hit logcat's ~4 KB per-entry cap and truncate).
     */
    fun logException(prefix: String, t: Throwable) {
        val line = "[${System.currentTimeMillis() - t0}ms] $prefix: " +
            "${t.javaClass.name} — ${t.message ?: "(no message)"}"
        try {
            Log.i(TAG, line, t)
        } catch (u: Throwable) {
            // Off-device (JVM unit tests): no Log impl.
        }
    }
}
