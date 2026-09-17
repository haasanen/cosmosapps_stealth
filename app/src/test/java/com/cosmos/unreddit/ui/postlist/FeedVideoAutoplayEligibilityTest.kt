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
 * 2.5.74: feed video previews should auto-play muted when visible. The
 * eligibility decision ([PostViewHolder.VideoPostViewHolder.canAutoplay]) is the
 * load-bearing rule — it decides which cells stream. It is pure (no view/player),
 * so it is pinned here: only enabled + preview-allowed + native v.redd.it videos
 * play; external embeds and hidden NSFW/spoiler previews never do.
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
    fun `native reddit video with autoplay on and preview allowed plays`() {
        val p = post(MediaType.REDDIT_VIDEO, "https://v.redd.it/abc123/DASHPlaylist.m3u8")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `native reddit gif preview plays`() {
        val p = post(MediaType.REDDIT_GIF, "https://v.redd.it/abc123/DASHPlaylist.m3u8")
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `gif card signed mp4 rendition on preview_redd_it plays`() {
        // 2.5.85: an animated (GIF) card's playable rendition is the signed
        // ?format=mp4 URL on preview.redd.it / cf.preview.redd.it — the gate must
        // accept it or GIFs silently never autoplay (2026-09-17 report).
        val p = post(
            MediaType.REDDIT_GIF,
            "https://preview.redd.it/l7conq7h7yoh1.gif?width=592&format=mp4&s=9ca921"
        )
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `gif card signed mp4 rendition on cf_preview_redd_it plays`() {
        val p = post(
            MediaType.REDDIT_GIF,
            "https://cf.preview.redd.it/pi3ddoktcfnh1.gif?width=220&format=mp4&s=ddbf90"
        )
        assertTrue(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `still image url on preview_redd_it does not play`() {
        // preview.redd.it serves stills too (no format=mp4) — not a playable rendition.
        val p = post(
            MediaType.REDDIT_GIF,
            "https://preview.redd.it/abc123.jpeg?width=640&s=deadbeef"
        )
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
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
    fun `redgifs external video never autoplays`() {
        val p = post(MediaType.REDGIFS, "https://www.redgifs.com/watch/ABC")
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `youtube external video never autoplays`() {
        val p = post(MediaType.VIDEO, "https://www.youtube.com/watch?v=abc")
        assertFalse(PostViewHolder.VideoPostViewHolder.canAutoplay(p, true, true))
    }

    @Test
    fun `non-video post never autoplays`() {
        val p = post(MediaType.IMAGE, "https://i.redd.it/abc123.jpeg")
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
