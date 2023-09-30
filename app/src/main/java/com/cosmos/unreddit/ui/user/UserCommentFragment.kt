package com.cosmos.unreddit.ui.user

import androidx.fragment.app.FragmentTransaction
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.paging.PagingData
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.ui.commentmenu.CommentMenuFragment
import com.cosmos.unreddit.ui.common.fragment.PagingListFragment
import com.cosmos.unreddit.ui.postdetails.PostDetailsFragment
import com.cosmos.unreddit.util.extension.launchRepeat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UserCommentFragment : PagingListFragment<UserCommentsAdapter, FeedItem>(),
    UserCommentsAdapter.CommentClickListener {

    override val viewModel: UserViewModel by hiltNavGraphViewModels(R.id.user)

    override val flow: Flow<PagingData<FeedItem>>
        get() = viewModel.commentDataFlow

    override val showItemDecoration: Boolean
        get() = true

    override fun bindViewModel() {
        super.bindViewModel()
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.lastRefreshComment.collect {
                    setRefreshTime(it)
                }
            }
        }
    }

    override fun createPagingAdapter(): UserCommentsAdapter {
        return UserCommentsAdapter(requireContext(), this, this)
    }

    override fun onClick(comment: CommentItem) {
        requireActivity().supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .add(
                R.id.fragment_container,
                PostDetailsFragment.newInstance(comment.postId, comment.service),
                PostDetailsFragment.TAG
            )
            .addToBackStack(null)
            .commit()
    }

    override fun onLongClick(comment: CommentItem) {
        CommentMenuFragment.show(childFragmentManager, comment, CommentMenuFragment.MenuType.USER)
    }
}
