package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.di.NetworkModule
import org.junit.Assert
import org.junit.Test

/**
 * Blur-toggle caching check: the progressive home feed persists posts as PostData
 * JSON (FeedCache.postJson) and re-parses them on every cold render
 * (FeedCoordinator.toPostData). If the `frost_baked_preview` flag or
 * `preview_blur_url` do not survive the round trip, cached frost-baked posts
 * (redgifs et al.) lose the flag and the sharp-poster swap never runs — so
 * "Show NSFW preview" ON cannot un-blur them until the cache row is refreshed.
 *
 * Uses the app's REAL Moshi (NetworkModule.provideRedditMoshi) so the adapters
 * are byte-identical to the feed's cache path.
 */
class FrostBakedRoundTripTest {

    private val json = """
    {
      "name": "t3_abc123",
      "id": "abc123",
      "subreddit": "test",
      "subreddit_name_prefixed": "r/test",
      "title": "redgifs post",
      "author": "someone",
      "created_utc": 1700000000,
      "permalink": "/r/test/comments/abc123/",
      "url": "https://www.redgifs.com/watch/xyz",
      "domain": "redgifs.com",
      "is_self": false,
      "score": 10,
      "num_comments": 3,
      "upvote_ratio": 0.9,
      "total_awards_received": 0,
      "is_original_content": false,
      "archived": false,
      "all_awardings": [],
      "spoiler": false,
      "locked": false,
      "stickied": false,
      "is_video": false,
      "thumbnail": "https://external-preview.redd.it/opq.jpeg?blur=40&s=abc",
      "preview_blur_url": "https://external-preview.redd.it/opq.jpeg?blur=40&s=abc",
      "frost_baked_preview": true,
      "over_18": true,
      "spoiler": false,
      "link_flair_richtext": []
    }
    """.trimIndent()

    @Test
    fun frostFlagSurvivesTheFeedCacheRoundTrip() {
        val moshi = NetworkModule.provideRedditMoshi()
        val adapter = moshi.adapter(PostData::class.java)

        // FeedCoordinator.toJson(data)
        val parsed1 = adapter.fromJson(json)!!
        // toJson -> fromJson again (the actual cache write -> read path)
        val roundTripped = adapter.fromJson(adapter.toJson(parsed1))!!

        println("round1: frost=${parsed1.frostBakedPreview} blurUrl=${parsed1.previewBlurUrl}")
        println("round2: frost=${roundTripped.frostBakedPreview} blurUrl=${roundTripped.previewBlurUrl}")
        println("round2: mediaType=${roundTripped.mediaType} url=${roundTripped.url}")
        // NOTE: previewUrl deliberately NOT asserted here: PostData.previewUrl
        // consults android.webkit.MimeTypeMap for its .url fall-through, which
        // the JVM test classpath does not mock. The previewUrl getter chain for
        // these frost-baked posts terminates at `thumbnail` (no media/preview
        // block in the SSR map), so the round-trip of `thumbnail` below covers it.

        Assert.assertTrue("frost_baked_preview must survive toJson", parsed1.frostBakedPreview)
        Assert.assertTrue("frost_baked_preview must survive fromJson(toJson)", roundTripped.frostBakedPreview)
        Assert.assertEquals(
            "https://external-preview.redd.it/opq.jpeg?blur=40&s=abc",
            roundTripped.previewBlurUrl
        )
        Assert.assertEquals(
            "https://external-preview.redd.it/opq.jpeg?blur=40&s=abc",
            roundTripped.thumbnail
        )
    }
}
