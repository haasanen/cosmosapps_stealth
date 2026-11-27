package com.cosmos.unreddit.ui.postlist

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TEMP: blank-launch diagnostics (removed once the root cause is found).
 *
 * Two channels, because one of them may be dead depending on where launch stalls:
 *  1. Logcat tag [TAG] (`adb logcat -s FeedDbg`).
 *  2. A plain-text file in the app's external files dir (works even if no frame
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
    private var logFile: File? = null

    val refreshCalls = AtomicInteger(0)
    val fanOutEmissions = AtomicInteger(0)
    val feedStates = AtomicInteger(0)
    val legacyEmissions = AtomicInteger(0)
    val lastTrigger = AtomicReference("none")
    val lastRefreshArgs = AtomicReference("none")

    /**
     * File-size governance (owner report 2026-11-27: launch_diag.txt reached
     * 84.5 MB — the file is a diagnostic an owner is expected to share, so
     * unbounded growth defeats its own purpose). At init: if the file exceeds
     * [MAX_FILE_BYTES], keep only the last [KEEP_TAIL_BYTES] (rotating away
     * the oldest evidence, newest first is what any investigation reads).
     * During a session: stop appending after [SESSION_BYTES_MAX] written —
     * logcat keeps flowing, the file stays shareable.
     */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_TAIL_BYTES = 512L * 1024
    private const val SESSION_BYTES_MAX = 1024L * 1024
    private val sessionBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val fileCapped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Call once from Application.onCreate, before any other milestone. */
    fun init(context: Context) {
        try {
            logFile = File(context.getExternalFilesDir(null), "launch_diag.txt")
            try {
                logFile?.let { f ->
                    if (f.exists() && f.length() > MAX_FILE_BYTES) keepTail(f)
                }
            } catch (t: Throwable) {
                // Rotation is best-effort; a failed rotate only costs space.
            }
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

    /** Rewrite [f] to contain only its last [KEEP_TAIL_BYTES], line-aligned. */
    private fun keepTail(f: File) {
        val tail = java.io.RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(0L, raf.length() - KEEP_TAIL_BYTES)
            raf.seek(start)
            var b = raf.read()
            while (b != -1 && b != '\n'.code) b = raf.read() // land on a line start
            val rest = (raf.length() - raf.filePointer).toInt()
            val buf = ByteArray(rest)
            if (rest > 0) raf.readFully(buf)
            buf
        }
        f.writer().buffered().use { w ->
            w.write("=== rotated: older content dropped ===\n")
            w.write(String(tail, Charsets.UTF_8))
        }
    }

    fun log(msg: String) {
        val now = System.currentTimeMillis()
        val line = "[${now - t0}ms] $msg"
        try {
            Log.i(TAG, line)
        } catch (t: Throwable) {
            // Off-device (JVM unit tests): no Log impl.
        }
        try {
            val f = logFile
            if (f != null && !fileCapped.get()) {
                f.appendText(line + "\n")
                if (sessionBytes.addAndGet(line.length + 1L) > SESSION_BYTES_MAX) {
                    fileCapped.set(true)
                    f.appendText("=== session file budget reached; further lines logcat-only ===\n")
                }
            }
        } catch (t: Throwable) {
            // File channel is best-effort.
        }
    }

    /**
     * Log a throwable with class, message and a bounded stack trace. Message-less
     * exceptions (NullPointerException et al.) are invisible in a `class: message`
     * line alone — the trace is the only way to find the throwing line. The full
     * stack goes to the file; the log line keeps the first lines only.
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
}
