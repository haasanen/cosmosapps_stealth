package com.cosmos.unreddit.util

/**
 * Where a typed query actually goes (v2.5.111 "manual subreddit" feature).
 *
 * Rationale: reddit's search is unreliable on this network class (JS-gated,
 * fingerprint-blocked for non-Gecko transports), but the subreddit/user
 * native RSS lane is dependable — feeds work. So explicit subreddit/user
 * input jumps straight to the native screen and skips search entirely.
 *
 * Rules (deterministic, no network, no guessing):
 *  - r/<name>  (also /r/<name>, //r/<name>, and a pasted
 *    https://(www|old|new|m|np|ssl|amp).reddit.com/r/<name>/ URL) -> subreddit
 *  - u/<name>  (same form tolerance)                              -> user
 *  - bare token matching a valid subreddit name
 *    (3–21 chars [A-Za-z0-9_], alnum at both ends, no spaces)     -> subreddit
 *  - anything else -> ordinary search
 *
 * Bare-token note: a single short word like "piracy" becomes a subreddit
 * jump; multi-word queries always stay search. This is deliberate — single
 * words previously hit the unreliable search anyway, and the destination is
 * the same content on the reliable lane.
 */
sealed class Routing {
    data class Subreddit(val name: String) : Routing()
    data class User(val name: String) : Routing()
    data class Search(val query: String) : Routing()
}

object SearchRouting {

    // Explicit forms (r/x, u/x, pasted URLs) accept reddit's full name
    // charset — 3-21 [A-Za-z0-9_] — because the user's intent is explicit
    // and grandfathered spellings exist (e.g. r/____error).
    private const val LOOSE_NAME = "[A-Za-z0-9_]{3,21}"

    // Bare-token auto-jump is conservative: 3-21 [A-Za-z0-9_] starting and
    // ending alphanumeric, so partial words/typos stay in search instead of
    // jumping to a subreddit the user may not have meant.
    private const val STRICT_NAME = "[A-Za-z0-9][A-Za-z0-9_]{1,19}[A-Za-z0-9]"

    private const val HOST = "(?:(?:www|old|new|m|np|ssl|amp)\\.)?reddit\\.com"

    // Optional leading slashes OR a reddit.com URL prefix, then r|u/NAME with
    // optional trailing slash/query.
    private val prefixed = Regex(
        "^(?:/*|https?://" + HOST + "/)(user|u|r)/(" + LOOSE_NAME + ")(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    private val bareSubreddit = Regex(STRICT_NAME)

    /** Decide the destination for [query]; never null. */
    fun target(query: String): Routing {
        val q = query.trim()

        prefixed.matchEntire(q)?.let { m ->
            val name = m.groupValues[2]
            return if (m.groupValues[1].equals("r", ignoreCase = true)) {
                Routing.Subreddit(name)
            } else {
                Routing.User(name) // "u" or "user"
            }
        }

        // A bare valid name (and no accidental URL/whitespace) jumps; reddit
        // names are case-insensitive, canonical form keeps the user's typing.
        if (bareSubreddit.matches(q)) return Routing.Subreddit(q)

        return Routing.Search(q)
    }
}
