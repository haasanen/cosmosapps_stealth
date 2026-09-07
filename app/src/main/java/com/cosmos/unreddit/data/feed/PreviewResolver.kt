package com.cosmos.unreddit.data.feed

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cosmos.unreddit.di.NetworkModule.BasicMoshi
import com.cosmos.unreddit.di.NetworkModule.GenericOkHttp
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Generalised link previews (v2.5.54).
 *
 * Reddit's API returns a `preview` image for most media, but LINK posts to
 * third-party sites (YouTube, news, ...) usually come back WITHOUT one, so the
 * link card renders blank. This resolves a preview image for any such URL in
 * two tiers:
 *
 *  1. oEmbed — `GET https://{host}/oembed?url={link}&format=json` and read
 *     `thumbnail_url`. The same mechanism reddit.com's own web UI uses; it
 *     covers YouTube, Vimeo, SoundCloud, most news and media sites.
 *  2. Open Graph — fetch the linked page and read the `og:image` (or
 *     `twitter:image`) meta tag. Catches everything without oEmbed.
 *
 * Results are cached INCLUDING the "no image" negatives (shorter TTL), so a
 * link post never triggers more than one fetch per TTL window. The cache is an
 * in-memory layer over DataStore, keyed by a hash of the URL; the value stores
 * the resolved image (or an empty one) so a cache-first launch can fill the
 * previews of cached link posts without any network.
 *
 * Called from [FeedCoordinator] on the IO dispatcher: one bounded batch per
 * feed cycle, never per visible row.
 */
@Singleton
class PreviewResolver @Inject constructor(
    @GenericOkHttp client: OkHttpClient,
    @BasicMoshi moshi: Moshi,
    private val preferences: DataStore<Preferences>
) {
    private val httpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheAdapter: JsonAdapter<CacheEntry> = moshi.adapter(CacheEntry::class.java)

    /** In-memory layer of the cache (avoids a DataStore round-trip per post). */
    private val memory = LinkedHashMap<String, CacheEntry>()

    /** URLs currently being resolved (concurrent dedupe; see [resolve]). */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val semaphore = Semaphore(MAX_PARALLEL)

    /**
     * A previously resolved image for [url], or null. Memory first, then the
     * DataStore cache. Expired entries count as misses (the caller will
     * re-resolve and refresh the cache).
     */
    suspend fun cachedImageFor(url: String): String? {
        synchronized(memory) { memory[url] }?.let { entry ->
            if (!entry.expired) return entry.image
        }
        val json = runCatching { preferences.data.first()[keyFor(url)] }.getOrNull()
            ?: return null
        val entry = runCatching { cacheAdapter.fromJson(json) }.getOrNull() ?: return null
        synchronized(memory) { memory[url] = entry }
        return if (entry.expired) null else entry.image
    }

    /**
     * Resolve a preview image for [url]: oEmbed first, Open Graph as the
     * fallback. Returns the image URL, or null when the site publishes no
     * thumbnail (remembered as a negative so it is not re-fetched per cycle).
     */
    suspend fun resolve(url: String): String? {
        cachedImageFor(url)?.let { return it }
        if (!inFlight.add(url)) return null
        try {
            val image = oEmbedImage(url)
                ?: fetchPage(url)?.let { ogImage(it, url) }
            remember(url, image)
            return image
        } finally {
            inFlight.remove(url)
        }
    }

    /**
     * Resolve a bounded batch in parallel (bounded by [MAX_PARALLEL]). Returns
     * url -> image for the hits only; misses (and negatives) are absent.
     */
    suspend fun resolveBatch(urls: List<String>): Map<String, String> = coroutineScope {
        urls.distinct().associate { url ->
            url to async {
                semaphore.withPermit {
                    runCatching { resolve(url) }.getOrNull()
                }
            }
        }.mapValues { (_, deferred) -> deferred.await() }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    //region Tier 1: oEmbed

    private fun oEmbedImage(url: String): String? {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
            ?: return null
        if (host.isBlank()) return null
        val endpoints = buildList {
            add("https://$host/oembed?url=${Uri.encode(url)}&format=json")
            if (!host.startsWith("www.")) {
                add("https://www.$host/oembed?url=${Uri.encode(url)}&format=json")
            }
        }
        for (endpoint in endpoints) {
            val body = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()?.take(MAX_OEMBED_BYTES)
                }
            }.getOrNull() ?: continue
            val match = THUMBNAIL_REGEX.find(body) ?: continue
            val thumb = match.groupValues[1].trim()
            if (thumb.startsWith("http")) return thumb
        }
        return null
    }

    //endregion

    //region Tier 2: Open Graph

    private fun fetchPage(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val type = response.header("Content-Type")?.lowercase().orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text")) {
                return@runCatching null
            }
            response.body?.source()?.buffer()?.readByteArray(MAX_PAGE_BYTES)?.decodeToString()
        }
    }.getOrNull()

    private fun ogImage(html: String, pageUrl: String): String? {
        for (pattern in OG_PATTERNS) {
            val match = pattern.find(html) ?: continue
            val raw = match.groupValues[1].trim().replace("&amp;", "&")
            if (raw.isEmpty() || raw.startsWith("data:")) continue
            val absolute = runCatching {
                val uri = Uri.parse(raw)
                if (uri.isAbsolute) uri.toString() else Uri.parse(pageUrl).resolve(raw)
            }.getOrNull() ?: raw
            if (absolute.startsWith("http")) return absolute
        }
        return null
    }

    //endregion

    private suspend fun remember(url: String, image: String?) {
        val entry = CacheEntry(url = url, image = image, at = System.currentTimeMillis())
        synchronized(memory) {
            memory[url] = entry
            while (memory.size > MAX_MEMORY) {
                memory.remove(memory.keys.first())
            }
        }
        runCatching {
            preferences.edit { it[keyFor(url)] = cacheAdapter.toJson(entry) }
        }
    }

    private data class CacheEntry(
        val url: String,
        val image: String?,
        val at: Long
    ) {
        val expired: Boolean
            get() = System.currentTimeMillis() - at >
                if (image == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val MAX_OEMBED_BYTES = 64 * 1024
        private const val MAX_PAGE_BYTES = 300 * 1024
        private const val MAX_PARALLEL = 6
        private const val MAX_MEMORY = 200

        /** Resolved images are stable (a video's thumbnail does not change). */
        private const val POSITIVE_TTL_MS = 30L * 24 * 3_600_000
        /** "No image" answers may be transient (site offline, A/B) — re-check sooner. */
        private const val NEGATIVE_TTL_MS = 18L * 3_600_000

        private val THUMBNAIL_REGEX = Regex("\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"")

        private val OG_PATTERNS = listOf(
            Regex(
                """<meta\b[^>]*property\s*=\s*["']og:image(?::url)?["'][^>]*content\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:image(?::url)?["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """<meta\b[^>]*name\s*=\s*["']twitter:image[^"']*["'][^>]*content\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        private fun keyFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            return "preview:" + digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
