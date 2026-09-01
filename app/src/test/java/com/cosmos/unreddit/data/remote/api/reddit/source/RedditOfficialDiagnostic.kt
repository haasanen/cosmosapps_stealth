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
                println("LIVE USER-MULTI OK: ${listing.data.children.size} posts in ${System.currentTimeMillis() - t0}ms for ${multi.length}-char (${multi.count { it == '+' } + 1}-sub) multiredd")
                println("LIVE USER-MULTI first 5: " + listing.data.children.take(5)
                    .map { (it as? PostChild)?.data?.title ?: "?" }.joinToString())
                println("LIVE USER-MULTI subs present: " + listing.data.children
                    .map { (it as? PostChild)?.data?.subreddit ?: "?" }
                    .distinct().take(12).joinToString())
            } catch (t: Throwable) {
                println("LIVE USER-MULTI FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE USER-MULTI cause: ${it::class.java.simpleName}: ${it.message}") }
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
