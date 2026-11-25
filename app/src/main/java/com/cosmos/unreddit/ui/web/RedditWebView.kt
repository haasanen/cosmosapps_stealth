package com.cosmos.unreddit.ui.web

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cosmos.unreddit.ui.base.BaseFragment
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

    fun setup(webView: WebView, host: BaseFragment) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            userAgentString = SearchWebUrls.cleanUserAgent(userAgentString)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Reddit's web UI follows prefers-color-scheme; allow the WebView to
            // report dark when the app is in its dark theme (API 29+ WebViews).
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        webView.isVerticalScrollBarEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString() ?: return true
                // Sub-frames (recaptcha, media embeds) always load in the WebView.
                if (!request.isForMainFrame) return false
                // Search pages stay: tabs/filters/pagination are search URLs.
                if (SearchWebUrls.isSearchPage(url)) return false
                // Everything else — posts, subreddits, users, external links —
                // goes through the app's deep-link router (it browser-falls-back
                // for links it does not recognize).
                host.linkHandler.handleLink(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(BANNER_JS, null)
            }
        }
    }

    fun load(webView: WebView, url: String) {
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
    private const val BANNER_JS =
        "(() => { for (const s of ['#AppPromotionBanner', 'faceplate-sketch-promo-banner', " +
        "'smart-banner', '[data-testid*=\"promoted-activity-app\"]']) " +
        "document.querySelectorAll(s).forEach(e => e.remove()); })()"
}
