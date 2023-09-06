package com.cosmos.unreddit.ui.common.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.db.MoreItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.listener.ItemClickListener
import com.cosmos.unreddit.ui.common.listener.ViewHolderItemListener
import com.cosmos.unreddit.ui.common.viewholder.PostItemViewHolder
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.ClickableMovementMethod

class FeedItemListAdapter(
    private val itemClickListener: ItemClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null
) : PagingDataAdapter<FeedItem, RecyclerView.ViewHolder>(POST_COMPARATOR) {

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

    private val viewHolderItemListener = object : ViewHolderItemListener {
        override fun onClick(position: Int, isLong: Boolean) {
            getItem(position)?.let {
                if (isLong) {
                    itemClickListener.onLongClick(it)
                } else {
                    setPostSeen(position, it)
                    itemClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getItem(position)?.let {
                setPostSeen(position, it)
                when (Type.toType(getItemViewType(position))) {
                    Type.POST_IMAGE, Type.POST_VIDEO -> itemClickListener.onMediaClick(it)
                    Type.POST_LINK -> itemClickListener.onLinkClick(it)
                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getItem(position)?.let {
                itemClickListener.onMenuClick(it)
            }
        }

        override fun onSaveClick(position: Int) {
            getItem(position)?.let {
                itemClickListener.onSaveClick(it)
                it.saved = !it.saved
                notifyItemChanged(position, it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (Type.toType(viewType)) {
            // Text post
            Type.POST_TEXT -> PostItemViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false),
                viewHolderItemListener,
                clickableMovementMethod
            )
            // Image post
            Type.POST_IMAGE -> PostItemViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                viewHolderItemListener
            )
            // Video post
            Type.POST_VIDEO -> PostItemViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                viewHolderItemListener
            )
            // Link post
            Type.POST_LINK -> PostItemViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false),
                viewHolderItemListener
            )
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is PostItem -> Type.toType(item.postType).value
            is CommentItem -> error("CommentItem is not supported")
            is MoreItem -> error("MoreItem is not supported")
            null -> error("Item is null")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return

        when (Type.toType(getItemViewType(position))) {
            // Text post
            Type.POST_TEXT -> (holder as PostItemViewHolder.TextPostViewHolder).bind(
                item as PostItem,
                contentPreferences
            )
            // Image post
            Type.POST_IMAGE -> (holder as PostItemViewHolder.ImagePostViewHolder).bind(
                item as PostItem,
                contentPreferences
            )
            // Video post
            Type.POST_VIDEO -> (holder as PostItemViewHolder.VideoPostViewHolder).bind(
                item as PostItem,
                contentPreferences
            )
            // Link post
            Type.POST_LINK -> (holder as PostItemViewHolder.LinkPostViewHolder).bind(
                item as PostItem,
                contentPreferences
            )
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position) as? PostItem ?: return
            (holder as? PostItemViewHolder)?.update(item)
        }
    }

    private fun setPostSeen(position: Int, item: FeedItem) {
        item.seen = true
        notifyItemChanged(position, item)
    }

    private enum class Type(val value: Int) {
        POST_TEXT(0), POST_IMAGE(1), POST_VIDEO(2), POST_LINK(3);

        companion object {
            fun toType(value: Int) = entries.find { it.value == value }
            fun toType(postType: PostType) = when (postType) {
                PostType.TEXT -> POST_TEXT
                PostType.IMAGE -> POST_IMAGE
                PostType.VIDEO -> POST_VIDEO
                PostType.LINK -> POST_LINK
            }
        }
    }

    companion object {
        private val POST_COMPARATOR = object : DiffUtil.ItemCallback<FeedItem>() {
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
