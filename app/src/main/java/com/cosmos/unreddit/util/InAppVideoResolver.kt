package com.cosmos.unreddit.util

import com.cosmos.unreddit.UnredditApplication
import com.cosmos.unreddit.data.model.MediaType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A playable in-app media source, plus — where a site provides one — a SHARP
 * poster for the NSFW/spoiler preview-ON state.
 *
 * [playableUrl] is the file the ExoPlayer streams (feed in-cell preview, detail
 * header preview, fullscreen MediaViewer). [previewUrl] is a sharp still to show
 * when the preview settings allow the content: external-embed cards otherwise
 * carry only reddit's CDN-baked `?blur=40` poster (signed, no sharp twin on
 * reddit's CDNs), so with the "Show NSFW preview" toggle ON they still looked
 * frosted — the 2026-09-17 "nsfw preview blur removal setting doesn't work".
 * Native (reddit) posts leave [previewUrl] null: the card's own sharp preview
 * (i.redd.it rewrite) is already correct there.
 */
data class InAppMediaSource(
    val playableUrl: String,
    val previewUrl: String? = null
) {
    companion object {
        fun of(playableUrl: String): InAppMediaSource = InAppMediaSource(playableUrl, null)
    }
}

/**
 * Resolves the in-app media source for every post the app plays IN its own
 * (ExoPlayer) view — the whole `PostType.VIDEO` set: reddit video/GIF, imgur
 * gif/video, gfycat, redgifs, streamable, generic video. Those are exactly the
 * posts whose tap opens the MediaViewer rather than leaving the app to a
 * website, so (2026-09-17 request) they are all eligible for auto-play.
 *
 * Two shapes:
 *  - **Direct** (the post already carries a streamable file): reddit video/GIF
 *    ([InAppMediaSource] of `mediaUrl`), imgur gif (the .mp4 swap), imgur video /
 *    generic video (the url itself). Pure — no network.
 *  - **Site-resolved** (the post carries a page URL, not a file): gfycat,
 *    redgifs, streamable. The playable file (and the sharp poster, where the
 *    site offers one) come from that site's API — the same calls the fullscreen
 *    MediaViewer makes. Resolved asynchronously and cached per URL, so a feed
 *    cell pays it once.
 *
 * Anything else (image, gallery, youtube/external link) is NOT in-app playable
 * and resolves to null — those keep their still preview and open in the browser.
 *
 * Instance access: view holders are not injection points, so the singleton is
 * held by [UnredditApplication] (constructed there from the injected
 * repositories) and reached via [get].
 */
