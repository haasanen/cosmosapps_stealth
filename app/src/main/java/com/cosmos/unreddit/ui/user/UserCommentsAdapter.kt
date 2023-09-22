package com.cosmos.unreddit.ui.user

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.db.MoreItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.databinding.ItemUserCommentBinding
import com.cosmos.unreddit.ui.common.viewholder.CommentViewHolder
import com.cosmos.unreddit.ui.common.widget.RedditView

class UserCommentsAdapter(
    context: Context,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null,
    private val commentClickListener: CommentClickListener
) : PagingDataAdapter<FeedItem, CommentViewHolder>(COMMENT_COMPARATOR) {

    interface CommentClickListener {
        fun onClick(comment: CommentItem)

        fun onLongClick(comment: CommentItem)
    }

    private val colorPrimary by lazy {
        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return CommentViewHolder(
            ItemUserCommentBinding.inflate(inflater, parent, false),
            colorPrimary,
            commentClickListener,
            onLinkClickListener
        )
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val comment = getItem(position) as? CommentItem ?: return
        holder.bind(comment)
    }

    companion object {
        private val COMMENT_COMPARATOR = object : DiffUtil.ItemCallback<FeedItem>() {

            override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
                return when (oldItem) {
                    is PostItem -> oldItem.id == (newItem as? PostItem)?.id
                    is CommentItem -> oldItem.id == (newItem as? CommentItem)?.id
                    is MoreItem -> oldItem.id == (newItem as? MoreItem)?.id
                }
            }

            override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
                return oldItem == newItem
            }

            override fun getChangePayload(oldItem: FeedItem, newItem: FeedItem): Any {
                return newItem
            }
        }
    }
}
