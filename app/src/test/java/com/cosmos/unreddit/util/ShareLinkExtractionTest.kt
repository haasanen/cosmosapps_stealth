package com.cosmos.unreddit.util

// v2.5.98: share-sheet (ACTION_SEND text/plain) bodies are free text; the
// app opens the shared link in-app, so URL extraction must survive real
// share formats (title+url+via, wrapped punctuation, multiple urls).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkExtractionTest {

    @Test
    fun `plain url passes through`() {
        assertEquals(
            "https://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/",
            LinkUtil.extractUrlFromSharedText(
                "https://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/")
        )
    }

    @Test
    fun `url extracted from title-via body`() {
        assertEquals(
            "https://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/",
            LinkUtil.extractUrlFromSharedText(
                "Ocean waves : r/godot\nhttps://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/\n")
        )
    }

    @Test
    fun `first url wins when body carries several`() {
        assertEquals(
            "https://old.reddit.com/r/godot/",
            LinkUtil.extractUrlFromSharedText(
                "look: https://old.reddit.com/r/godot/ and https://redd.it/abc123")
        )
    }

    @Test
    fun `trailing sentence punctuation is stripped`() {
        assertEquals(
            "https://redd.it/abc123",
            LinkUtil.extractUrlFromSharedText("check this out https://redd.it/abc123.")
        )
        assertEquals(
            "https://redd.it/abc123",
            LinkUtil.extractUrlFromSharedText("(shared: https://redd.it/abc123)")
        )
        assertEquals(
            "https://redd.it/abc123",
            LinkUtil.extractUrlFromSharedText("https://redd.it/abc123, definitely")
        )
    }

    @Test
    fun `query strings survive`() {
        assertEquals(
            "https://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/?context=3",
            LinkUtil.extractUrlFromSharedText(
                "https://www.reddit.com/r/godot/comments/1xgxkq5/ocean_waves/?context=3")
        )
    }

    @Test
    fun `text without url yields null`() {
        assertNull(LinkUtil.extractUrlFromSharedText("no link here at all"))
        assertNull(LinkUtil.extractUrlFromSharedText(""))
        assertNull(LinkUtil.extractUrlFromSharedText(null))
    }
}
