package com.cosmos.unreddit.ui.postlist

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Main-thread dispatcher for [androidx.paging.PagingDataAdapter] that NEVER runs inline: every
 * block is posted to the main looper as its own message.
 *
 * Why this exists: Paging's [androidx.paging.PagingDataDiffer.collectFrom] runs each page event
 * inside `withContext(mainDispatcher)`, and on the empty-list fast path it calls
 * `differCallback.onInserted -> AdapterListUpdateCallback -> notifyItemRangeInserted` directly on
 * that dispatcher. With the default [kotlinx.coroutines.Dispatchers.Main], a collector coroutine
 * that is already on the main thread takes the coroutine fast-path and runs that block INLINE.
 * If that moment falls inside a RecyclerView layout/scroll pass, the notify throws
 * `IllegalStateException("Cannot call this method while RecyclerView is computing a layout or
 * scrolling")` (see r/outerwilds, build 2.5.81, 2026-09-16: page-insert fired 100ms after a
 * load-more, mid-fling).
 *
 * Posting to the handler defers the notify to a fresh main-looper message, which executes only
 * after the current layout/scroll pass has completed and decremented its counter — so
 * `isComputingLayout()` is false by the time the notify runs. This is the same between-frames
 * safety that `AsyncListDiffer`/`submitList` (the home feed's path) already provides.
 *
 * `isDispatchNeeded` returns true so the `withContext` fast-path (which would run inline when the
 * current dispatcher is "already main") is never taken: the block is always dispatched (posted).
 */
class PostListFrameDispatcher : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }

    // Stable value identity: equal to any other instance sharing the same main handler, but never
    // equal to Dispatchers.Main / Main.immediate — which is what forces withContext to dispatch.
    override fun equals(other: Any?): Boolean =
        other is PostListFrameDispatcher && other.handler === handler

    override fun hashCode(): Int = handler.hashCode()
}
