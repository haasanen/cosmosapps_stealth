package com.cosmos.unreddit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.cosmos.unreddit.MainActivity.BottomNavigationState.LEFT_HANDED
import com.cosmos.unreddit.MainActivity.BottomNavigationState.NOT_INITIALIZED
import com.cosmos.unreddit.MainActivity.BottomNavigationState.RIGHT_HANDED
import com.cosmos.unreddit.data.feed.FeedCoordinator
import com.cosmos.unreddit.databinding.ActivityMainBinding
import com.cosmos.unreddit.ui.postlist.PostListFragment
import com.cosmos.unreddit.util.HideBottomViewBehavior
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.clearWindowInsetsListener
import com.cosmos.unreddit.util.extension.currentNavigationFragment
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.unredditApplication
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {

    // v2.5.53: feed the coordinator's foreground visibility (onStart..onStop).
    // v2.5.54: field injection (not constructor) so the class keeps a no-arg
    // constructor — the Instantiatable lint check requires it for the manifest
    // activity. Hilt populates injected fields before onCreate, so the first
    // onStart (which calls onForeground) always sees a non-null field.
    @Inject
    lateinit var feedCoordinator: FeedCoordinator

    private lateinit var binding: ActivityMainBinding

    private val viewModel: UiViewModel by viewModels()

    private lateinit var navController: NavController

    private var bottomNavigationState: BottomNavigationState = NOT_INITIALIZED

    // v2.5.53: feed the coordinator's foreground gate. Failed-sub retry rounds
    // run ONLY while the app is visible (onStart..onStop): a backgrounded
    // process's network is suspended on this device, so a background round is
    // guaranteed to fail — v2.5.52 burned its whole retry budget that way.
    override fun onStart() {
        super.onStart()
        feedCoordinator.onForeground()
    }

    override fun onStop() {
        super.onStop()
        feedCoordinator.onBackground()
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(unredditApplication.appTheme)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initNavigation()

        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.navigationVisibility
                    // Drop the first item to let initBottomNavigationView manage the visibility
                    .drop(1)
                    .collect(this@MainActivity::showNavigation)
            }

            launch {
                viewModel.leftHandedMode.collect { leftHandedMode ->
                    when (bottomNavigationState) {
                        NOT_INITIALIZED -> initBottomNavigationView(leftHandedMode)
                        RIGHT_HANDED -> if (leftHandedMode) initBottomNavigationView(true)
                        LEFT_HANDED -> if (!leftHandedMode) initBottomNavigationView(false)
                    }
                }
            }
        }

        // v2.5.98: VIEW deep links were already consumed by the nav host
        // during graph setup; the share sheet (ACTION_SEND text/plain) is
        // ours to route.
        handleSharedIntent(intent)
    }

    // v2.5.98: shared links open the link in-app. Extract the URL from the
    // share text and push it through the same nav deep links the VIEW intent
    // filter feeds (post /r/ /user/ gallery redd.it). A share whose URL is
    // not a known reddit destination opens in the browser instead of dumping
    // the user on a blank home tab.
    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = LinkUtil.extractUrlFromSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?: return
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.logException("share intent: URL parse failed: $url", e)
            return
        }
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
        if (!navController.handleDeepLink(viewIntent)) {
            try {
                startActivity(viewIntent)
            } catch (e: ActivityNotFoundException) {
                // No browser on the device: nothing sensible left to do — but say so.
                com.cosmos.unreddit.ui.postlist.FeedDebug.logException("share intent: no browser on device", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun initNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                as NavHostFragment
        navController = navHostFragment.navController.apply {
            addOnDestinationChangedListener(this@MainActivity)
        }

        binding.bottomNavigation.run {
            setupWithNavController(navController)
            setOnItemReselectedListener {
                when (it.itemId) {
                    R.id.home -> (currentNavigationFragment as? PostListFragment)?.scrollToTop()
                    else -> {
                        // Ignore
                    }
                }
            }
        }
    }

    private fun initBottomNavigationView(leftHandedMode: Boolean) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.run {
                updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = insets.bottom +
                            resources.getDimension(R.dimen.bottom_navigation_margin).toInt()
                }

                clearWindowInsetsListener()
            }

            windowInsets
        }

        binding.bottomNavigation.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            gravity = if (leftHandedMode) {
                Gravity.BOTTOM or Gravity.START
            } else {
                Gravity.BOTTOM or Gravity.END
            }
            behavior = HideBottomViewBehavior<BottomNavigationView>(leftHandedMode)
        }

        val radius = resources.getDimension(R.dimen.bottom_navigation_radius)
        val bottomNavigationBackground = binding.bottomNavigation.background
                as? MaterialShapeDrawable

        bottomNavigationBackground?.run {
            val builder = shapeAppearanceModel.toBuilder()

            if (leftHandedMode) {
                builder.apply {
                    setTopRightCorner(CornerFamily.ROUNDED, radius)
                    setBottomRightCorner(CornerFamily.ROUNDED, radius)

                    setTopLeftCorner(CornerFamily.CUT, 0F)
                    setBottomLeftCorner(CornerFamily.CUT, 0F)
                }
            } else {
                builder.apply {
                    setTopRightCorner(CornerFamily.CUT, 0F)
                    setBottomRightCorner(CornerFamily.CUT, 0F)

                    setTopLeftCorner(CornerFamily.ROUNDED, radius)
                    setBottomLeftCorner(CornerFamily.ROUNDED, radius)
                }
            }

            shapeAppearanceModel = builder.build()
        }

        // Wait for the view to be ready to show/hide it (otherwise width could be 0)
        binding.bottomNavigation.post {
            showNavigation(viewModel.navigationVisibility.value, false)
        }

        bottomNavigationState = if (leftHandedMode) LEFT_HANDED else RIGHT_HANDED
    }

    private fun showNavigation(show: Boolean, animate: Boolean = true) {
        val layoutParams = binding.bottomNavigation.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = layoutParams.behavior as HideBottomViewBehavior?

        if (show) {
            behavior?.run {
                enabled = true
                slideIn(binding.bottomNavigation, animate)
            }
        } else {
            behavior?.run {
                enabled = false
                slideOut(binding.bottomNavigation, animate)
            }
        }
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        when (destination.id) {
            R.id.postListFragment,
            R.id.subscriptionsFragment,
            R.id.subredditFragment,
            R.id.profileFragment,
            R.id.preferencesFragment -> {
                viewModel.setNavigationVisibility(true)
            }

            else -> viewModel.setNavigationVisibility(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bottomNavigationState = NOT_INITIALIZED
    }

    private enum class BottomNavigationState {
        NOT_INITIALIZED, RIGHT_HANDED, LEFT_HANDED
    }
}
