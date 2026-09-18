package com.cosmos.unreddit.data.remote.api.redgifs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redgifs temporary-token expiry logic (the 2026-09-18 "blur toggle
 * doesn't always work, feels like caching" root cause: a forever-cached
 * short-lived token kept 401ing after it expired, and the sharp-poster swap
 * swallows that failure so redgifs posters stayed CDN-frosted).
 */
class RedgifsTokenExpiryTest {

    private fun jwt(payload: String): String {
        val b64 = { s: String ->
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.toByteArray())
        }
        return "${b64("header")}.${b64(payload)}.sig"
    }

    @Test
    fun `base64url decode matches the standard alphabet with url chars and no padding`() {
        // {"exp":1234567890}
        val payload = "{\"exp\":1234567890}"
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        assertEquals(payload, RedgifsTokenExpiry.base64UrlDecode(encoded))
        // with padding
        assertEquals(payload, RedgifsTokenExpiry.base64UrlDecode(encoded + "=="))
        // invalid char -> null
        assertNull(RedgifsTokenExpiry.base64UrlDecode("ab\u0024cd"))
    }

    @Test
    fun `jwt exp claim is extracted`() {
        assertEquals(1234567890L, RedgifsTokenExpiry.expSeconds(jwt("{\"exp\":1234567890}")))
    }

    @Test
    fun `non jwt token has no exp`() {
        assertNull(RedgifsTokenExpiry.expSeconds("just.a-string"))
        assertNull(RedgifsTokenExpiry.expSeconds("not-a-jwt"))
        // jwt without exp
        assertNull(RedgifsTokenExpiry.expSeconds(jwt("{\"foo\":1}")))
    }

    @Test
    fun `valid until is exp minus margin`() {
        val exp = 1_700_000_000L
        val validUntil = RedgifsTokenExpiry.validUntilMillis(jwt("{\"exp\":$exp}"), now = 1_699_000_000_000L)
        assertEquals(exp * 1000L - 10L * 60L * 1000L, validUntil)
    }

    @Test
    fun `non jwt falls back to 24h from now`() {
        val now = 1_699_000_000_000L
        val validUntil = RedgifsTokenExpiry.validUntilMillis("opaque.token.value", now)
        assertEquals(now + 24L * 60L * 60L * 1000L, validUntil)
    }

    @Test
    fun `stale decision`() {
        assertFalse(RedgifsTokenExpiry.isStale(validUntil = 1000L, now = 999L))
        assertTrue(RedgifsTokenExpiry.isStale(validUntil = 1000L, now = 1000L))
        assertTrue(RedgifsTokenExpiry.isStale(validUntil = 1000L, now = 1001L))
    }
}
