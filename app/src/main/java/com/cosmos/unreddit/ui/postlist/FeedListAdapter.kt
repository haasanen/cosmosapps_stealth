package com.cosmos.unreddit.ui.postlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.DateUtil

/**
 * Adapter for the official home feed, driven by [FeedCoordinator] instead of Paging.
 *
 * It is a plain [ListAdapter] over `List<PostEntity>` so the progressive fan-out can
 * re-emit a growing list on every subreddit that finishes: [submitList] diffs by id, so
 * only the rows that actually changed rebind. This is what makes "watch the feed fill in
 * subreddit by subreddit" cheap.
 *
 * The cell tree, view holders and click semantics are shared with [PostListAdapter] (the
 * Paging home / single-sub adapter) via [PostListAdapter.Listener] /
 * [PostListAdapter.PostClickListener]; only the data plumbing differs.
 *
 * When [cachedIds] is non-empty, posts whose id is in the set are served from the local
 * feed cache (not refreshed this cycle) and get a "(cached)" suffix on their timestamp —
 * the user's way of telling fresh posts from cached ones at a glance.
 */
class FeedListAdapter(
    private val postClickListener: PostListAdapter.PostClickListener,
    private val onLinkClickListener: com.cosmos.unreddit.ui.common.widget.RedditView.OnLinkClickListener? = null
) : ListAdapter<PostEntity, RecyclerView.ViewHolder>(POST_COMPARATOR) {

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

    /** Ids of posts currently served from the local cache (not refreshed this cycle). */
    var cachedIds: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val clickableMovementMethod = ClickableMovementMethod(
        object : ClickableMovementMethod.OnClickListener {
            override fun onLinkClick(link: String) {
                onLinkClickListener?.onLinkClick(link)
            }

            override fun onLinkLongClick(link: String) {
                onLinkClickListener?.onLinkLongClick(link)
            }

            override fun onClick() { /* ignore */ }
            override fun onLongClick() { /* ignore */ }
        }
    )

    private val listener = object : PostListAdapter.Listener {
        override fun onClick(position: Int, isLong: Boolean) {
            getItem(position)?.let {
                if (isLong) {
                    postClickListener.onLongClick(it)
                } else {
                    it.seen = true
                    notifyItemChanged(position, it)
                    postClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getItem(position)?.let {
                it.seen = true
                when (it.type) {
                    PostType.IMAGE -> postClickListener.onImageClick(it)
                    PostType.LINK -> postClickListener.onLinkClick(it)
                    PostType.VIDEO -> postClickListener.onVideoClick(it)
                    else -> { /* ignore */ }
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getItem(position)?.let { postClickListener.onMenuClick(it) }
        }

        override fun onSaveClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onSaveClick(it)
                it.saved = !it.saved
                notifyItemChanged(position, it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PostType.TEXT.value -> PostViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false), listener, clickableMovementMethod
            )
            PostType.IMAGE.value -> PostViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false), listener
            )
            PostType.VIDEO.value -> PostViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false), listener
            )
            PostType.LINK.value -> PostViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false), listener
            )
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int =
        getItem(position)?.type?.value ?: -1

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when (getItemViewType(position)) {
            PostType.TEXT.value -> (holder as PostViewHolder.TextPostViewHolder).bind(item, contentPreferences)
            PostType.IMAGE.value -> (holder as PostViewHolder.ImagePostViewHolder).bind(item, contentPreferences)
            PostType.VIDEO.value -> (holder as PostViewHolder.VideoPostViewHolder).bind(item, contentPreferences)
            PostType.LINK.value -> (holder as PostViewHolder.LinkPostViewHolder).bind(item, contentPreferences)
            else -> throw IllegalArgumentException("Unknown type")
        }
        applyCachedBadge(holder, item)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            getItem(position)?.let { applyCachedBadge(holder, it) }
        } else {
            getItem(position)?.let { (holder as? PostViewHolder)?.update(it) }
        }
    }

    /** Append " (cached)" to the post timestamp when the row came from the local cache. */
    private fun applyCachedBadge(holder: RecyclerView.ViewHolder, item: PostEntity) {
        val vh = holder as? PostViewHolder ?: return
        vh.postInfoTextPostDate?.let { tv ->
            val base = DateUtil.getTimeDifference(tv.context, item.created)
            tv.text = if (cachedIds.contains(item.id)) "$base (cached)" else base
        }
    }

    companion object {
        private val POST_COMPARATOR = object : DiffUtil.ItemCallback<PostEntity>() {
            override fun areItemsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: PostEntity, newItem: PostEntity): Any = newItem
        }
    }
}
