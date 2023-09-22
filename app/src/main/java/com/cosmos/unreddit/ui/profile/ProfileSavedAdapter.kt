package com.cosmos.unreddit.ui.profile

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.SavedItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.databinding.ItemUserCommentBinding
import com.cosmos.unreddit.ui.common.listener.ItemClickListener
import com.cosmos.unreddit.ui.common.listener.ViewHolderItemListener
import com.cosmos.unreddit.ui.common.viewholder.CommentViewHolder
import com.cosmos.unreddit.ui.common.viewholder.PostItemViewHolder
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.ui.user.UserCommentsAdapter
import com.cosmos.unreddit.util.ClickableMovementMethod

class ProfileSavedAdapter(
    context: Context,
    private val postClickListener: ItemClickListener,
    private val commentClickListener: UserCommentsAdapter.CommentClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null,
) : ListAdapter<SavedItem, RecyclerView.ViewHolder>(SAVED_COMPARATOR) {

    private val colorPrimary by lazy {
        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
    }

    private val clickableMovementMethod = ClickableMovementMethod(
        object : ClickableMovementMethod.OnClickListener {
            override fun onLinkClick(link: String) {
                onLinkClickListener?.onLinkClick(link)
            }

            override fun onLinkLongClick(link: String) {
                onLinkClickListener?.onLinkLongClick(link)
            }

            override fun onClick() {
                // ignore
            }

            override fun onLongClick() {
                // ignore
            }
        }
    )

    var contentPreferences: ContentPreferences = ContentPreferences(
        showNsfw = false,
        showNsfwPreview = false,
        showSpoilerPreview = false
    )
        set(value) {
            if (field.showNsfwPreview != value.showNsfwPreview ||
                field.showSpoilerPreview != value.showSpoilerPreview
            ) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val listener = object : ViewHolderItemListener {
        override fun onClick(position: Int, isLong: Boolean) {
            getPost(position)?.let {
                if (isLong) {
                    postClickListener.onLongClick(it)
                } else {
                    postClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getPost(position)?.let {
                when (it.postType) {
                    PostType.IMAGE, PostType.VIDEO -> postClickListener.onMediaClick(it)
                    PostType.LINK -> postClickListener.onLinkClick(it)
                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onMenuClick(it)
            }
        }

        override fun onSaveClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onSaveClick(it)
                it.saved = !it.saved
                notifyItemChanged(position, it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            // Text post
            PostType.TEXT.value -> PostItemViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false),
                listener,
                clickableMovementMethod
            )
            // Image post
            PostType.IMAGE.value -> PostItemViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Video post
            PostType.VIDEO.value -> PostItemViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Link post
            PostType.LINK.value -> PostItemViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false),
                listener
            )
            COMMENT_TYPE -> {
                CommentViewHolder(
                    ItemUserCommentBinding.inflate(inflater, parent, false),
                    colorPrimary,
                    commentClickListener,
                    onLinkClickListener
                )
            }
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            // Text post
            PostType.TEXT.value -> (holder as PostItemViewHolder.TextPostViewHolder).bind(
                getPost(position)!!,
                contentPreferences
            )
            // Image post
            PostType.IMAGE.value -> (holder as PostItemViewHolder.ImagePostViewHolder).bind(
                getPost(position)!!,
                contentPreferences
            )
            // Video post
            PostType.VIDEO.value -> (holder as PostItemViewHolder.VideoPostViewHolder).bind(
                getPost(position)!!,
                contentPreferences
            )
            // Link post
            PostType.LINK.value -> (holder as PostItemViewHolder.LinkPostViewHolder).bind(
                getPost(position)!!,
                contentPreferences
            )
            COMMENT_TYPE -> (holder as CommentViewHolder).bind(
                (getItem(position) as SavedItem.Comment).comment
            )
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is SavedItem.Post -> item.post.postType.value
            is SavedItem.Comment -> COMMENT_TYPE
            else -> -1
        }
    }

    private fun getPost(position: Int): PostItem? {
        return (getItem(position) as? SavedItem.Post)?.post
    }

    companion object {
        private const val COMMENT_TYPE = 99

        private val SAVED_COMPARATOR = object : DiffUtil.ItemCallback<SavedItem>() {
            override fun areItemsTheSame(oldItem: SavedItem, newItem: SavedItem): Boolean {
                return if (oldItem is SavedItem.Post && newItem is SavedItem.Post) {
                    oldItem.post.id == newItem.post.id
                } else if (oldItem is SavedItem.Comment && newItem is SavedItem.Comment) {
                    oldItem.comment.id == newItem.comment.id
                } else {
                    false
                }
            }

            override fun areContentsTheSame(oldItem: SavedItem, newItem: SavedItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
