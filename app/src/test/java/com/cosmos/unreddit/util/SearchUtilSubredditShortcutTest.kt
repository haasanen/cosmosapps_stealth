package com.cosmos.unreddit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `r/<name>` typed in the search box is a direct subreddit navigation, not
 * a search. The normalizer must handle real-world input shapes and reject
 * things that look like a shortcut but aren't (e.g. a plain word that
 * happens to start with "r").
 */
class SearchUtilSubredditShortcutTest {

    @Test
    fun cleanName() {
        assertEquals("linux", SearchUtil.subredditShortcut("r/linux"))
        // case is preserved as typed; the subreddit screen matches case-insensitively
        assertEquals("LINUX", SearchUtil.subredditShortcut("R/LINUX"))
        assertEquals("Linux", SearchUtil.subredditShortcut("r/Linux"))
        assertEquals("linux", SearchUtil.subredditShortcut("r/linux/"))
        assertEquals("linux", SearchUtil.subredditShortcut("  r/linux  "))
        // deep link past the community (still opens the community)
        assertEquals("linux", SearchUtil.subredditShortcut("r/linux/about"))
    }

    @Test
    fun notAShortcut() {
        // no slash -> it is a search term, however it looks
        assertNull(SearchUtil.subredditShortcut("rlinux"))
        assertNull(SearchUtil.subredditShortcut("r linux"))
        assertNull(SearchUtil.subredditShortcut("redgifs"))
        assertNull(SearchUtil.subredditShortcut("r"))
        assertNull(SearchUtil.subredditShortcut("r/"))
        assertNull(SearchUtil.subredditShortcut("r/ "))
        assertNull(SearchUtil.subredditShortcut(""))
        assertNull(SearchUtil.subredditShortcut("   "))
        // spaces inside the name are not a community name
        assertNull(SearchUtil.subredditShortcut("r/my sub"))
    }
}
