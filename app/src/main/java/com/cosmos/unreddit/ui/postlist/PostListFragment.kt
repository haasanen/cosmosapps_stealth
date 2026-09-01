package com.cosmos.unreddit.ui.postlist

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.cosmos.unreddit.R
import com.cosmos.unreddit.UiViewModel
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.databinding.FragmentPostBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.common.widget.PullToRefreshLayout
import com.cosmos.unreddit.ui.common.widget.PullToRefreshView
import com.cosmos.unreddit.ui.loadstate.NetworkLoadStateAdapter
import com.cosmos.unreddit.ui.sort.SortFragment
import com.cosmos.unreddit.util.DateUtil
import com.cosmos.unreddit.util.extension.applyMarginWindowInsets
import com.cosmos.unreddit.util.extension.applyWindowInsets
import com.cosmos.unreddit.util.extension.betterSmoothScrollToPosition
import com.cosmos.unreddit.util.extension.clearNavigationListener
import com.cosmos.unreddit.util.extension.clearSortingListener
import com.cosmos.unreddit.util.extension.clearWindowInsetsListener
import com.cosmos.unreddit.util.extension.getFloatValue
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.onRefreshFromNetwork
import com.cosmos.unreddit.util.extension.setNavigationListener
import com.cosmos.unreddit.util.extension.setSortingListener
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PostListFragment : BaseFragment(), PullToRefreshLayout.OnRefreshListener {

    private var _binding: FragmentPostBinding? = null
    private val binding get() = _binding!!

    override val viewModel: PostListViewModel by activityViewModels()
    private val uiViewModel: UiViewModel by activityViewModels()

    // Workaround for nested CoordinatorLayout that prevents bottom navigation from being hidden on
    // scroll
    private val onOffsetChangedListener = object : AppBarLayout.OnOffsetChangedListener {
        var visible: Boolean = true
            private set

        override fun onOffsetChanged(appBarLayout: AppBarLayout?, verticalOffset: Int) {
            if (verticalOffset != 0 && visible) {
                visible = false
                uiViewModel.setNavigationVisibility(false)
            } else if (verticalOffset == 0 && !visible) {
                visible = true
                uiViewModel.setNavigationVisibility(true)
            }
        }
    }

    private val contentScale by lazy { resources.getFloatValue(R.dimen.subreddit_content_scale) }
    private val contentRadius by lazy { resources.getDimension(R.dimen.subreddit_content_radius) }
    private val contentElevation by lazy {
        resources.getDimension(R.dimen.subreddit_content_elevation)
    }

    private val isDrawerOpen: Boolean
        get() = binding.drawerLayout.isDrawerOpen(GravityCompat.START)

    private lateinit var postListAdapter: PostListAdapter

    private lateinit var feedListAdapter: FeedListAdapter

    /** Progressive (coordinator) scroll listener; active only in coordinator mode. */
    private var progressiveScrollListener: androidx.recyclerview.widget.RecyclerView.OnScrollListener? = null

    /** True while the list is driven by the progressive coordinator feed. */
    private var coordinatorMode = false

    private lateinit var profileAdapter: ProfileAdapter

    @Inject
    lateinit var repository: PostListRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initAppBar()
        initRecyclerView()
        initDrawer()
        bindViewModel()

        binding.infoRetry.apply {
            applyMarginWindowInsets(left = false, right = false, bottom = false)
            setActionClickListener {
                if (coordinatorMode) {
                    viewModel.pullToRefresh()
                } else {
                    postListAdapter.retry()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        initResultListener()
    }

    override fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { rootView, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.appBar.root.updateLayoutParams<AppBarLayout.LayoutParams> {
                topMargin = insets.top
            }

            binding.listProfiles.run {
                updatePadding(
                    paddingLeft,
                    insets.top,
                    paddingRight,
                    paddingBottom
                )
            }

            rootView.clearWindowInsetsListener()

            windowInsets
        }
    }

    private fun bindViewModel() {
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.contentPreferences.collect {
                    binding.infoRetry.hide()
                    if (coordinatorMode) {
                        feedListAdapter.contentPreferences = it
                    } else {
                        postListAdapter.contentPreferences = it
                    }
                }
            }

            launch {
                viewModel.profiles.collect {
                    profileAdapter.submitList(it)
                }
            }

            launch {
                viewModel.fetchData.collect {
                    if (!coordinatorMode) {
                        binding.infoRetry.hide()
                    }
                }
            }

            // Legacy Paging home feed (every source except the official one).
            launch {
                viewModel.postDataFlow.collectLatest {
                    if (coordinatorMode) return@collectLatest
                    postListAdapter.submitData(it)
                }
            }

            // Progressive home feed (official source only): live cache-first render.
            launch {
                viewModel.feedState.collectLatest { state ->
                    if (!coordinatorMode) return@collectLatest
                    binding.infoRetry.hide()
                    feedListAdapter.cachedIds = if (state.fromCacheOnly) {
                        emptySet()
                    } else {
                        state.posts.map { it.id }.toSet() - state.freshIds
                    }
                    feedListAdapter.submitList(state.posts)

                    // Progress header: spinner + "Fetching r/X — done / total" while filling.
                    val progress = state.progress
                    val showingProgress = state.refreshing
                    binding.feedProgress.root.isVisible = showingProgress && progress != null
                    if (showingProgress && progress != null) {
                        val label = progress.lastFinished?.let { "r/$it" } ?: ""
                        binding.feedProgress.feedProgressText.text =
                            getString(R.string.feed_progress_loading, label, progress.done, progress.total)
                    }

                    state.error?.let {
                        if (!coordinatorMode) return@collectLatest
                        binding.infoRetry.setMessage(it.take(400))
                        binding.infoRetry.show()
                    }
                }
            }

            // Switch the list between the legacy Paging feed and the progressive one.
            launch {
                viewModel.usesCoordinator.collect { active ->
                    applyListMode(active)
                }
            }

            launch {
                viewModel.sorting.collect {
                    binding.appBar.sortIcon.setSorting(it)
                }
            }

            launch {
                viewModel.currentProfile.collect {
                    binding.appBar.profileImage.setText(it.name)
                }
            }

            launch {
                viewModel.lastRefresh.collect {
                    val time = getString(R.string.last_refresh, DateUtil.getLocalizedTime(it))
                    (binding.pullRefresh.refreshView as? PullToRefreshView)?.setLastRefresh(time)
                }
            }
        }
    }

    private fun initDrawer() {
        binding.drawerLayout.apply {
            setScrimColor(Color.TRANSPARENT)
            drawerElevation = 0F
            addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                    val slideX = drawerView.width * slideOffset
                    val scale = 1 - (slideOffset / (contentScale * SCALE_FACTOR))
                    updateContainerView(
                        slideX,
                        scale,
                        slideOffset * contentElevation,
                        slideOffset * contentRadius
                    )
                }
            })
        }

        profileAdapter = ProfileAdapter { onProfileClick(it) }

        binding.listProfiles.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = profileAdapter
        }

        // Restore container view when drawer was open before
        if (viewModel.isDrawerOpen) {
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val scale = 1 - (1 / (contentScale * SCALE_FACTOR))
                    updateContainerView(
                        binding.navigationView.width.toFloat(),
                        scale,
                        contentElevation,
                        contentRadius
                    )
                    binding.navigationView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
            binding.navigationView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
    }

    private fun initRecyclerView() {
        postListAdapter = PostListAdapter(repository, this, this).apply {
            addLoadStateListener { loadState ->
                // The Paging listener only drives UI in legacy mode.
                if (coordinatorMode) return@addLoadStateListener

                val isLoading = loadState.source.refresh is LoadState.Loading

                binding.run {
                    if (!pullRefresh.isRefreshing) {
                        pullRefresh.isVisible = loadState.source.refresh is LoadState.NotLoading

                        loadingCradle.isVisible = isLoading
                    } else {
                        pullRefresh.setRefreshing(isLoading)
                    }
                }

                val errorState = loadState.source.refresh as? LoadState.Error
                errorState?.let {
                    // Show the real reason (e.g. the official-source diagnostic describing the
                    // page reddit.com actually served) instead of the static retry hint.
                    val realMessage = it.error.message?.takeIf { m -> m.isNotBlank() }
                    if (realMessage != null) {
                        binding.infoRetry.setMessage(realMessage.take(400))
                    }
                    binding.infoRetry.show()
                }
            }
        }

        // Progressive (coordinator) adapter: plain ListAdapter, no Paging.
        feedListAdapter = FeedListAdapter(this)

        // Scroll-triggered "load more" for the progressive feed.
        progressiveScrollListener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!coordinatorMode || dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val total = lm.itemCount
                if (total == 0) return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= total - LOAD_MORE_THRESHOLD) {
                    viewModel.loadMoreFeed()
                }
            }
        }

        binding.listPost.apply {
            applyWindowInsets(left = false, top = false, right = false)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = postListAdapter.withLoadStateHeaderAndFooter(
                header = NetworkLoadStateAdapter { postListAdapter.retry() },
                footer = NetworkLoadStateAdapter { postListAdapter.retry() }
            )
        }

        binding.pullRefresh.setOnRefreshListener(this)

        launchRepeat(Lifecycle.State.STARTED) {
            postListAdapter.onRefreshFromNetwork {
                scrollToTop()
            }
        }
    }

    /**
     * Swap the list between the legacy Paging feed and the progressive coordinator feed.
     * Never leaves the list without an adapter, so the home feed can never go blank
     * when the source preference resolves or changes.
     */
    private fun applyListMode(coordinator: Boolean) {
        if (coordinator == coordinatorMode) return
        coordinatorMode = coordinator

        val list = binding.listPost
        if (coordinator) {
            // The legacy adapter's withLoadStateHeaderAndFooter composite owns the
            // RecyclerView's current adapter; replace it wholesale.
            list.adapter = feedListAdapter
            progressiveScrollListener?.let { list.addOnScrollListener(it) }
            binding.pullRefresh.isVisible = true
            binding.loadingCradle.isVisible = false
        } else {
            progressiveScrollListener?.let { list.removeOnScrollListener(it) }
            list.adapter = postListAdapter.withLoadStateHeaderAndFooter(
                header = NetworkLoadStateAdapter { postListAdapter.retry() },
                footer = NetworkLoadStateAdapter { postListAdapter.retry() }
            )
            binding.feedProgress.root.isVisible = false
        }
    }

    private fun initAppBar() {
        binding.appBar.run {
            sortCard.setOnClickListener { showSortDialog() }
            profileImage.setOnClickListener { openProfileDrawer() }
            title.setOnClickListener { scrollToTop() }
        }
        binding.appBarLayout.addOnOffsetChangedListener(onOffsetChangedListener)
    }

    private fun initResultListener() {
        setSortingListener { sorting -> sorting?.let { viewModel.setSorting(it) } }

        setNavigationListener { showNavigation ->
            uiViewModel.setNavigationVisibility(showNavigation && onOffsetChangedListener.visible)
        }
    }

    fun scrollToTop() {
        binding.listPost.betterSmoothScrollToPosition(0)
    }

    private fun showSortDialog() {
        SortFragment.show(childFragmentManager, viewModel.sorting.value)
    }

    private fun updateContainerView(
        translationX: Float,
        scale: Float,
        elevation: Float,
        radius: Float
    ) {
        binding.container.apply {
            this.translationX = translationX
            this.scaleX = scale
            this.scaleY = scale
            this.cardElevation = elevation
            this.radius = radius
        }
    }

    private fun openProfileDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun closeProfileDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun onProfileClick(profile: Profile) {
        viewModel.selectProfile(profile)
        closeProfileDrawer()
        // Show app bar on profile change to prevent weird scrolling behaviors
        binding.appBarLayout.setExpanded(true)
    }

    override fun onRefresh() {
        if (coordinatorMode) {
            viewModel.pullToRefresh()
            binding.pullRefresh.setRefreshing(false)
        } else {
            postListAdapter.refresh()
        }
    }

    override fun onBackPressed() {
        if (isDrawerOpen) {
            closeProfileDrawer()
        } else {
            activity?.finish()
        }
    }

    override fun onStop() {
        super.onStop()
        clearSortingListener()
        clearNavigationListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        (binding.pullRefresh.refreshView as? PullToRefreshLayout.RefreshCallback)?.reset()

        viewModel.isDrawerOpen = isDrawerOpen

        _binding = null
    }

    companion object {
        const val TAG = "PostListFragment"

        private const val SCALE_FACTOR = 10

        /** Fire load-more when this many items from the end become visible. */
        private const val LOAD_MORE_THRESHOLD = 8
    }
}
