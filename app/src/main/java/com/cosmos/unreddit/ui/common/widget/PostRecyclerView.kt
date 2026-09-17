package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.ui.common.PostDividerItemDecoration
import com.cosmos.unreddit.ui.postlist.FeedDebug
import java.lang.reflect.Modifier

/**
 * [RecyclerView] that recovers from a leaked layout/scroll counter.
 *
 * RecyclerView 1.2.1 wraps every layout pass and scroll step in
 * `onEnterLayoutOrScroll()` / `onExitLayoutOrScroll()` WITHOUT try/finally. If anything throws
 * inside the pass (a binding exception during `onLayoutChildren`, a notify* mid-pass, ...), the
 * exit never runs and `mLayoutOrScrollCounter` stays elevated forever. The list then "computes a
 * layout" permanently and EVERY later notify* throws `IllegalStateException("Cannot call this
 * method while RecyclerView is computing a layout or scrolling")`. The observed shape
 * (r/outerwilds, build 2.5.81, 2026-09-16): first page renders, the NEXT page insert (a posted
 * looper task, 106ms after load-more) dies — a posted task can only see
 * `isComputingLayout()==true` because an earlier pass leaked the counter.
 *
 * Recovery: [ViewTreeObserver.OnGlobalLayoutListener] fires in a separate message AFTER the
 * layout pass has finished — a moment when the counter MUST be zero. If it is still elevated, a
 * pass leaked it. The reset is self-verifying: candidate int fields are zeroed one at a time and
 * only kept zero if that actually clears `super.isComputingLayout()`; anything else is restored,
 * so it works even in the R8 release build where the field is renamed.
 *
 * The known poison that leaks the counter (r/outerwilds, builds 2.5.79–2.5.82): every frosted
 * (spoiler) bind threw inside `onBindViewHolder` because `ViewExtKt.load`'s `error { … }`
 * resolved to the Kotlin stdlib `kotlin.error(…)` (Coil 2.2.2 has no `error{}` overload), i.e.
 * `throw IllegalStateException`. Fixed in 2.5.83 by switching to Coil's real
 * `listener(onError = …)`; this watchdog is the backstop for any future in-pass throw.
 * The listener is armed in [onAttachedToWindow] — registering in `init` orphans it, because the
 * pre-attach `ViewTreeObserver` is replaced on attach (which is why 2.5.82 logged nothing).
 */
class PostRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastResetAt = 0L

    private val counterWatch = Runnable {
        if (!isAttachedToWindow) return@Runnable
        if (!isComputingLayout()) return@Runnable
        // We are in an idle looper message: no layout/scroll code can be on the
        // stack, so an elevated counter here is a leak, not a pass in flight.
        // Throttled: a reset triggers a re-layout, which (while the poison is
        // still there) can leak the counter again — never reset faster than
        // once per 250ms so a persistent poison cannot pin the main thread in
        // a reset/re-layout loop.
        val now = SystemClock.uptimeMillis()
        if (now - lastResetAt < 250L) return@Runnable
        FeedDebug.log(
            "PostRecyclerView COUNTER STUCK at $this: a layout/scroll pass leaked " +
                "mLayoutOrScrollCounter (something threw inside the pass — see the first " +
                "exception logged in this launch, e.g. 'PullToRefreshLayout LAYOUT PASS " +
                "THREW'). Resetting."
        )
        if (resetStuckCounter()) {
            lastResetAt = SystemClock.uptimeMillis()
            requestLayout()
        }
    }

    /**
     * Zeros the int field(s) that keep [isComputingLayout] elevated. Self-verifying: a field is
     * only left at zero if doing so clears the flag; otherwise it is restored and the next
     * candidate is tried. Never touches fields the flag does not depend on.
     */
    private fun resetStuckCounter(): Boolean {
        for (f in RecyclerView::class.java.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
            f.isAccessible = true
            val old = try {
                f.getInt(this)
            } catch (t: Throwable) {
                continue
            }
            if (old <= 0) continue
            try {
                f.setInt(this, 0)
            } catch (t: Throwable) {
                continue
            }
            val cleared = !super.isComputingLayout()
            if (cleared) {
                FeedDebug.log("PostRecyclerView counter cleared via field '${f.name}' at $this — list recovers")
                return true
            }
            // Not the flag's field (or not the only one): restore and try the next candidate.
            try {
                f.setInt(this, old)
            } catch (t: Throwable) {
                // best effort
            }
        }
        FeedDebug.log("PostRecyclerView counter reset FAILED at $this — no candidate field cleared isComputingLayout")
        return false
    }

    private val globalLayoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            // Schedule the check a message later: onGlobalLayout itself is part of the
            // traversal bookkeeping; an idle posted message is a moment we KNOW no pass runs in.
            if (isAttachedToWindow) {
                mainHandler.removeCallbacks(counterWatch)
                mainHandler.post(counterWatch)
            }
        }
    }

    init {
        addItemDecoration(PostDividerItemDecoration(context))
        isVerticalScrollBarEnabled = false
        // NOTE: the global-layout listener is armed in onAttachedToWindow, NOT here.
        // A view inflated from XML is not attached at construction time: its
        // ViewTreeObserver is a non-recording placeholder that gets REPLACED when
        // the view is attached to the window, so a listener added in init is
        // orphaned and never fires. (That is why 2.5.82 logged zero 'COUNTER
        // STUCK' lines despite the list being provably poisoned.)
    }

    override fun onAttachedToWindow() {
        // Arm on the REAL (recording) ViewTreeObserver. If the counter is already
        // leaked at attach, the first check runs one idle message later.
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        }
        mainHandler.removeCallbacks(counterWatch)
        mainHandler.post(counterWatch)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        mainHandler.removeCallbacks(counterWatch)
        super.onDetachedFromWindow()
    }
}
