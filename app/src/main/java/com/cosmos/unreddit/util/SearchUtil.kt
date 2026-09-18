package com.cosmos.unreddit.util

object SearchUtil {
    private const val QUERY_MIN_LENGTH = 3
    private const val QUERY_MAX_LENGTH = 20

    fun isQueryValid(query: String): Boolean {
        return query.length >= QUERY_MIN_LENGTH
    }

    /**
     * Manual subreddit shortcut: typing `r/<name>` in the search box opens
     * that subreddit directly instead of searching — useful when search
     * can't find a community (e.g. it is only available from certain
     * countries, or the community search is rate-limited).
     *
     * Returns the normalized subreddit name (no `r/`, no trailing slash)
     * or null when the query is not a subreddit shortcut. Case is preserved
     * as typed: the subreddit screen matches case-insensitively.
     */
    fun subredditShortcut(query: String): String? {
        val trimmed = query.trim()
        if (!trimmed.startsWith("r/", ignoreCase = true)) return null
        // First path segment only: r/linux or r/linux/about both open r/linux.
        val name = trimmed.substring(2).trim().trimEnd('/').substringBefore('/')
        if (name.isEmpty() || name.contains(' ')) return null
        return name
    }
}
