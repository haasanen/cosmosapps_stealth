package com.cosmos.unreddit.util

/**
 * URL building + browser handoff for reddit.com search.
 *
 * Background (measured 2026-11-27, host + device, same exit IP): reddit
 * search is JS-only, and its gate rejects the system WebView (Chromium
 * transport fingerprint) at the JS-challenge resubmit with a 403 "blocked by
 * network security" page — headers/UA-CH cannot change that verdict (a full
 * desktop Chromium here is blocked identically while curl and Gecko pass).
 * Search therefore hands off to the user's browser (their Fennec passes the
 * gate); result taps return natively via the manifest reddit.com deep links.
 *
 * Pure functions — JVM-tested in SearchWebUrlsTest.
 */
object SearchWebUrls {

    private const val SITE = "https://www.reddit.com"

    /** Global search page URL (reddit's own web UI). */
    fun search(query: String): String = "$SITE/search/?q=${encode(query)}"

    /** Subreddit-scoped search page URL (reddit's own web UI). */
    fun inSubreddit(subreddit: String, query: String): String {
        val name = subreddit.trim().trim('/').removePrefix("r/").trim('/')
        return "$SITE/r/$name/search/?q=${encode(query)}&restrict_sr=1"
    }

    fun isRedditHost(host: String): Boolean =
        host == "reddit.com" || host.endsWith(".reddit.com") ||
            host == "redd.it" || host.endsWith(".redd.it")


    /**
     * Which package the handoff intent should pin to: the resolved default
     * browser unless it is [ownPackage] (Stealth itself handles reddit.com
     * links, and pinning self would loop the search back into the app) or
     * nothing resolved; null means launch the plain implicit intent (system
     * chooser). Pure — JVM-pinned in SearchWebUrlsTest.
     */
    fun pickHandoffPackage(resolvedPackage: String?, ownPackage: String): String? =
        resolvedPackage?.takeIf { it != ownPackage }

    /**
     * Open [url] in the browser the user resolves as default (their Fennec on
     * the target device). If resolution would land back in Stealth itself
     * (reddit.com deep-link defaults), launch WITHOUT an explicit target so
     * the system chooser appears — never a self-loop.
     */
    fun handOff(context: android.content.Context, url: String) {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        val resolver = context.packageManager.resolveActivity(
            view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        pickHandoffPackage(
            resolver?.activityInfo?.packageName,
            context.packageName
        )?.let { view.setPackage(it) }
        // The framework rejects startActivity from non-Activity contexts
        // without NEW_TASK; fragments pass their Activity context and need no
        // flag, but hardening the seam keeps any future non-Activity caller
        // from crashing on the exact exception real Android throws.
        if (context !is android.app.Activity) {
            view.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (e: android.content.ActivityNotFoundException) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.logException(
                "no browser available for search handoff url=$url", e)
            android.widget.Toast.makeText(context,
                "No browser found to open the search",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun encode(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
