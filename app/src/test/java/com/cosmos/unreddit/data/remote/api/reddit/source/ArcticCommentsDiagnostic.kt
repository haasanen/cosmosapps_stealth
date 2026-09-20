package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.ArcticUserAgentInterceptor
import com.cosmos.unreddit.data.remote.api.reddit.ArcticApi
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Print-only live diagnostic (never throws red): drives the REAL ArcticShiftSource.getPost
 * against a post that has a genuine comment tree and prints exactly what the app sees —
 * the listing shapes and the parsed comment count/depths — to diagnose the "no comments" report.
 */
class ArcticCommentsDiagnostic {

    @Test
    fun livePostWithCommentTree() {
        runBlocking {
            val client = OkHttpClient.Builder()
                .addInterceptor(ArcticUserAgentInterceptor())
                .connectTimeout(60L, TimeUnit.SECONDS)
                .readTimeout(60L, TimeUnit.SECONDS)
                .writeTimeout(60L, TimeUnit.SECONDS)
                .build()
            val api = Retrofit.Builder()
                .baseUrl(ArcticApi.BASE_URL)
                .client(client)
                .build()
                .create(ArcticApi::class.java)
            val source = ArcticShiftSource(api, NetworkModule.provideRedditMoshi(), Dispatchers.IO)

            // Post with a real comment tree (verified live: /api/comments/tree returns t1 nodes).
            val permalink = "/r/AskReddit/comments/1wje9sx/"
            val t0 = System.currentTimeMillis()
            try {
                val listings = source.getPost(permalink, null, Sort.HOT)
                println("LIVE OK: ${listings.size} listings in ${System.currentTimeMillis() - t0}ms")
                listings.forEachIndexed { i, listing ->
                    println("listing[$i]: kind=${listing.kind} children=${listing.data.children.size}")
                }
                val post = (listings[0].data.children[0] as? PostChild)?.data
                println("post: title=${post?.title} subreddit=${post?.subreddit} numComments=${post?.commentsNumber} permalink=${post?.permalink}")
                val comments = listings[1].data.children
                val top = comments.filterIsInstance<CommentChild>()
                println("comments listing: total children=${comments.size} commentChildren=${top.size}")
                top.take(10).forEach { c ->
                    val d = c.data
                    println("  comment: name=${d.name} author=${d.author} depth=${d.depth} body=${d.bodyHtml.orEmpty().take(40).replace('\n', ' ')} replies=${d.replies?.data?.children?.size ?: "-"}")
                }
            } catch (t: Throwable) {
                println("LIVE FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                var c: Throwable? = t.cause
                while (c != null) {
                    println("LIVE cause: ${c::class.java.simpleName}: ${c.message}")
                    c = c.cause
                }
                t.stackTrace.take(15).forEach { println("  at $it") }
            }
        }
    }
}
