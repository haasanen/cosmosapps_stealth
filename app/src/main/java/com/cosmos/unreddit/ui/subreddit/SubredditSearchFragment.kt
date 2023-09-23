package com.cosmos.unreddit.ui.subreddit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.FragmentSubredditSearchBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.common.adapter.FeedItemListAdapter
import com.cosmos.unreddit.ui.loadstate.NetworkLoadStateAdapter
import com.cosmos.unreddit.ui.sort.SortFragment
import com.cosmos.unreddit.util.SearchUtil
import com.cosmos.unreddit.util.extension.addLoadStateListener
import com.cosmos.unreddit.util.extension.applyWindowInsets
import com.cosmos.unreddit.util.extension.betterSmoothScrollToPosition
import com.cosmos.unreddit.util.extension.clearFilteringListener
import com.cosmos.unreddit.util.extension.hideSoftKeyboard
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.loadSubredditIcon
import com.cosmos.unreddit.util.extension.onRefreshFromNetwork
import com.cosmos.unreddit.util.extension.setFilteringListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SubredditSearchFragment : BaseFragment() {

    private var _binding: FragmentSubredditSearchBinding? = null
    private val binding get() = _binding!!

    override val viewModel: SubredditSearchViewModel by viewModels()

    private val args: SubredditSearchFragmentArgs by navArgs()

    private lateinit var feedItemListAdapter: FeedItemListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setSubreddit(args.community)
        viewModel.setService(args.service)
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
        initRecyclerView()
        bindViewModel()
        binding.loadingState.infoRetry.setActionClickListener { feedItemListAdapter.retry() }
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

            launch {
                viewModel.searchData.collect {
                    binding.loadingState.infoRetry.hide()
                }
            }

            launch {
                viewModel.contentPreferences.collect {
                    feedItemListAdapter.contentPreferences = it
                }
            }

            launch {
                viewModel.filtering.collect {
                    binding.appBar.sortIcon.setFiltering(it)
                }
            }

            launch {
                viewModel.feedItemDataFlow.collectLatest {
                    feedItemListAdapter.submitData(it)
                }
            }
        }
    }

    private fun initRecyclerView() {
        feedItemListAdapter = FeedItemListAdapter(this, this).apply {
            addLoadStateListener(binding.listPost, binding.loadingState) {
                showRetryBar()
            }
        }

        binding.listPost.apply {
            applyWindowInsets(left = false, top = false, right = false)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = feedItemListAdapter.withLoadStateHeaderAndFooter(
                header = NetworkLoadStateAdapter { feedItemListAdapter.retry() },
                footer = NetworkLoadStateAdapter { feedItemListAdapter.retry() }
            )
        }

        launchRepeat(Lifecycle.State.STARTED) {
            feedItemListAdapter.onRefreshFromNetwork {
                scrollToTop()
            }
        }
    }

    private fun initAppBar() {
        with(binding.appBar) {
            subredditImage.loadSubredditIcon(args.icon)
            sortCard.setOnClickListener { showSortDialog() }
            cancelCard.setOnClickListener { cancelSearch() }
            backCard.setOnClickListener { onBackPressed() }
            label.setOnClickListener { showSearchInput(true) }
            root.setOnClickListener { showSearchInput(true) }
            searchInput.apply {
                addTarget(backCard)
                addTarget(subredditImage)
                addTarget(label)
                addTarget(sortIcon)
                addTarget(sortCard)
                addTarget(cancelCard)
                setSearchActionListener {
                    handleSearchAction(it)
                }
            }
        }
    }

    private fun initResultListener() {
        setFilteringListener { filtering -> filtering?.let { viewModel.setFiltering(it) } }
    }

    private fun scrollToTop() {
        binding.listPost.betterSmoothScrollToPosition(0)
    }

    private fun showSearchInput(show: Boolean) {
        binding.appBar.searchInput.show(binding.appBar.root, show) {
            with(binding.appBar) {
                backCard.isVisible = !show
                label.isVisible = !show
                sortCard.isVisible = !show
                sortIcon.isVisible = !show
                subredditImage.isVisible = !show
                cancelCard.isVisible = show
            }
        }
    }

    private fun showSortDialog() {
        SortFragment.show(
            childFragmentManager,
            viewModel.filtering.value,
            SortFragment.SortType.SEARCH
        )
    }

    private fun showRetryBar() {
        if (!binding.loadingState.infoRetry.isVisible) {
            binding.loadingState.infoRetry.show()
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
        clearFilteringListener()
        _binding = null
    }

    companion object {
        const val TAG = "SubredditSearchFragment"
    }
}
