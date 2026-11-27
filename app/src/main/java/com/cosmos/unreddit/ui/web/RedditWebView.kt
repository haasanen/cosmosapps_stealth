package com.cosmos.unreddit.ui.web

import android.os.Build
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.postlist.FeedDebug
import com.cosmos.unreddit.util.SearchWebUrls

/**
 * Configures a [WebView] to render reddit.com's OWN web UI and routes taps
 * through the app's native link handling.
 *
 * Why: reddit search pages became JS-only (2026-11-25, live-captured) — the
 * served HTML carries no result cards at all, the data call is gated behind a
 * reCAPTCHA Enterprise challenge, and non-JS clients are answered with the
 * 403 "blocked by network security" page. A real browser engine gets real
 * results; so the search screens embed reddit's own page in the system
 * WebView (the same engine family the user's Chrome uses — and the user's
 * browser search demonstrably works), and every result/community/user tap is
 * intercepted and opened in the app's native screens via [LinkHandler].
 */
object RedditWebView {

    fun setup(
        webView: WebView,
        host: BaseFragment,
        onBlocked: ((String) -> Unit)? = null
    ) {
        // Console messages from reddit's own JS go to the system log UNLIMITED
        // (owner ruling 2026-11-27: no self-imposed write limits — the log
        // buffer's own rotation decides what survives).
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            userAgentString = SearchWebUrls.cleanUserAgent(userAgentString)
            // The UA string alone does NOT fool reddit's gate: the Sec-CH-UA
            // client hints still say `"Android WebView"` and the owner's
            // device log (2026-11-26, vc207) shows the challenge resubmit
            // taking a main-frame 403 for exactly that fingerprint — while
            // the owner's Fennec browser (Gecko, NO Sec-CH-UA at all) loads
            // the same search on the same device/IP. The gate is therefore
            // anti-"WebView identity", not pro-Chromium. The
            // USER_AGENT_METADATA override (WebView M125+) rewrites the
            // Sec-CH-UA* request headers AND navigator.userAgentData
            // consistently from one metadata object — brands Chrome/Chromium,
            // versions copied from the engine's own Chrome/x.y.z.w token so
            // nothing disagrees. Feature-gated: older WebViews keep the
            // string-only behaviour (2.5.107 status quo), never a crash.
            val chromeFull = SearchWebUrls.chromeFullVersion(userAgentString)
            if (chromeFull != null &&
                WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
            ) {
                try {
                    val major = chromeFull.substringBefore('.')
                    val metadata = UserAgentMetadata.Builder()
                        .setBrandVersionList(
                            listOf(
                                UserAgentMetadata.BrandVersion.Builder()
                                    .setBrand("Google Chrome")
                                    .setMajorVersion(major)
                                    .setFullVersion(chromeFull)
                                    .build(),
                                UserAgentMetadata.BrandVersion.Builder()
                                    .setBrand("Chromium")
                                    .setMajorVersion(major)
                                    .setFullVersion(chromeFull)
                                    .build(),
                                UserAgentMetadata.BrandVersion.Builder()
                                    .setBrand("Not_A Brand")
                                    .setMajorVersion("24")
                                    .setFullVersion("24.0.0.0")
                                    .build()
                            )
                        )
                        .setFullVersion(chromeFull)
                        .setPlatform("Android")
                        .setPlatformVersion(Build.VERSION.RELEASE + ".0")
                        .setModel(Build.MODEL)
                        .setMobile(true)
                        .setBitness(64)
                        .setWow64(false)
                        .setArchitecture("arm64")
                        .build()
                    WebSettingsCompat.setUserAgentMetadata(this, metadata)
                    FeedDebug.log("search web: UA-CH override Chrome $chromeFull (Android ${Build.VERSION.RELEASE}, ${Build.MODEL})")
                } catch (t: Throwable) {
                    FeedDebug.logException("search web: UA-CH override failed", t)
                }
            } else {
                FeedDebug.log(
                    "search web: no UA-CH override (feature=" +
                        WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) +
                        " chromeToken=$chromeFull)"
                )
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Reddit's web UI follows prefers-color-scheme; allow the WebView to
            // report dark when the app is in its dark theme (API 29+ WebViews).
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        webView.isVerticalScrollBarEnabled = false
        // Diagnostics into FeedDebug (system log, tag FeedDbg): a WebView
        // search failure is invisible in a plain
        // logcat unless the WebView itself reports — and reddit JS errors and
        // load failures are exactly what a capture must contain (2026-11-25: a
        // user logcat from a failing build contained only lifecycle noise,
        // because nothing in the search path ever logged).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val line = "search web console [${message.messageLevel()}] " +
                    "${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                FeedDebug.log(line)
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString() ?: return true
                // Sub-frames (recaptcha, media embeds) always load in the WebView.
                if (!request.isForMainFrame) return false
                // Main-frame navigations ARE the story (challenge auto-resubmit
                // is one): log every routing decision so the chain is readable
                // without guessing which hops happened.
                if (SearchWebUrls.isSearchPage(url)) {
                    FeedDebug.log("search web nav: stay $url")
                    return false
                }
                FeedDebug.log("search web nav: route to app $url")
                // Everything else — posts, subreddits, users, external links —
                // goes through the app's deep-link router (it browser-falls-back
                // for links it does not recognize).
                host.linkHandler.handleLink(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                FeedDebug.log("search web: start $url")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    FeedDebug.log(
                        "search web FAIL main=${request.url} code=${errorResponse.errorCode} " +
                            "desc=${errorResponse.description}"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    val h = errorResponse.responseHeaders ?: emptyMap<String, String>()
                    fun hv(k: String) = h[k]?.take(140) ?: "-"
                    FeedDebug.log(
                        "search web HTTP ${errorResponse.statusCode} " +
                            "(${errorResponse.reasonPhrase}) main=${request.url}" +
                            " loc=${hv("location")} server=${hv("server")} cf-ray=${hv("cf-ray")}"
                    )
                    if (errorResponse.statusCode == 403) {
                        // The block page's own words disambiguate WHICH gate
                        // answered (network-security vs captcha vs login).
                        view.postDelayed({
                            view.evaluateJavascript(SNIPPET_JS) { r ->
                                FeedDebug.log("search web 403 body: $r")
                            }
                        }, 500)
                        onBlocked?.invoke(request.url.toString())
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                FeedDebug.log("search web: done $url")
                view.evaluateJavascript(BANNER_JS, null)
            }
        }
    }

    fun load(webView: WebView, url: String) {
        // The identity actually in force when the request goes out — settings
        // and overrides can differ from what setup() intended; one line per
        // navigation attempt is cheap and settles identity arguments forever.
        FeedDebug.log("search web UA: ${webView.settings.userAgentString?.take(150) ?: "n/a"}")
        webView.loadUrl(url)
    }

    /** Detach + destroy for `onDestroyView`; prevents the WebView outliving
     *  its fragment and leaking the activity context. */
    fun destroy(webView: WebView) {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Best-effort removal of reddit's "Open in the app" promo banners — they
     *  advertise a different (Play Store) build inside this app. Selectors are
     *  best-effort; a no-op when nothing matches. */
    /** First visible text of the served document — the block/captcha page
     *  identifies itself by wording, which no header tells us. */
    private const val SNIPPET_JS =
        "(() => { try { return String(document.body.innerText).slice(0, 180); } " +
        "catch (e) { return 'n/a'; } })()"

    private const val BANNER_JS =
        "(() => { for (const s of ['#AppPromotionBanner', 'faceplate-sketch-promo-banner', " +
        "'smart-banner', '[data-testid*=\"promoted-activity-app\"]']) " +
        "document.querySelectorAll(s).forEach(e => e.remove()); })()"
}
