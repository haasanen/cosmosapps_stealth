package com.cosmos.unreddit.ui.postlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineDispatcher
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.ClickableMovementMethod

class PostListAdapter(
    private val repository: PostListRepository,
    private val postClickListener: PostClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null,
    // Paging runs its internal page-appends (differCallback.onInserted ->
    // Adapter.notifyItemRangeInserted) on this dispatcher. The default Dispatchers.Main takes
    // the coroutine fast-path and runs that notify INLINE, which can land inside a RecyclerView
    // layout/scroll pass and throw IllegalStateException("Cannot call this method while
    // RecyclerView is computing a layout or scrolling"). This dispatcher always posts to the
    // main looper, so the append lands in its own message after the in-flight pass completes —
    // the same between-frames safety AsyncListDiffer/submitList gives the home feed.
    mainDispatcher: CoroutineDispatcher = PostListFrameDispatcher()
) : PagingDataAdapter<PostEntity, RecyclerView.ViewHolder>(
    POST_COMPARATOR,
    mainDispatcher = mainDispatcher
) {

    // Tracks the RecyclerView we are attached to so data-set notifications can be deferred to
    // the next frame when the list is mid-layout. Firing a notify* while RecyclerView is
    // computing a layout throws IllegalStateException("Cannot call this method while
    // RecyclerView is computing a layout or scrolling"). The home feed avoids this via
    // submitList (which posts its update between frames), but a Paging adapter notifies inline —
    // opening a subreddit with the spoiler/NSFW preview setting enabled rebinds the whole list
    // during its initial layout pass and used to crash (r/outerwilds, 2026-09-16).
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
    }

    /** Runs [action] now, or posts it to the next frame if the list is computing a layout. */
    private fun runWhenListIdle(action: () -> Unit) {
        val rv = attachedRecyclerView
        if (rv != null && rv.isComputingLayout()) {
            rv.post(action)
        } else {
            action()
        }
    }

    interface PostClickListener {
        fun onClick(post: PostEntity)

        fun onLongClick(post: PostEntity)

        fun onMenuClick(post: PostEntity)

        fun onImageClick(post: PostEntity)

        fun onVideoClick(post: PostEntity)

        fun onLinkClick(post: PostEntity)

        fun onSaveClick(post: PostEntity)
    }

    interface Listener {
        fun onClick(position: Int, isLong: Boolean = false)

        fun onMediaClick(position: Int)

        fun onMenuClick(position: Int)

        fun onSaveClick(position: Int)
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
        showSpoilerPreview = false,
        autoplayPreviews = true
    )
        set(value) {
            if (field.showNsfwPreview != value.showNsfwPreview ||
                field.showSpoilerPreview != value.showSpoilerPreview ||
                field.autoplayPreviews != value.autoplayPreviews
            ) {
                field = value
                // A full rebind of a list that is mid-layout (opening a subreddit with the
                // spoiler/NSFW preview setting enabled fires this during the first layout
                // pass) would throw — defer it to the next frame when that happens.
                runWhenListIdle { notifyDataSetChanged() }
            }
        }

    private val listener = object : Listener {
        override fun onClick(position: Int, isLong: Boolean) {
            getItem(position)?.let {
                if (isLong) {
                    postClickListener.onLongClick(it)
                } else {
                    setPostSeen(position, it)
                    postClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getItem(position)?.let {
                setPostSeen(position, it)
                when (it.type) {
                    PostType.IMAGE -> postClickListener.onImageClick(it)
                    PostType.LINK -> postClickListener.onLinkClick(it)
                    PostType.VIDEO -> postClickListener.onVideoClick(it)
                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onMenuClick(it)
            }
        }

        override fun onSaveClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onSaveClick(it)
                it.saved = !it.saved
                runWhenListIdle { notifyItemChanged(position, it) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            // Text post
            PostType.TEXT.value -> PostViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false),
                listener,
                clickableMovementMethod
            )
            // Image post
            PostType.IMAGE.value -> PostViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Video post
            PostType.VIDEO.value -> PostViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Link post
            PostType.LINK.value -> PostViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false),
                listener
            )
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position)?.type?.value ?: -1
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return

        when (getItemViewType(position)) {
            // Text post
            PostType.TEXT.value -> (holder as PostViewHolder.TextPostViewHolder).bind(
                item,
                contentPreferences
            )
            // Image post
            PostType.IMAGE.value -> (holder as PostViewHolder.ImagePostViewHolder).bind(
                item,
                contentPreferences
            )
            // Video post
            PostType.VIDEO.value -> (holder as PostViewHolder.VideoPostViewHolder).bind(
                item,
                contentPreferences
            )
            // Link post
            PostType.LINK.value -> (holder as PostViewHolder.LinkPostViewHolder).bind(
                item,
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
            val item = getItem(position) ?: return
            (holder as? PostViewHolder)?.update(item)
        }
    }

    private fun setPostSeen(position: Int, post: PostEntity) {
        post.seen = true
        runWhenListIdle { notifyItemChanged(position, post) }
    }

    companion object {
        private val POST_COMPARATOR = object : DiffUtil.ItemCallback<PostEntity>() {
            override fun areItemsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem == newItem
            }

            override fun getChangePayload(oldItem: PostEntity, newItem: PostEntity): Any {
                return newItem
            }
        }
    }
}
