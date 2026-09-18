package com.cosmos.unreddit.util

import android.content.Context
import android.view.View
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Shared in-cell / in-header muted video preview controller (feed cells and the
 * post-detail header). Owns one pool token: resolving a playable URL for the
 * bound post (direct, or via the post's own site API), acquiring a muted looping
 * ExoPlayer, and swapping a sharp still in for a CDN-baked frost preview when the
 * preview settings allow the content.
 *
 * Auto-play eligibility (2026-09-17 request: "everything that plays as a video
 * within the app should auto-play"): any post whose tap opens the app's own
 * player — every in-app-playable video type — plays muted while
 * [visibilityProvider] says the surface is on screen, gated by
 * [ContentPreferences.autoplayPreviews] and the NSFW/spoiler preview settings.
 *
 * The controller is created per view (cheap: no player until needed) and MUST be
 * [release]d when the host view detaches, or the pool token leaks.
 *
 * @param sharpPosterCallback receives a site sharp-still URL when a frost-baked
 *   CDN preview (redgifs et al.) can be un-blurred; the host loads it into its
 *   preview ImageView. Null = no swap (keep the frosted poster).
 *
 * @param restoreStillBadge restores the HOST-OWNED still badge (icon +
 *   visibility) for [post] after the controller stops/evicts/fails a stream.
 *   The badge is the host's element (the detail header's TYPE INDICATOR —
 *   gallery/play/link per post type — and the feed cell's play badge); the
 *   controller may only HIDE it while playing. The 2026-09-18 "War Dogs"
 *   report: stopPlayback() used to re-show it unconditionally, and the
 *   detail screen's payload rebind (update()) runs stopPlayback() without
 *   re-applying the per-type decision — leaving an ICONLESS chip visible on
 *   text posts. Null = the controller never re-shows it (legacy behavior).
 */
class VideoPreviewController(
    private val context: Context,
    private val playerView: PlayerView,
    private val playBadge: View,
    private val visibilityProvider: () -> Boolean,
    /**
     * The still poster behind the player (feed cell / detail header preview
     * image). Hidden while the player plays and restored when it stops.
     * The feed cell's still carries the 8dp elevation that lifts it ABOVE the
     * 0dp player in the FrameLayout's draw order — without hiding it, the
     * playing video bled out of the still's rounded corners (2026-09-18
     * r/Unity3D screenshot: "video playing behind the image preview").
     */
    private val posterView: View? = null,
    private val restoreStillBadge: ((PostEntity) -> Unit)? = null,
    private val sharpPosterCallback: ((url: String) -> Unit)? = null
) {

    private val pool = FeedPreviewPlayerPool.get()
    private val resolver = InAppVideoResolver.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val token = object : FeedPreviewPlayerPool.Token {
        override fun playerAttached(player: Player) {
            this@VideoPreviewController.player = player
            playerView.player = player
        }

        override fun playerDetached() {
            // The pool evicted this token's player (2-slot cap) and released
            // it — the controller MUST forget it too, or the next playback
            // evaluation would call play() on a released player (crash).
            // Clearing the PlayerView's player too (a released ExoPlayer
            // still attached to the surface is what the pool previously
            // left behind — PlayerView's replay button would call play()
            // on it and crash). This callback does NOT fire on a voluntary
            // release() (the pool only invokes it for eviction), so
            // clearing here is always correct.
            player = null
            playerView.player = null
            playerView.visibility = View.GONE
            // Restore the still state: the post is still bound and (if the
            // setting allows) a later scroll will re-resolve/re-acquire.
            posterView?.visibility = View.VISIBLE
            restoreStillBadge()
        }
    }

    /** The player for the current post, or null. */
    private var player: Player? = null

    /** The post this controller is bound to (null after [release]). */
    private var bound: PostEntity? = null

    /** True while a site-API resolution is in flight. */
    private var resolving = false

    /** True while auto-play applies to the bound post. */
    private var desired = false

    /** True while the frost-baked poster's sharp swap is in flight. */
    private var posterSwapping = false

    /**
     * Binds a new post: stops any previous playback, decides eligibility, and
     * starts playback if the surface is visible. [prefs] is the current content
     * preferences (the host re-calls this when they change).
     */
    fun bind(post: PostEntity, prefs: ContentPreferences) {
        stopPlayback()
        bound = post
        val previewAllowed = post.shouldShowPreview(prefs)
        desired = InAppVideoResolver.canAutoplay(
            post.mediaType,
            prefs.autoplayPreviews,
            previewAllowed
        )
        if (desired) updatePlayback()
    }

    /** Called on scroll / layout (feed) or attach (detail): re-evaluate playback. */
    fun onVisibleChanged() {
        if (desired) updatePlayback()
    }

    /**
     * Swaps a CDN-baked frost poster for the site's own sharp still when the
     * preview is allowed and the post carries [PostEntity.frostBakedPreview]
     * (external video embeds have no sharp reddit-CDN rendition — the "NSFW
     * preview blur removal doesn't work" report). Only sites whose API exposes a
     * sharp still participate (redgifs today); others keep the frosted poster.
     * No-op when [sharpPosterCallback] is null or the preview is disallowed.
     */
    fun maybeLoadSharpPoster(prefs: ContentPreferences) {
        val post = bound ?: return
        if (!post.frostBakedPreview || posterSwapping || sharpPosterCallback == null) return
        if (!post.shouldShowPreview(prefs)) return
        posterSwapping = true
        scope.launch {
            val sharp = resolver.sharpPreview(post.mediaType, post.url)
            posterSwapping = false
            if (bound !== post) return@launch
            sharp?.let { sharpPosterCallback?.invoke(it) }
        }
    }

    private fun updatePlayback() {
        val post = bound ?: return
        if (!desired || !visibilityProvider()) {
            player?.pause()
            return
        }
        if (player != null) {
            player?.play()
            return
        }
        // Not playing yet: resolve a playable URL, then acquire.
        val direct = resolver.directSource(post.mediaType, post.url, post.mediaUrl)
        if (direct != null) {
            acquire(direct.playableUrl)
        } else if (!resolving) {
            resolving = true
            scope.launch {
                val src = resolver.resolve(post.mediaType, post.url, post.mediaUrl)
                resolving = false
                if (bound !== post || !desired) return@launch
                if (src != null && visibilityProvider()) {
                    acquire(src.playableUrl)
                } else if (src == null) {
                    // The site API failed (or the type isn't resolvable after
                    // all): never leave an empty player — restore the still
                    // preview + the host's badge state.
                    stopPlayback()
                }
            }
        }
    }

    private fun acquire(url: String) {
        if (player != null) return
        val acquired = pool.acquire(context, url, token)
        // The pool's eviction runs inside acquire(): if the slot had to go to
        // another surface, THIS token's player was evicted+released and
        // playerDetached() already reset the still state — `player` is null.
        // Don't expose an empty player surface.
        if (player == null || player !== acquired) return
        // A stream that fails to start (dead signed URL, site API lied, network
        // loss) must not leave the surface showing an empty player — restore the
        // still poster + the host's badge state. The pool's eviction path already
        // handles budget pressure; this covers playback errors only.
        acquired.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackFailed()
            }

            // The feed cell's still poster is drawn ABOVE the player (its 8dp
            // elevation beats the player's 0dp in the FrameLayout draw order) —
            // drop it when the FIRST video frame lands, or the video bleeds out
            // of the still's rounded corners (2026-09-18 r/Unity3D report).
            // Not at acquire() time: the player surface is a SurfaceView, whose
            // hole shows the window background (black) while buffering — hiding
            // the poster early would flash black on slow networks.
            override fun onRenderedFirstFrame() {
                posterView?.visibility = View.GONE
            }
        })
        playerView.visibility = View.VISIBLE
        playBadge.visibility = View.GONE
    }

    /** Restores the still-preview state after a failed stream. */
    protected open fun onPlaybackFailed() {
        stopPlayback()
    }

    private fun stopPlayback() {
        // Cancel any in-flight site-API resolution / poster swap; the scope
        // itself survives — feed cells are recycled (detach → rebind), so a later
        // bind() must be able to launch again.
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        resolving = false
        posterSwapping = false
        if (player != null) {
            pool.release(token)
            player = null
        }
        playerView.player = null
        playerView.visibility = View.GONE
        posterView?.visibility = View.VISIBLE
        restoreStillBadge()
    }

    /**
     * Restores the host-owned still badge for the bound post (icon + visibility
     * per post type). The controller only ever HIDES the badge while playing;
     * re-showing a WRONG badge (e.g. the detail header's iconless chip on a
     * text post) is what caused the 2026-09-18 report — the host owns the
     * per-type decision and re-applies it here.
     */
    private fun restoreStillBadge() {
        val post = bound
        if (post == null) {
            playBadge.visibility = View.GONE
            return
        }
        restoreStillBadge?.invoke(post)
    }

    /** Stops playback and forgets the bound post. Idempotent. */
    fun release() {
        stopPlayback()
        bound = null
        desired = false
    }
}
