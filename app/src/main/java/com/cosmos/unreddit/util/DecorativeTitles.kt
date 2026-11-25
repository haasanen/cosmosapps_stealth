package com.cosmos.unreddit.util

/**
 * Restores readability of subreddit titles written with decorative Latin
 * look-alike letters.
 *
 * Why (measured 2026-11-25, r/Piracy report): r/Piracy's title is
 * "Piracy: \uA731\u1D00\u026A\u029F \u1D1B\u1D07\u029C \u029C\u026A\u0262\u029C \uA731\u1D00\uA731"
 * — LATIN LETTER SMALL CAPITAL letters (Latin Extended-B / IPA Extensions /
 * Modifier Letters blocks). Android system fonts cover most of that set but
 * not all of it (U+026A has no glyph in the platform fallback chain), so the
 * header rendered "SA[tofu]L THE H[tofu]GH SEAS" — visible tofu boxes.
 * Browsers dodge this with wider font fallback, and reddit's own web UI
 * ships its own font; a plain TextView cannot.
 *
 * These codepoints are NOT folded by Unicode NFKC (measured), so the app
 * keeps its own table: every codepoint whose Unicode name is
 * "LATIN LETTER SMALL CAPITAL X", "LATIN SUBSCRIPT SMALL LETTER x" or
 * "MODIFIER LETTER SMALL x" (the faux-small-caps toolkit subreddit owners
 * use) maps to the capital letter X. Genuine text never uses these
 * letters for meaning (real IPA letters like ʃ or ɛ have different names
 * and stay untouched), and a title written this way is by design
 * all-caps, so folding to capitals preserves the author's intent exactly.
 *
 * Table generated from Python unicodedata (the same Unicode data the
 * platform ships); 66 entries.
 */
object DecorativeTitles {

    private val smallCaps: Map<Int, String> = mapOf(
        0x0262 to "G",
        0x026a to "I",
        0x0274 to "N",
        0x0280 to "R",
        0x028f to "Y",
        0x0299 to "B",
        0x029c to "H",
        0x029f to "L",
        0x02b0 to "H",
        0x02b2 to "J",
        0x02b3 to "R",
        0x02b7 to "W",
        0x02b8 to "Y",
        0x02e1 to "L",
        0x02e2 to "S",
        0x02e3 to "X",
        0x1d00 to "A",
        0x1d04 to "C",
        0x1d05 to "D",
        0x1d07 to "E",
        0x1d0a to "J",
        0x1d0b to "K",
        0x1d0d to "M",
        0x1d0f to "O",
        0x1d18 to "P",
        0x1d1b to "T",
        0x1d1c to "U",
        0x1d20 to "V",
        0x1d21 to "W",
        0x1d22 to "Z",
        0x1d43 to "A",
        0x1d47 to "B",
        0x1d48 to "D",
        0x1d49 to "E",
        0x1d4d to "G",
        0x1d4f to "K",
        0x1d50 to "M",
        0x1d52 to "O",
        0x1d56 to "P",
        0x1d57 to "T",
        0x1d58 to "U",
        0x1d5b to "V",
        0x1d62 to "I",
        0x1d63 to "R",
        0x1d64 to "U",
        0x1d65 to "V",
        0x1d9c to "C",
        0x1da0 to "F",
        0x1dbb to "Z",
        0x2090 to "A",
        0x2091 to "E",
        0x2092 to "O",
        0x2093 to "X",
        0x2095 to "H",
        0x2096 to "K",
        0x2097 to "L",
        0x2098 to "M",
        0x2099 to "N",
        0x209a to "P",
        0x209b to "S",
        0x209c to "T",
        0x2c7c to "J",
        0xa730 to "F",
        0xa731 to "S",
        0xa7af to "Q",
        0x107a5 to "Q"
    )

    /** Folds decorative small-capital/subscript/superscript Latin letters to
     *  their plain capitals; every other character is passed through. */
    fun fold(text: String): String {
        // Fast path: pure ASCII titles (the overwhelming majority) skip the map.
        if (text.all { it.code < 128 }) {
            return text
        }
        val sb = StringBuilder(text.length)
        for (c in text) {
            val folded = smallCaps[c.code]
            if (folded == null) sb.append(c) else sb.append(folded)
        }
        return sb.toString()
    }
}
