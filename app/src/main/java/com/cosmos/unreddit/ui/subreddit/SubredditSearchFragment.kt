package com.cosmos.unreddit.ui.subreddit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.navArgs
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.FragmentSubredditSearchBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.util.SearchUtil
import com.cosmos.unreddit.util.SearchWebUrls
import com.cosmos.unreddit.util.extension.clearSortingListener
import com.cosmos.unreddit.util.extension.hideSoftKeyboard
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.loadSubredditIcon
import com.cosmos.unreddit.util.extension.setSortingListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * In-subreddit search: the app's own query field, which HANDS OFF to the
 * user's browser on submit (measured 2026-11-27: reddit's search gate
 * 403-blocks the system WebView's Chromium transport by TLS/HTTP2 fingerprint
 * — headers cannot change that verdict; Gecko-class browsers pass logged out;
 * full rationale in ui/search/SearchFragment). The screen stays open after
 * handoff so the query label keeps the search visible; result taps return
 * natively via the reddit.com deep links in the manifest.
 */
@AndroidEntryPoint
class SubredditSearchFragment : BaseFragment() {

    private var _binding: FragmentSubredditSearchBinding? = null
    private val binding get() = _binding!!

    override val viewModel: SubredditSearchViewModel by viewModels()

    private val args: SubredditSearchFragmentArgs by navArgs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setSubreddit(args.subreddit)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubredditSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initResultListener()
        initAppBar()
        bindViewModel()
    }

    override fun onResume() {
        super.onResume()
        if (binding.appBar.searchInput.isQueryEmpty()) {
            showSearchInput(true)
        } else {
            binding.appBar.apply {
                searchInput.isVisible = false
                cancelCard.isVisible = false
            }
        }
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

            launch {
                viewModel.subreddit.collect { subreddit ->
                    subreddit.takeIf { it.isNotBlank() }?.let {
                        binding.appBar.searchInput.hint =
                            getString(R.string.search_hint_subreddit, it)
                    }
                }
            }
        }
    }

    private fun initAppBar() {
        with(binding.appBar) {
            subredditImage.loadSubredditIcon(args.icon)
            // Sorting happens on the browser search page now.
            sortCard.isVisible = false
            sortIcon.isVisible = false
            cancelCard.setOnClickListener { cancelSearch() }
            backCard.setOnClickListener { onBackPressed() }
            label.setOnClickListener { showSearchInput(true) }
            root.setOnClickListener { showSearchInput(true) }
            searchInput.apply {
                addTarget(backCard)
                addTarget(subredditImage)
                addTarget(label)
                addTarget(cancelCard)
                setSearchActionListener {
                    handleSearchAction(it)
                }
            }
        }
    }

    private fun initResultListener() {
        setSortingListener { sorting -> sorting?.let { viewModel.setSorting(it) } }
    }

    private fun showSearchInput(show: Boolean) {
        binding.appBar.searchInput.show(binding.appBar.root, show) {
            with(binding.appBar) {
                backCard.isVisible = !show
                label.isVisible = !show
                subredditImage.isVisible = !show
                cancelCard.isVisible = show
            }
        }
    }

    private fun cancelSearch() {
        if (viewModel.query.value.isNotBlank()) {
            showSearchInput(false)
        } else {
            binding.appBar.searchInput.hideSoftKeyboard()
            onBackPressed()
        }
    }

    private fun handleSearchAction(query: String) {
        if (SearchUtil.isQueryValid(query)) {
            viewModel.setQuery(query)
            SearchWebUrls.handOff(
                requireContext(),
                SearchWebUrls.inSubreddit(args.subreddit, query)
            )
            showSearchInput(false)
        }
    }

    override fun onBackPressed() {
        if (binding.appBar.searchInput.isVisible && viewModel.query.value.isNotBlank()) {
            showSearchInput(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearSortingListener()
        _binding = null
    }
}
