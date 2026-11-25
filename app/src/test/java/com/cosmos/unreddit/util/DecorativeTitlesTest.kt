package com.cosmos.unreddit.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins for the r/Piracy "title not rendered correctly" report (2026-11-25).
 * The stored title string was pulled from reddit's own subreddit record and
 * verified codepoint by codepoint: "Piracy: " followed by LATIN LETTER SMALL
 * CAPITAL s/a/I/l, T/h/E, H/I/G/h, S/E/S (U+A731, U+1D00, U+026A, U+029F,
 * U+1D1B, U+029C, U+1D07, U+0262). U+026A has no glyph in the Android
 * fallback chain — that is the tofu box the owner saw.
 */
class DecorativeTitlesTest {

    @Test
    fun piracyTitleFoldsToTheIntendedCapitals() {
        val piracyTitle = "Piracy: \uA731\u1D00\u026A\u029F \u1D1B\u029C\u1D07 \u029C\u026A\u0262\u029C \uA731\u1D07\u1D00\uA731"
        assertEquals("Piracy: SAIL THE HIGH SEAS", DecorativeTitles.fold(piracyTitle))
    }

    @Test
    fun asciiTitlesPassThroughUntouched() {
        assertEquals("DJI Neo Owners", DecorativeTitles.fold("DJI Neo Owners"))
    }

    @Test
    fun genuineIpaLettersAreNotFolded() {
        // esh U+0283 and open-e U+025B are real IPA letters (names do not
        // match the small-capital toolkit) and must survive untouched.
        assertEquals("\u0283\u025B", DecorativeTitles.fold("\u0283\u025B"))
    }

    @Test
    fun emojiAndDecorativeMixKeepsEmoji() {
        assertEquals("\uD83D\uDE02 S", DecorativeTitles.fold("\uD83D\uDE02 \uA731"))
    }

    @Test
    fun subscriptAndModifierLettersFoldToo() {
        // U+2090 LATIN SUBSCRIPT SMALL LETTER A, U+1D47 MODIFIER LETTER SMALL B
        assertEquals("AB", DecorativeTitles.fold("\u2090\u1D47"))
    }

    @Test
    fun emptyAndWhitespaceAreStable() {
        assertEquals("", DecorativeTitles.fold(""))
        assertEquals("  ", DecorativeTitles.fold("  "))
    }
}
