package com.cosmos.unreddit.ui.postlist

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.PostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5.86: "everything that plays as a video within the app should auto-play" —
 * the eligibility decision ([PostViewHolder.VideoPostViewHolder.canAutoplay]) is
 * the load-bearing rule: it decides which cells stream. It is pure (no
 * view/player), so it is pinned here.
 *
 * 2.5.85 rule: only native v.redd.it / signed-?format=mp4 reddit renditions
 * played; external embeds kept the still poster + badge.
 * 2.5.86 rule: EVERY [MediaType] the app plays in its own (ExoPlayer) view
 * autoplays — reddit video/GIF, imgur gif/video, gfycat, redgifs, streamable,
 * generic video. The URL no longer gates eligibility (the shared
 * InAppVideoResolver resolves the playable file for each type); only the
 * user's setting and the NSFW/spoiler preview allowance do. Site-opening links
 * and non-videos never play.
 */
class FeedVideoAutoplayEligibilityTest {

    private fun post(
        mediaType: MediaType,
        mediaUrl: String,
        isOver18: Boolean = false,
        isSpoiler: Boolean = false
    ): PostEntity = PostEntity(
        id = "t3_test",
        subreddit = "test",
        title = "t",
        ratio = 100,
        totalAwards = 0,
        isOC = false,
        score = "1",
        type = if (mediaType == MediaType.IMAGE) PostType.IMAGE else PostType.VIDEO,
        domain = hostOf(mediaUrl),
        isSelf = false,
        selfTextHtml = null,
        suggestedSorting = Sorting(Sort.HOT),
        isOver18 = isOver18,
        preview = null,
        isSpoiler = isSpoiler,
        isArchived = false,
        isLocked = false,
        posterType = PosterType.REGULAR,
        author = "a",
        commentsNumber = "0",
        permalink = "/r/test/comments/1",
        isStickied = false,
        url = mediaUrl,
        created = 0L,
        mediaType = mediaType,
        mediaUrl = mediaUrl
    )

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    @Test
    fun `native reddit video plays`() {
        val p = post(MediaType.REDDIT_VIDEO, "https://v.redd.it/abc123/DASHPlaylist.m3u8")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `native reddit gif plays`() {
        val p = post(MediaType.REDDIT_GIF, "https://v.redd.it/abc123/DASHPlaylist.m3u8")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `gif card signed mp4 rendition plays`() {
        // A GIF/animated card's playable rendition is the signed ?format=mp4 URL
        // on preview.redd.it — the gate is media-type based now (the URL is
        // resolved by InAppVideoResolver), so this plays.
        val p = post(
            MediaType.REDDIT_GIF,
            "https://preview.redd.it/l7conq7h7yoh1.gif?width=592&format=mp4&s=9ca921"
        )
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `imgur gif plays`() {
        val p = post(MediaType.IMGUR_GIF, "https://i.imgur.com/abc123.gif")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `imgur video plays`() {
        val p = post(MediaType.IMGUR_VIDEO, "https://i.imgur.com/abc123.mp4")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `redgifs external video plays (app plays it in its own player)`() {
        // The MediaViewer resolves redgifs through the site API, so the post
        // "plays as a video within the app" — it autoplays (site API call made
        // on demand by the resolver).
        val p = post(MediaType.REDGIFS, "https://www.redgifs.com/watch/ABC")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `gfycat external video plays`() {
        val p = post(MediaType.GFYCAT, "https://gfycat.com/SomeGif")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `streamable external video plays`() {
        val p = post(MediaType.STREAMABLE, "https://streamable.com/abc")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `generic video link plays`() {
        val p = post(MediaType.VIDEO, "https://example.com/video.mp4")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `autoplay disabled never plays`() {
        val p = post(MediaType.REDDIT_VIDEO, "https://v.redd.it/abc123/DASHPlaylist.m3u8")
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, false, true))
    }

    @Test
    fun `nsfw preview hidden never plays`() {
        val p = post(MediaType.REDDIT_VIDEO, "https://v.redd.it/abc123/DASHPlaylist.m3u8", isOver18 = true)
        // previewAllowed=false is what shouldShowPreview returns when NSFW previews are off
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, false))
    }

    @Test
    fun `spoiler preview hidden never plays`() {
        val p = post(MediaType.REDDIT_VIDEO, "https://v.redd.it/abc123/DASHPlaylist.m3u8", isSpoiler = true)
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, false))
    }

    @Test
    fun `image post never autoplays`() {
        val p = post(MediaType.IMAGE, "https://i.redd.it/abc123.jpeg")
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `gallery post never autoplays`() {
        val p = post(MediaType.REDDIT_GALLERY, "https://preview.redd.it/abc123.jpeg")
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    // --- Visible-fraction geometry (the "at least half on screen" rule) -------

    @Test
    fun `fully visible cell`() {
        assertEquals(800, PostViewHolder.VideoPostViewHolder.visibleFraction(0, 800, 900))
    }

    @Test
    fun `exactly half visible cell`() {
        assertEquals(400, PostViewHolder.VideoPostViewHolder.visibleFraction(500, 800, 900))
    }

    @Test
    fun `just under half visible cell`() {
        assertEquals(399, PostViewHolder.VideoPostViewHolder.visibleFraction(501, 800, 900))
    }

    @Test
    fun `scrolled out cells are invisible`() {
        assertEquals(0, PostViewHolder.VideoPostViewHolder.visibleFraction(900, 800, 900))
        assertEquals(0, PostViewHolder.VideoPostViewHolder.visibleFraction(-800, 800, 900))
    }

    @Test
    fun `zero sized inputs are invisible`() {
        assertEquals(0, PostViewHolder.VideoPostViewHolder.visibleFraction(0, 0, 900))
        assertEquals(0, PostViewHolder.VideoPostViewHolder.visibleFraction(0, 800, 0))
    }
}
