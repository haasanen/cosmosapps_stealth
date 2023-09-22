package com.cosmos.unreddit.util.extension

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cosmos.unreddit.UnredditApplication
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.ui.sort.SortFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Fragment.launchRepeat(state: Lifecycle.State, block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(state) {
            block()
        }
    }
}

val Fragment.unredditApplication: UnredditApplication?
    get() = activity?.unredditApplication

fun Fragment.setFilteringListener(result: (Filtering?) -> Unit) {
    childFragmentManager.setFragmentResultListener(
        SortFragment.REQUEST_KEY_SORTING,
        viewLifecycleOwner
    ) { _, bundle ->
        val sorting = bundle.parcelable<Filtering>(SortFragment.BUNDLE_KEY_SORTING)
        result(sorting)
    }
}

fun Fragment.clearFilteringListener() {
    childFragmentManager.clearFragmentResultListener(SortFragment.REQUEST_KEY_SORTING)
}
