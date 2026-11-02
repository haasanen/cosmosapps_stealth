package com.cosmos.unreddit.util

import com.cosmos.unreddit.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.5.100: reddit's SSR comment HTML carries site-relative hrefs, and
 * LinkUtil.getLinkType cannot classify what OkHttp cannot parse — a relative
 * link fell through to NO_MEDIA -> openBrowser -> CustomTabs with a relative
 * Uri -> ActivityNotFoundException (no app resolves a relative VIEW intent).
 *
 * Crashed in the wild on the r/megalophobia post wnremk: the comment
 * auto-linked "Joel Osteen" to /search/?q=... (copied verbatim below).
 * Every case returns before the lazy `mime` is evaluated, plain-JVM safe.
 */
class RelativeLinkAbsolutizationTest {

    // The exact href copied off the crashing comment link.
    private val searchHref =
        "/search/?q=Joel+Osteen+Lakewood+Church&cId=ab7eadd1-5b97-460b-a45e-ca778576d219&iId=9697bf70-183e-4544-916c-ed4b6bf3a563"

    @Test
    fun relativeSearchHrefAbsolutizesAgainstReddit() {
        assertEquals(
            "https://www.reddit.com$searchHref",
            LinkUtil.toRedditAbsolute(searchHref)
        )
    }

    @Test
    fun relativeSearchHrefWasUnclassifiable() {
        // Pins WHY the old path crashed: the raw relative href is NO_MEDIA,
        // which routed straight to the browser.
        assertEquals(MediaType.NO_MEDIA, LinkUtil.getLinkType(searchHref))
    }

    @Test
    fun absolutizedSearchHrefClassifiesAsRedditLink() {
        // With a real URL the link enters the normal reddit routing instead of
        // crashing: openRedditLink -> deep link (or guarded browser fallback).
        val absolute = LinkUtil.toRedditAbsolute(searchHref)
        assertEquals(MediaType.REDDIT_LINK, LinkUtil.getLinkType(absolute))
    }

    @Test
    fun protocolRelativeExternalHrefGetsScheme() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/x.jpg",
            LinkUtil.toRedditAbsolute("//upload.wikimedia.org/wikipedia/commons/x.jpg")
        )
    }

    @Test
    fun absoluteHrefsPassThroughUntouched() {
        val https = "https://example.com/a/b?q=1"
        val http = "http://example.com/a"
        assertEquals(https, LinkUtil.toRedditAbsolute(https))
        assertEquals(http, LinkUtil.toRedditAbsolute(http))
        assertEquals("", LinkUtil.toRedditAbsolute(""))
    }
}
