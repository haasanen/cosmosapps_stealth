package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
 * so it works even in the R8 release build where the field is renamed (here: `G`).
 */
class PostRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val counterWatch = Runnable {
        if (!isAttachedToWindow) return@Runnable
        if (!isComputingLayout()) return@Runnable
        // We are in an idle looper message: no layout/scroll code can be on the stack, so an
        // elevated counter here is a leak, not a pass in flight.
        FeedDebug.log(
            "PostRecyclerView COUNTER STUCK at $this: a layout/scroll pass leaked " +
                "mLayoutOrScrollCounter (something threw inside the pass — see the first " +
                "exception logged in this launch, e.g. 'PullToRefreshLayout layout pass'). " +
                "Resetting."
        )
        if (resetStuckCounter()) {
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

    private val globalLayoutListener = object : androidx.view.ViewTreeObserver.OnGlobalLayoutListener {
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
        viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        mainHandler.removeCallbacks(counterWatch)
        super.onDetachedFromWindow()
    }
}
