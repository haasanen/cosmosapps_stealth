package com.cosmos.unreddit.util

import android.content.Context
import android.os.Looper
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A tiny shared pool of [SimpleExoPlayer] instances for the feed's in-cell video
 * previews (muted, looping, visible-while-on-screen).
 *
 * The official reddit.com web feed plays video previews the same way: a handful of
 * concurrent players, the rest stay as their still poster. A fixed cap (2) keeps
 * memory and bandwidth bounded regardless of how many video posts are on screen
 * after a long scroll; the least-recently-seen preview is evicted when a new one
 * needs a slot.
 *
 * Holders must always call [release] for their [token] (in the cell's
 * `unbind()`/recycle path) — eviction also releases the player, but the holder is
 * the only one that knows when its view stopped using it.
 */
class FeedPreviewPlayerPool private constructor() {

    interface Token {
        fun playerAttached(player: Player)

        /** The pool evicted (or released) this token's player. */
        fun playerDetached()
    }

    private class Entry(
        val token: Token,
        val player: SimpleExoPlayer
    )

    /** FIFO order of [Entry]s; head = least recently active. */
    private val queue = ConcurrentLinkedQueue<Entry>()

    /**
     * Hands out a prepared, playing (muted, looping) [SimpleExoPlayer] for [url].
     *
     * Returns the existing player if this [token] already holds one (rebind with the
     * same post), otherwise a fresh player, evicting the least recently seen one if
     * the cap is reached.
     */
    @Synchronized
    fun acquire(context: Context, url: String, token: Token): SimpleExoPlayer {
        // Rebind with the same content: keep the running player.
        queue.firstOrNull { it.token === token }?.let { return it.player }

        // Evict until there is room. (Detached from views by the pool; the evicted
        // holder's later release() is a no-op via the identity check.)
        while (queue.size >= MAX_PLAYERS) {
            val victim = queue.poll() ?: break
            victim.token.playerDetached()
            try {
                victim.player.stop()
                victim.player.release()
            } catch (_: Exception) {
            }
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(LinkUtil.USER_AGENT)

        val player = SimpleExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLooper(Looper.getMainLooper())
            .build()

        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f // feed previews are always muted
        player.setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(url))
        player.prepare()
        player.play()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    touch(token)
                }
            }
        })

        queue.add(Entry(token, player))
        token.playerAttached(player)
        return player
    }

    /** Marks [token]'s player as most recently active. */
    @Synchronized
    private fun touch(token: Token) {
        val entry = queue.firstOrNull { it.token === token } ?: return
        queue.remove(entry)
        queue.add(entry)
    }

    /** Releases and forgets the player [token] holds, if any. Idempotent. */
    @Synchronized
    fun release(token: Token) {
        val entry = queue.firstOrNull { it.token === token } ?: return
        queue.remove(entry)
        entry.token.playerDetached()
        try {
            entry.player.stop()
            entry.player.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_PLAYERS = 2

        @Volatile
        private var instance: FeedPreviewPlayerPool? = null

        fun get(): FeedPreviewPlayerPool =
            instance ?: synchronized(this) {
                instance ?: FeedPreviewPlayerPool().also { instance = it }
            }
    }
}
