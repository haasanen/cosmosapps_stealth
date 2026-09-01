package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditRedirectParseTest {
    private val source = RedditOfficialSource(
        NetworkModule.provideRedditScrapOkHttpClient(),
        NetworkModule.provideRedditMoshi(),
        Dispatchers.IO
    )

    private val encodedStub =
        "<div id=\"shreddit-redirect\">" +
        "<ac-call delay=\"0\" method=\"location.replace(&quot;/r/EarthCam/comments/1vpgtb5/earthcam_road_trip/&quot;)\" target=\"window\" trigger=\"init\"></ac-call>" +
        "</div>"

    @Test
    fun `encoded stub returns slugged target`() {
        val t = source.shredditRedirectTarget(Jsoup.parse(encodedStub))
        assertTrue(t != null)
        assertTrue(t!!.endsWith("/r/EarthCam/comments/1vpgtb5/earthcam_road_trip/"))
    }

    @Test
    fun `page without redirect returns null`() {
        assertNull(source.shredditRedirectTarget(Jsoup.parse("<html><body>x</body></html>")))
    }
}