@Singleton
class InAppVideoResolver @Inject constructor(
    private val gfycatRepository: com.cosmos.unreddit.data.repository.GfycatRepository,
    private val redgifsRepository: com.cosmos.unreddit.data.repository.RedgifsRepository,
    private val streamableRepository: com.cosmos.unreddit.data.repository.StreamableRepository
) {

    private val cache = HashMap<String, InAppMediaSource>()

    /**
     * Synchronous half: the source for direct types, or null for types that need
     * a site API call / that aren't in-app playable. Pure (no network).
     */
    fun directSource(mediaType: MediaType, url: String, mediaUrl: String): InAppMediaSource? =
        when (mediaType) {
            // mediaUrl already resolves to a v.redd.it MP4/HLS (or the GIF card's
            // signed ?format=mp4 rendition) — streamable as-is.
            MediaType.REDDIT_VIDEO, MediaType.REDDIT_GIF ->
                mediaUrl.takeIf { it.isNotBlank() }?.let(InAppMediaSource::of)

            // imgur gif: the .gif/.gifv link is a streamable mp4 via the .mp4 swap.
            MediaType.IMGUR_GIF -> InAppMediaSource.of(LinkUtil.getImgurVideo(url))

            // imgur video + generic video: the url/mediaUrl is the file.
            MediaType.IMGUR_VIDEO, MediaType.VIDEO ->
                (mediaUrl.takeIf { it.isNotBlank() } ?: url)
                    .takeIf { it.isNotBlank() }
                    ?.let(InAppMediaSource::of)

            else -> null
        }

    /**
     * Full resolution: direct types return immediately; site-resolved types hit
     * that site's API (cached). Returns null when the post is not in-app playable
     * or the site call fails — the caller then keeps the still preview.
     */
    suspend fun resolve(mediaType: MediaType, url: String, mediaUrl: String): InAppMediaSource? {
        directSource(mediaType, url, mediaUrl)?.let { return it }
        val key = "$mediaType|$url"
        cache[key]?.let { return it }
        val resolved = try {
            when (mediaType) {
                MediaType.GFYCAT -> {
                    val g = gfycatRepository.getGfycatGif(LinkUtil.getGfycatId(url)).first()
                    InAppMediaSource(g.gfyItem.contentUrls.mp4.url)
                }
                MediaType.REDGIFS -> {
                    // The MediaViewer uses the SAME id extraction (LinkUtil.getGfycatId)
                    // for redgifs URLs — last path segment.
                    val g = redgifsRepository.getRedgifsGif(LinkUtil.getGfycatId(url)).first()
                    // The API poster/thumbnail are sharp stills (no reddit CDN
                    // blur); the hd file plays. poster is the full-size one —
                    // thumbnail is a ~200px webp (soft in a feed cell).
                    InAppMediaSource(
                        g.gif.urls.hd,
                        previewUrl = g.gif.urls.poster.ifBlank { g.gif.urls.thumbnail }.ifBlank { null }
                    )
                }
                MediaType.STREAMABLE -> {
                    val v = streamableRepository.getVideo(
                        LinkUtil.getStreamableShortcode(url)
                    ).first()
                    InAppMediaSource(v.files.mp4.url)
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
        if (resolved != null) cache[key] = resolved
        return resolved
    }

    /**
     * The sharp still a site offers for a frost-baked poster (preview-ON state),
     * or null when the site doesn't expose one. Resolves via the site API
     * (cached); native posts never come here (their preview is already sharp).
     */
    suspend fun sharpPreview(mediaType: MediaType, url: String): String? =
        if (mediaType == MediaType.REDGIFS) {
            resolve(mediaType, url, url)?.previewUrl
        } else {
            null
        }

    /** True for every [MediaType] the app plays in its own ExoPlayer (pure). */
    fun isInAppPlayable(mediaType: MediaType): Boolean = when (mediaType) {
        MediaType.REDDIT_VIDEO, MediaType.REDDIT_GIF,
        MediaType.IMGUR_GIF, MediaType.IMGUR_VIDEO, MediaType.VIDEO,
        MediaType.GFYCAT, MediaType.REDGIFS, MediaType.STREAMABLE -> true
        else -> false
    }

    companion object {
        /**
         * Auto-play eligibility (pure, unit-tested). 2026-09-17 request: "everything
         * that plays as a video within the app should auto-play" — so every
         * in-app-playable video type qualifies, not just native v.redd.it. Only
         * site-opening links (youtube, articles) and non-videos don't. Gated by the
         * user's setting and the NSFW/spoiler preview allowance (playing would reveal
         * hidden content).
         */
        fun canAutoplay(
            mediaType: MediaType,
            autoplayEnabled: Boolean,
            previewAllowed: Boolean
        ): Boolean {
            if (!autoplayEnabled || !previewAllowed) return false
            return when (mediaType) {
                MediaType.REDDIT_VIDEO, MediaType.REDDIT_GIF,
                MediaType.IMGUR_GIF, MediaType.IMGUR_VIDEO, MediaType.VIDEO,
                MediaType.GFYCAT, MediaType.REDGIFS, MediaType.STREAMABLE -> true
                else -> false
            }
        }

        /**
         * Obtain the app-scoped resolver from any [android.content.Context]
         * (view holders are not injection points). Held by the Application, which
         * is a @HiltAndroidApp and already receives its repositories by
         * construction.
         */
        fun get(context: android.content.Context): InAppVideoResolver =
            (context.applicationContext as UnredditApplication).inAppVideoResolver
    }
}
