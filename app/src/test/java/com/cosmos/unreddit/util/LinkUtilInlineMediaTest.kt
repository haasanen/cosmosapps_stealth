package com.cosmos.unreddit.util

import com.cosmos.unreddit.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Routing for the inline comment-media URLs reddit actually serves. The tricky
 * ones are the hosts that carry no useful file extension:
 *  - v.redd.it  -> HLS playlists (.m3u8) / DASH mp4
 *  - external-preview.redd.it -> GIF "videos" as .gif?format=mp4
 * Both must reach the in-app player, not the image loader or the browser.
 *
 * Every case returns before the lazy `mime` (android MimeTypeMap) is evaluated,
 * so the test runs on the plain JVM.
 */
class LinkUtilInlineMediaTest {

    @Test
    fun vRedditHlsPlaylistRoutesToRedditVideo() {
        val url = "https://v.redd.it/7pthmmfecioh1/HLSPlaylist.m3u8?f=sd%2ChlsTrimLow"
        assertEquals(MediaType.REDDIT_VIDEO, LinkUtil.getLinkType(url))
    }

    @Test
    fun vRedditBareBaseRoutesToRedditVideo() {
        val url = "https://v.redd.it/7pthmmfecioh1"
        assertEquals(MediaType.REDDIT_VIDEO, LinkUtil.getLinkType(url))
    }

    @Test
    fun gifAsMp4OnExternalPreviewRoutesToRedditGif() {
        // Real markup from r/Amd 1wbeqyp comment (player 2):
        val url = "https://external-preview.redd.it/Mt6wSwNQlYIdMhepPzcDbHpiMA306hWAKb1cpl9AaAg.gif?width=356&format=mp4&s=0f6c8099b8e464f9c847eee5c6b55ed0410670ba"
        assertEquals(MediaType.REDDIT_GIF, LinkUtil.getLinkType(url))
    }

    @Test
    fun gifAsWebmOnExternalPreviewRoutesToRedditGif() {
        val url = "https://external-preview.redd.it/ABC123.gif?width=352&format=webm&s=f51cea"
        assertEquals(MediaType.REDDIT_GIF, LinkUtil.getLinkType(url))
    }
}
