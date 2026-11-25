package com.cosmos.unreddit.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.FragmentSearchBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.web.RedditWebView
import com.cosmos.unreddit.util.SearchUtil
import com.cosmos.unreddit.util.SearchWebUrls
import com.cosmos.unreddit.util.extension.clearSortingListener
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.setSortingListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global reddit search. Since 2026-11-25 reddit renders search results
 * client-side only (the served HTML carries zero cards; the data call sits
 * behind a reCAPTCHA Enterprise gate and plain HTTP clients get the 403
 * "blocked by network security" page), the app embeds reddit's own search
 * page in the system WebView — the same engine family the user's browser
 * uses, and the browser search works — and routes result taps into the
 * native screens via [RedditWebView]. See ui/web/RedditWebView.kt.
 */
@AndroidEntryPoint
class SearchFragment : BaseFragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    override val viewModel: SearchViewModel by hiltNavGraphViewModels(R.id.search)

    private val args: SearchFragmentArgs by navArgs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val query = args.query

            viewModel.setQuery(query)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val query = viewModel.query.value.takeIf { it.isNotBlank() } ?: args.query

        binding.appBar.searchInput.setText(query)

        initResultListener()
        initAppBar()
        bindViewModel()

        RedditWebView.setup(binding.webSearch, this)
        loadSearch(query)

        lifecycleScope.launch {
            delay(250)
            showSearchInput(false)
        }
    }

    private fun loadSearch(query: String) {
        RedditWebView.load(binding.webSearch, SearchWebUrls.search(query))
    }

    private fun bindViewModel() {
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.query.collect { query ->
                    query.takeIf { it.isNotBlank() }?.let {
                        binding.appBar.label.text = query
                    }
                }
            }
        }
    }

    private fun initAppBar() {
        with(binding.appBar) {
            // Sorting lives on the web page now (its own filter row).
            sortCard.isVisible = false
            sortIcon.isVisible = false
            label.setOnClickListener { showSearchInput(true) }
            root.setOnClickListener { showSearchInput(true) }
            searchInput.apply {
                addTarget(backCard)
                addTarget(label)
                addTarget(cancelCard)
                setSearchActionListener {
                    handleSearchAction(it)
                }
            }
            backCard.setOnClickListener { onBackPressed() }
            cancelCard.setOnClickListener { showSearchInput(false) }
        }
    }

    private fun initResultListener() {
        setSortingListener { sorting -> sorting?.let { viewModel.setSorting(it) } }
    }

    private fun showSearchInput(show: Boolean) {
        binding.appBar.searchInput.apply {
            show(binding.appBar.root, show) {
                with(binding.appBar) {
                    backCard.isVisible = !show
                    label.isVisible = !show
                    cancelCard.isVisible = show
                }
            }
            setSelection(text?.length ?: 0)
        }
    }

    private fun handleSearchAction(query: String) {
        val subreddit = SearchUtil.subredditShortcut(query)
        if (subreddit != null) {
            // `r/<name>` is a direct navigation, not a search.
            openSubreddit(subreddit)
            showSearchInput(false)
        } else if (SearchUtil.isQueryValid(query)) {
            viewModel.setQuery(query)
            // Reload explicitly: the query StateFlow conflates an identical
            // resubmit, but a repeated search must still refresh the page.
            loadSearch(query)
            showSearchInput(false)
        }
    }

    override fun onBackPressed() {
        if (binding.webSearch.canGoBack()) {
            binding.webSearch.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearSortingListener()
        RedditWebView.destroy(binding.webSearch)
        _binding = null
    }

    companion object {
        const val TAG = "SearchFragment"
    }
}
