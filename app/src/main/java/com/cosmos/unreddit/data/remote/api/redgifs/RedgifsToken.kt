package com.cosmos.unreddit.data.remote.api.redgifs

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redgifs temporary-token holder.
 *
 * The token is short-lived (a JWT with an `exp` claim, ~24 h). Caching it FOREVER
 * (the old behavior: re-fetch only when null) meant that once it expired
 * server-side every `getGif` call 401'd, and the sharp-poster swap
 * (`InAppVideoResolver.sharpPreview`) swallows that failure — leaving redgifs posts
 * stuck on their CDN-frosted preview even with "Show NSFW preview" ON
 * (2026-09-18 "toggle the blur and it doesn't always work" report).
 *
 * Now: re-fetch when the cached token has reached its expiry
 * ([RedgifsTokenExpiry.validUntilMillis], a 10-min safety margin before `exp`,
 * 24 h fallback for non-JWT tokens), and [invalidate] on an auth failure so the
 * caller's retry gets a fresh one.
 */
@Singleton
class RedgifsToken @Inject constructor(private val redgifsApi: RedgifsApi) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var validUntil: Long = 0L

    private suspend fun getToken(): String? {
        val current = token
        if (current != null && !RedgifsTokenExpiry.isStale(validUntil, System.currentTimeMillis())) {
            return current
        }
        return runCatching {
            redgifsApi.getTemporaryToken()
        }.onSuccess { fetched ->
            token = fetched.token
            validUntil = RedgifsTokenExpiry.validUntilMillis(fetched.token)
        }.getOrNull()?.token
    }

    suspend fun getAuthorization(): String {
        val token = getToken() ?: ""
        return "Bearer $token"
    }

    /**
     * Drop the cached token (e.g. after the API rejected it with 401/403) so the
     * next [getAuthorization] fetches a fresh one.
     */
    fun invalidate() {
        token = null
        validUntil = 0L
    }
}
