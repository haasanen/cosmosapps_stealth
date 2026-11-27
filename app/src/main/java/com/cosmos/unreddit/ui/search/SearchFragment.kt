package com.cosmos.unreddit.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.util.SearchWebUrls
import dagger.hilt.android.AndroidEntryPoint

/**
 * Global reddit search: HANDS OFF to the user's browser (the owner's Fennec,
 * measured working) and pops itself.
 *
 * Why not in-app (measured 2026-11-27, host + device, same exit IP
 * 161.51.252.52): reddit's search results require (a) its JS page and (b) a
 * captcha-gated data call; the JS-challenge resubmit is answered with the 403
 * "blocked by network security" page for Chromium-transport (system WebView,
 * full desktop Chromium on this host alike) and OkHttp alike, and for NONE of
 * them is that fixable by headers — the verdict follows the TLS/HTTP2
 * fingerprint (Gecko passes: the owner's logged-out Fennec works daily; even
 * curl passes the page fetch but only gets the card-less shell). A
 * Chromium-engine WebView can therefore never pass this gate; embedding Gecko
 * (~80MB on a 5.6MB app) is the only in-app alternative. Result taps come
 * back natively via the reddit.com deep links already in the manifest.
 *
 * The ViewModel is kept: setQuery() records search history.
 */
@AndroidEntryPoint
class SearchFragment : BaseFragment() {

    override val viewModel: SearchViewModel by viewModels()

    private val args: SearchFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FrameLayout(inflater.context)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val query = args.query
        viewModel.setQuery(query)
        SearchWebUrls.handOff(requireContext(), SearchWebUrls.search(query))
        findNavController().popBackStack()
    }
}
