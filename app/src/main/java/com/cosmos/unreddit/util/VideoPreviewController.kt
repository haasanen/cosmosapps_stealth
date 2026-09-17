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
 */
class VideoPreviewController(
    private val context: Context,
    private val playerView: PlayerView,
    private val playBadge: View,
    private val visibilityProvider: () -> Boolean,
    private val sharpPosterCallback: ((url: String) -> Unit)? = null
) {

    private val pool = FeedPreviewPlayerPool.get()
    private val resolver = InAppVideoResolver.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val token = object : FeedPreviewPlayerPool.Token {
        override fun playerAttached(player: Player) {
            playerView.player = player
        }

        override fun playerDetached() {
            // The pool evicted this token's player (budget exceeded or release);
            // the controller already nulled it.
            playerView.player = null
            playerView.visibility = View.GONE
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
                    // preview + play badge.
                    stopPlayback()
                    playBadge.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun acquire(url: String) {
        if (player != null) return
        player = pool.acquire(context, url, token)
        // A stream that fails to start (dead signed URL, site API lied, network
        // loss) must not leave the surface showing an empty player — restore the
        // still poster + play badge. The pool's eviction path already handles
        // budget pressure; this covers playback errors only.
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackFailed()
            }
        })
        playerView.visibility = View.VISIBLE
        playBadge.visibility = View.GONE
    }

    /** Restores the still-preview state after a failed stream. */
    protected open fun onPlaybackFailed() {
        stopPlayback()
        playBadge.visibility = View.VISIBLE
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
        playBadge.visibility = View.VISIBLE
    }

    /** Stops playback and forgets the bound post. Idempotent. */
    fun release() {
        stopPlayback()
        bound = null
        desired = false
    }
}
