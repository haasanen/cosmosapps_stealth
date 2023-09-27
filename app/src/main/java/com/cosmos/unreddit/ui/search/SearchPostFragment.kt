package com.cosmos.unreddit.ui.search

import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.paging.PagingData
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.ui.common.adapter.FeedItemListAdapter
import com.cosmos.unreddit.ui.common.fragment.PagingListFragment
import com.cosmos.unreddit.util.extension.launchRepeat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchPostFragment : PagingListFragment<FeedItemListAdapter, FeedItem>() {

    override val viewModel: SearchViewModel by hiltNavGraphViewModels(R.id.search)

    override val flow: Flow<PagingData<FeedItem>>
        get() = viewModel.postDataFlow

    override val showItemDecoration: Boolean
        get() = true

    override fun bindViewModel() {
        super.bindViewModel()
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.contentPreferences.collect {
                    adapter.contentPreferences = it
                }
            }

            launch {
                viewModel.lastRefreshPost.collect {
                    setRefreshTime(it)
                }
            }
        }
    }

    override fun createPagingAdapter(): FeedItemListAdapter {
        return FeedItemListAdapter(this, this)
    }
}
