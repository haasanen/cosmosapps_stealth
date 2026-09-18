package com.cosmos.unreddit.data.remote.api.redgifs

/**
 * Redgifs issues SHORT-LIVED temporary tokens for its API
 * (`/v2/auth/temporary`). They are JWTs carrying an `exp` (epoch seconds) claim.
 *
 * A cached token that outlives its `exp` is rejected by the API with 401, and the
 * sharp-poster swap (`InAppVideoResolver.sharpPreview`) swallows that failure — so a
 * stale-but-cached token silently keeps every redgifs post frozen on its CDN-frosted
 * preview even when the user turns "Show NSFW preview" ON (the 2026-09-18 "toggle the
 * blur and it doesn't always work, feels like caching" report).
 *
 * These helpers are pure (no Android API, no clock read) so the decision can be
 * unit-tested on the JVM: [validUntilMillis] turns a token into an expiry timestamp
 * and [isStale] decides whether a cached token must be re-fetched.
 *
 * `java.util.Base64` is unavailable below API 26 and `android.util.Base64` is a
 * framework class that is mocked out in JVM unit tests, so [base64UrlDecode] is a
 * small self-contained decoder (base64url alphabet, optional padding).
 */
object RedgifsTokenExpiry {

    /** Assume 24 h when the token is not a decodable JWT with an `exp` claim. */
    const val FALLBACK_LIFETIME_MS = 24L * 60L * 60L * 1000L

    /** Refresh this long BEFORE the stated expiry so a slow API never sees an expired token. */
    private const val REFRESH_MARGIN_MS = 10L * 60L * 1000L

    private val EXP = Regex(""""exp"\s*:\s*(\d+)""")

    private val B64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * The moment (epoch millis) after which [token] must be refreshed.
     * `exp` minus a safety margin, or `now + 24 h` when the token carries no
     * decodable `exp`.
     */
    fun validUntilMillis(token: String, now: Long = System.currentTimeMillis()): Long {
        val exp = expSeconds(token) ?: return now + FALLBACK_LIFETIME_MS
        return exp * 1000L - REFRESH_MARGIN_MS
    }

    /** True when a token cached as valid until [validUntil] must be re-fetched at [now]. */
    fun isStale(validUntil: Long, now: Long): Boolean = now >= validUntil

    /** The JWT `exp` claim (epoch seconds), or null when the token is not a JWT / has no `exp`. */
    fun expSeconds(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = base64UrlDecode(parts[1]) ?: return null
        return EXP.find(payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /** Base64url decode (A–Z a–z 0–9 - _, optional `=` padding) to a UTF-8 string. */
    fun base64UrlDecode(input: String): String? {
        val chars = input
            .trimEnd('=')
            .map { if (it == '-') '+' else if (it == '_') '/' else it }
        val out = ByteArray(chars.size * 3 / 4 + 4)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (c in chars) {
            val value = B64_ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return runCatching { out.copyOf(outIndex).decodeToString() }.getOrNull()
    }
}
