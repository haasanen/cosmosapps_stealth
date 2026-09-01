package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE diagnostic — not part of the CI suite (uses the network).
 *
 * Runs the app's exact client stack (same OkHttpClient build as NetworkModule,
 * the same RedditCookieJar, the real RedditOfficialSource) against the live
 * reddit.com home feed and prints what happens at every step.
 */
class RedditOfficialDiagnostic {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(60L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .cookieJar(RedditCookieJar())
        .build()

    @Test
    fun liveHomeFeed() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            try {
                val listing = source.getSubreddit("popular", Sort.HOT, null, null)
                println("LIVE OK: ${listing.data.children.size} posts in ${System.currentTimeMillis() - t0}ms")
                println("LIVE first 5: " + listing.data.children.take(5)
                    .map { (it as? PostChild)?.data?.title ?: "?" }.joinToString())
            } catch (t: Throwable) {
                println("LIVE FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    @Test
    fun liveSubredditFeed() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            try {
                // Exactly what the app calls when the user picks a subreddit (default sort HOT):
                // https://www.reddit.com/r/AskReddit/hot/?count=25
                val listing = source.getSubreddit("AskReddit", Sort.HOT, null, null)
                println("LIVE SUB OK: ${listing.data.children.size} posts in ${System.currentTimeMillis() - t0}ms")
                println("LIVE SUB first 5: " + listing.data.children.take(5)
                    .map { (it as? PostChild)?.data?.title ?: "?" }.joinToString())
            } catch (t: Throwable) {
                println("LIVE SUB FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE SUB cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    @Test
    fun liveMultiReddFeed() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            // A multiredd home feed: subreddits joined with '+', exactly the URL shape the
            // app builds for a user's home list (e.g. /r/A+B+C/hot/?count=25).
            val multi = listOf(
                "LocalLLM", "openclaw", "3Dmodeling", "freegames",
                "prusa3d", "eWitness", "linux_gaming", "animation"
            ).joinToString("+")
            try {
                val listing = source.getSubreddit(multi, Sort.HOT, null, null)
                println("LIVE MULTI OK: ${listing.data.children.size} posts in ${System.currentTimeMillis() - t0}ms for ${multi.length}-char multiredd")
                println("LIVE MULTI first 5: " + listing.data.children.take(5)
                    .map { (it as? PostChild)?.data?.title ?: "?" }.joinToString())
            } catch (t: Throwable) {
                println("LIVE MULTI FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE MULTI cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    @Test
    fun liveMultiReddUserList() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            // The user's ACTUAL home multiredd — the exact 73-subreddit list taken
            // verbatim from their backup file (projects/stealth/backup-stealth-profile.json,
            // profile "Stealth"). This is the feed that was blank on-device.
            val multi = listOf(
                "GameDeals", "freegames", "FreeGamesOnSteam", "patientgamers", "Amd", "intel", "nvidia", "hardware",
                "gamedev", "godot", "netsec", "CrackWatch", "PiratedGames", "Piracy", "FREEMEDIAHECKYEAH",
                "privacy", "ProtonVPN", "ProtonMail", "linux_gaming", "Android", "food", "FoodPorn", "Cooking",
                "unity", "Unity3D", "archlinux", "blender", "DataHoarder", "homelab", "HomeServer", "selfhosted",
                "hetzner", "BorgBackup", "fromsoftware", "Steam", "virtualreality", "oculus", "OculusQuest",
                "TheWitness", "cableporn", "3Dmodeling", "functionalprint", "3Dprinting", "prusa3d",
                "animation", "LineageOS", "cybersecurity", "sysadmin", "MiniPCs", "Eldenring", "pcgaming",
                "TOR", "tails", "homeassistant", "frigate_nvr", "reolinkcam", "homeautomation", "networking",
                "ipv6", "PorkBun", "Lofree", "NuPhy", "MechanicalKeyboards", "outerwilds", "storage",
                "LocalLLM", "LocalLLaMA", "openclaw", "yubikey", "doohickeycorporation", "smarthome", "ollama",
                "hermesagent"
            ).joinToString("+")
            try {
                val listing = source.getSubreddit(multi, Sort.HOT, null, null)
                val posts = listing.data.children.mapNotNull { it as? PostChild }.map { it.data }
                val withScore = posts.count { it.score > 0 }
                val withComments = posts.count { it.commentsNumber > 0 }
                val withThumb = posts.count { it.thumbnail != null }
                val top = posts.maxByOrNull { it.score }
                println("LIVE USER-MULTI OK: ${posts.size} posts in ${System.currentTimeMillis() - t0}ms for ${multi.count { it == '+' } + 1}-sub multiredd")
                println("LIVE USER-MULTI scores: $withScore/${posts.size} nonzero; comments: $withComments/${posts.size} nonzero; media: $withThumb/${posts.size}")
                if (top != null) println("LIVE USER-MULTI top: score=${top.score} comments=${top.commentsNumber} r/${top.subreddit} title=${top.title.take(50)} thumb=${top.thumbnail?.take(60)}")
                println("LIVE USER-MULTI subs present: " + posts.map { it.subreddit }.distinct().take(12).joinToString())
                val cursor = listing.data.after
                println("LIVE USER-MULTI page1 cursor parts=${cursor?.split(";")?.size ?: 0} (per-sub cursors threaded for page 2)")
            } catch (t: Throwable) {
                println("LIVE USER-MULTI FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE USER-MULTI cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    @Test
    fun livePostDetailSlugless() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            // Slugless permalink — the exact form the multiredd/home feed links use.
            // Reddit serves these as a JS-redirect stub; getPost must follow the
            // redirect to the slugged URL and return the post + comments.
            val slugless = "/r/EarthCam/comments/1vpgtb5/"
            val t0 = System.currentTimeMillis()
            try {
                val result = source.getPost(slugless, 25, Sort.BEST)
                val listing = result.first()
                val op = (listing.data.children.first() as PostChild).data
                val comments = (result.getOrNull(1)?.data?.children ?: emptyList()).size
                println("LIVE POST-DETAIL OK: slugless loaded in ${System.currentTimeMillis() - t0}ms: r/${op.subreddit} score=${op.score} comments-listed=${comments} title=${op.title.take(50)}")
            } catch (t: Throwable) {
                println("LIVE POST-DETAIL FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE POST-DETAIL cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }

    @Test
    fun liveSubredditOverview() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            try {
                val child = source.getSubredditInfo("AskReddit")
                val about = (child as AboutChild).data
                println("LIVE ABOUT OK in ${System.currentTimeMillis() - t0}ms: title=${about.title} subscribers=${about.subscribers} online=${about.activeUserCount}")
            } catch (t: Throwable) {
                println("LIVE ABOUT FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE ABOUT cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }
}
