package com.cosmos.unreddit.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins for v2.5.111 manual subreddit/user routing: explicit forms and bare
 * valid names must reach the native screens (reliable lane) without touching
 * search; ordinary queries must not be hijacked.
 */
class SearchRoutingTest {

    @Test
    fun `explicit r forms route to the subreddit screen`() {
        assertEquals(Routing.Subreddit("Piracy"), SearchRouting.target("r/Piracy"))
        assertEquals(Routing.Subreddit("Piracy"), SearchRouting.target("/r/Piracy"))
        assertEquals(Routing.Subreddit("Piracy"), SearchRouting.target("  /r/Piracy/  "))
        assertEquals(Routing.Subreddit("IAmA"), SearchRouting.target("R/IAmA"))
        assertEquals(Routing.Subreddit("uucwdd"),
            SearchRouting.target("r/" + "uucwdd"))
    }

    @Test
    fun `pasted reddit urls route natively, other domains search`() {
        assertEquals(Routing.Subreddit("DJI"),
            SearchRouting.target("https://www.reddit.com/r/DJI/"))
        assertEquals(Routing.Subreddit("DJI"),
            SearchRouting.target("http://old.reddit.com/r/DJI/top/?t=week"))
        assertEquals(Routing.User("u_name1"),
            SearchRouting.target("https://reddit.com/user/u_name1/comments"))
        // Non-reddit URL stays search (no hijack of link pasting).
        assertEquals(Routing.Search("https://example.com/r/DJI"),
            SearchRouting.target("https://example.com/r/DJI"))
    }

    @Test
    fun `u forms route to the user screen`() {
        assertEquals(Routing.User("spartanrc"), SearchRouting.target("u/spartanrc"))
        assertEquals(Routing.User("spartanrc"), SearchRouting.target("/u/spartanrc"))
        assertEquals(Routing.User("Bob_123"), SearchRouting.target("U/Bob_123"))
    }

    @Test
    fun `bare valid names search - 2_5_113 owner correction, no hijack`() {
        // A typed search query must never be hijacked into a subreddit jump,
        // even when it has a valid subreddit-name shape. Measured hijack:
        // "lucia" -> r/lucia (404, dead screen). Bare input is a query.
        assertEquals(Routing.Search("Piracy"), SearchRouting.target("Piracy"))
        assertEquals(Routing.Search("lucia"), SearchRouting.target("lucia"))
        assertEquals(Routing.Search("pics"), SearchRouting.target(" pics "))
        // Multi-word = always search.
        assertEquals(Routing.Search("dji neo"), SearchRouting.target("dji neo"))
        // Too short / too long / illegal chars for a subreddit name = search.
        assertEquals(Routing.Search("ab"), SearchRouting.target("ab"))
        assertEquals(Routing.Search("a".repeat(22)), SearchRouting.target("a".repeat(22)))
        assertEquals(Routing.Search("_bad"), SearchRouting.target("_bad"))
        assertEquals(Routing.Search("bad_"), SearchRouting.target("bad_"))
        assertEquals(Routing.Subreddit("bad_"), SearchRouting.target("r/bad_"))  // explicit is exact
        assertEquals(Routing.Search("some thing"), SearchRouting.target("some thing"))
        assertEquals(Routing.Search("neo 2 & mini"),
            SearchRouting.target("neo 2 & mini"))
    }

    @Test
    fun `length boundaries are reddit-exact for explicit forms`() {
        assertEquals(Routing.Subreddit("abc"), SearchRouting.target("r/abc"))
        val max = "a" + "b".repeat(19) + "c" // 21 chars
        assertEquals(Routing.Subreddit(max), SearchRouting.target("r/" + max))
        assertEquals(Routing.Search("r/" + max + "d"), SearchRouting.target("r/" + max + "d"))
    }
}
