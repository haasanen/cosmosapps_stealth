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
import com.cosmos.unreddit.data.feed.FeedCoordinator
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.data.remote.api.reddit.source.RedditOfficialSource
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        FeedDebug.log("PostListFragment.onCreateView")
        _binding = FragmentPostBinding.inflate(layoutInflater, container, false)
        FeedDebug.log("PostListFragment layout inflated")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initAppBar()
        initRecyclerView()
        initDrawer()
        FeedDebug.log("PostListFragment initAppBar/initRecyclerView/initDrawer done")
        bindViewModel()
        FeedDebug.log("PostListFragment.bindViewModel done (collectors attached)")

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

            // Legacy Paging home feed (every source EXCEPT the official one).
            // The legacy flow is `by lazy`: it builds and fires its own 73-sub fan-out
            // the MOMENT anything subscribes to it (a plain `combine` would, at
            // collection start, even if the result is discarded). So the subscription
            // itself must be gated: only when the source preference has RESOLVED
            // (usesCoordinator != null, i.e. the datastore has loaded) and the official
            // source is NOT selected do we attach to it. While the official source is
            // active, `emptyFlow()` keeps the chain warm without a single legacy
            // request. Previously the legacy flow was collected unconditionally and a
            // guard inside `collectLatest` skipped its output — but by then the second
            // parallel 73-sub fan-out was already in flight, doubling the reddit.com
            // load and throttling both fan-outs through CF (blank first launch).
            launch {
                viewModel.usesCoordinator
                    .filterNotNull()
                    .flatMapLatest { active ->
                        if (active) emptyFlow() else viewModel.postDataFlow
                    }
                    .collectLatest { pagingData ->
                        val n = FeedDebug.legacyEmissions.incrementAndGet()
                        if (n <= 5 || n % 50 == 0) {
                            FeedDebug.log("legacy paging emission #$n")
                        }
                        postListAdapter.submitData(pagingData)
                    }
            }

            // Progressive home feed (official source only): live cache-first render.
            // The renderer itself lives in renderFeedState() so the SAME path runs
            // both for live emissions and for the immediate re-render when the list
            // mode flips (states emitted before the flip must not be lost).
            launch {
                viewModel.feedState.collectLatest { state ->
                    // UNCAPPED on purpose (2026-09-04 "frozen dots" freeze): the cap that
                    // used to be here (first 10) made every later emission — including the
                    // ones during a settings->back recreate — invisible in the log, which
                    // was exactly why that bug could not be diagnosed from a device log.
                    val n = FeedDebug.feedStates.incrementAndGet()
                    FeedDebug.log("feedState #$n: posts=${state.posts.size} refresh=${state.refreshing} " +
                        "prog=${state.progress?.done ?: "-"}/${state.progress?.total ?: "-"} " +
                        "offline=${state.offline} err=${state.error ?: "null"}")
                    if (coordinatorMode) {
                        renderFeedState(state)
                    } else {
                        FeedDebug.log("feedState #$n DROPPED (coordinatorMode=false)")
                    }
                }
            }

            // Switch the list between the legacy Paging feed and the progressive one.
            // `null` = the source preference has not resolved yet (DataStore still
            // loading); the list keeps its initial (legacy) adapter and nothing is
            // re-swapped until a real value arrives.
            launch {
                viewModel.usesCoordinator.collect { active ->
                    FeedDebug.log("usesCoordinator -> $active")
                    if (active != null) applyListMode(active)
                }
            }

            // TEMP diagnostics: resolve the raw source preference for the panel.
            launch {
                preferencesRepository.getRedditSource().collect { v ->
                    FeedDebug.lastSourcePref.set("$v")
                    FeedDebug.log("source pref = $v")
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
        // NO no-op guard here. The list's adapter is reset to the legacy composite on
        // EVERY onViewCreated (see initRecyclerView), so after a fragment view recreate
        // (e.g. opening a sibling destination such as Settings, then pressing Back)
        // `coordinatorMode` can already equal `coordinator` while the RecyclerView is
        // physically on the WRONG adapter. Guarding on "did the value change" would skip
        // the re-attach and leave the home feed on the empty legacy list with the
        // (XML-default-visible) loading cradle frozen — the 2026-09-04 "frozen dots" freeze.
        // Re-applying is idempotent and cheap, and the driving StateFlow only re-emits on
        // change anyway, so there is no thrashing to guard against.
        coordinatorMode = coordinator

        val list = binding.listPost
        if (coordinator) {
            // The legacy adapter's withLoadStateHeaderAndFooter composite owns the
            // RecyclerView's current adapter; replace it wholesale.
            list.adapter = feedListAdapter
            progressiveScrollListener?.let { list.addOnScrollListener(it) }
            binding.pullRefresh.isVisible = true
            binding.loadingCradle.isVisible = false
            // Re-render the latest state NOW: anything emitted while the list was still
            // on the legacy adapter (mode not flipped yet) would otherwise be lost,
            // leaving the progressive list blank until the next emission. If the
            // coordinator has not emitted at all yet (preferences still resolving,
            // profile still loading) the screen must STILL not be blank: show the
            // initial header.
            val latest = latestFeedState
            if (latest != null) {
                renderFeedState(latest)
            } else {
                binding.feedProgress.root.isVisible = true
                binding.feedProgress.feedProgressText.text =
                    getString(R.string.feed_progress_initial)
            }
        } else {
            progressiveScrollListener?.let { list.removeOnScrollListener(it) }
            list.adapter = postListAdapter.withLoadStateHeaderAndFooter(
                header = NetworkLoadStateAdapter { postListAdapter.retry() },
                footer = NetworkLoadStateAdapter { postListAdapter.retry() }
            )
            binding.feedProgress.root.isVisible = false
        }
    }

    /**
     * The latest progressive feed state, kept so [applyListMode] can re-render it the
     * moment the list flips into coordinator mode (states emitted earlier must not be
     * lost to the mode race).
     */
    private var latestFeedState: FeedCoordinator.FeedState? = null

    /**
     * Render one progressive feed state.
     *
     * THE NEVER-BLANK RULE: while the official source is active the screen must always
     * show exactly one of — posts, the live progress header, or the error bar. A blank
     * screen (no posts, no header, no error) is a bug, not a state.
     */
    private fun renderFeedState(state: FeedCoordinator.FeedState) {
        latestFeedState = state
        binding.infoRetry.hide()
        // The loading cradle is a LEGACY-path indicator. In progressive mode it must
        // never be on screen: it inflates VISIBLE by XML default and its animation only
        // starts via the custom `isVisible = true` setter, so if any lifecycle path ever
        // leaves it visible-without-started the user sees a frozen dot that looks like a
        // total app freeze (2026-09-04 "frozen dots" report). Every progressive render
        // kills that state by construction.
        binding.loadingCradle.isVisible = false

        feedListAdapter.cachedIds = if (state.fromCacheOnly) {
            emptySet()
        } else {
            state.posts.map { it.id }.toSet() - state.freshIds
        }
        feedListAdapter.submitList(state.posts)

        // Progress header: visible whenever a refresh is in flight, with a label that
        // says WHAT is loading so a stuck load is screenshot-able. Never shown when the
        // feed is served from cache offline (that would claim to be loading).
        val progress = state.progress
        val showingProgress = state.refreshing ||
            (state.posts.isEmpty() && state.error == null && !state.offline)
        binding.feedProgress.root.isVisible = showingProgress
        if (showingProgress) {
            binding.feedProgress.feedProgressText.text = progressLabel(progress)
        }

        if (state.offline && state.posts.isEmpty()) {
            // Offline with an empty cache: nothing to show and nothing to fetch —
            // say so, instead of leaving a blank screen.
            binding.infoRetry.setMessage(getString(R.string.feed_offline))
            binding.infoRetry.show()
        }

        state.error?.let {
            binding.infoRetry.setMessage(it.take(400))
            binding.infoRetry.show()
        }
    }

    /**
     * One label for the progress header, ordered by how much we know:
     *  - a sub just finished  -> "Loading r/Steam — 12 / 73"
     *  - requests in flight    -> "Loading r/Steam + r/Games + r/PC… — 2 / 73"
     *  - nothing has finished  -> "Loading your 73 subreddits…"
     *  - refresh in flight but the fan-out hasn't emitted its first progress yet ->
     *    the same "your 73 subreddits" line (never an empty string).
     */
    private fun progressLabel(progress: RedditOfficialSource.FanOutProgress?): String {
        if (progress == null) {
            return getString(R.string.feed_progress_initial)
        }
        if (progress.done > 0 && progress.lastFinished != null) {
            return getString(
                R.string.feed_progress_loading,
                "r/${progress.lastFinished}", progress.done, progress.total
            )
        }
        val inFlight = progress.inFlight
        if (inFlight.isNotEmpty()) {
            val names = inFlight.take(3).joinToString(" + ") { "r/$it" } +
                if (inFlight.size > 3) "…" else ""
            return getString(R.string.feed_progress_inflight, names, progress.done, progress.total)
        }
        if (progress.total > 0 && progress.done == 0) {
            return getString(R.string.feed_progress_subs, progress.total)
        }
        return getString(R.string.feed_progress_warming, progress.done, progress.total)
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
