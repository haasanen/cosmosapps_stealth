package com.cosmos.unreddit.ui.postdetails

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.stealth.sdk.util.Resource
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.db.MoreItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.databinding.ItemCommentBinding
import com.cosmos.unreddit.databinding.ItemMoreBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.extension.formatNumber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommentAdapter(
    context: Context,
    mainImmediateDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
    private val repository: StealthRepository,
    private val feedableMapper: FeedableMapper,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null,
    private val onCommentLongClick: (CommentItem) -> Unit
) : ListAdapter<FeedItem, RecyclerView.ViewHolder>(COMMENT_COMPARATOR) {

    var postItem: PostItem? = null

    var savedIds: List<String> = emptyList()

    private val scope = CoroutineScope(Job() + mainImmediateDispatcher)

    private val commentOffset by lazy {
        context.resources.getDimension(R.dimen.comment_offset)
    }
    private val popInAnimation by lazy {
        AnimationUtils.loadAnimation(context, R.anim.pop_in)
    }
    private val popOutAnimation by lazy {
        AnimationUtils.loadAnimation(context, R.anim.pop_out)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            Type.COMMENT.value ->
                CommentViewHolder(ItemCommentBinding.inflate(inflater, parent, false))
            Type.MORE.value ->
                MoreViewHolder(ItemMoreBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is CommentItem -> Type.COMMENT.value
            is MoreItem -> Type.MORE.value
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            Type.COMMENT.value -> {
                (holder as CommentViewHolder).bind(getItem(position) as CommentItem)
            }
            Type.MORE.value -> (holder as MoreViewHolder).bind(getItem(position) as MoreItem)
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
            val comment = getItem(position)
            if (holder is CommentViewHolder && comment is CommentItem) {
                holder.bindCommentHiddenIndicator(comment, true)
            }
        }
    }

    private fun onCommentClick(position: Int) {
        val newList = currentList.toMutableList()
        when (val comment = newList[position]) {
            is CommentItem -> {
                scope.launch { onCommentClick(position, newList, comment) }
            }
            is MoreItem -> {
                scope.launch { onMoreClick(position, newList, comment) }
            }
            else -> { /* ignore */ }
        }
    }

    private suspend fun onCommentClick(
        position: Int,
        newList: MutableList<FeedItem>,
        comment: CommentItem
    ) {
        if (!comment.hasReplies) return

        val startIndex = position + 1

        if (!comment.isExpanded) {
            comment.isExpanded = true

            val replies = getExpandedReplies(comment.replies)
            newList.addAll(startIndex, replies)
        } else {
            comment.isExpanded = false

            val replyCount = getReplyCount(startIndex, comment.depth ?: 0)
            comment.visibleReplyCount = replyCount

            val endIndex = startIndex + replyCount
            newList.subList(startIndex, endIndex).clear()
        }

        notifyItemChanged(position, comment)
        submitList(newList)
    }

    private suspend fun onMoreClick(
        position: Int,
        newList: MutableList<FeedItem>,
        comment: MoreItem
    ) {
        repository.getMore(comment.appendable)
            .map {
                when (it) {
                    is Resource.Success -> feedableMapper.dataToEntities(it.data)
                    is Resource.Error -> error(it.message)
                    is Resource.Exception -> error(it.throwable.message.orEmpty())
                }
            }
            .map { comments ->
                comment.apply { isLoading = false; isError = false }

                comments.forEach { comment ->
                    (comment as? CommentItem)?.run {
                        saved = savedIds.contains(comment.id)
                    }
                }

                if (comment.depth > 0) {
                    val parentComment = newList.find { it.id == comment.appendable.parentId }

                    if (parentComment != null &&
                        parentComment is CommentItem &&
                        parentComment.isExpanded
                    ) {
                        parentComment.replies.removeLastOrNull()
                        parentComment.replies.addAll(comments)
                    } else {
                        return@map newList
                    }
                }

                newList.removeAt(position)
                newList.addAll(position, comments)

                return@map newList
            }
            .flowOn(defaultDispatcher)
            .onStart {
                comment.apply { isLoading = true; isError = false }
                notifyItemChanged(position)
            }
            .catch {
                comment.apply { isLoading = false; isError = true }
                notifyItemChanged(position)
            }
            .collect { comments ->
                submitList(comments)
            }
    }

    private fun onCommentLongClick(position: Int) {
        val comment = getItem(position)
        if (comment is CommentItem) {
            comment.run {
                saved = savedIds.contains(id)
                onCommentLongClick.invoke(this)
            }
        }
    }

    private suspend fun getExpandedReplies(
        comments: List<FeedItem>
    ): List<FeedItem> = withContext(defaultDispatcher) {
        val replies = mutableListOf<FeedItem>()

        for (comment in comments) {
            when (comment) {
                is CommentItem -> {
                    replies.add(comment)
                    if (comment.isExpanded) {
                        replies.addAll(getExpandedReplies(comment.replies))
                    }
                }
                is MoreItem -> replies.add(comment)
                else -> continue
            }
        }

        return@withContext replies
    }

    private suspend fun getReplyCount(
        index: Int,
        depth: Int
    ): Int = withContext(defaultDispatcher) {
        var count = 0

        for (i in index until itemCount) {
            val item = getItem(i)
            val itemDepth = (item as? CommentItem)?.depth
                ?: (item as? MoreItem)?.depth
                ?: 0

            if (itemDepth > depth) {
                count++
            } else {
                break
            }
        }

        return@withContext count
    }

    fun cleanUp() {
        scope.cancel()
    }

    private inner class CommentViewHolder(
        private val binding: ItemCommentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: CommentItem) {
            binding.comment = comment

            binding.commentAuthor.apply {
                setTextColor(ContextCompat.getColor(context, comment.posterType.color))
            }

            binding.commentScore.text = itemView.context.getString(
                R.string.comment_score,
                comment.score.formatNumber()
            )

            binding.commentColorIndicator.setCommentColor(comment)

            bindCommentHiddenIndicator(comment, false)

            binding.commentBadge.apply {
                if (comment.authorBadge != null) {
                    visibility = View.VISIBLE

                    setBadge(comment.authorBadge)
                } else {
                    visibility = View.GONE
                }
            }

            binding.commentReactions.apply {
                if (comment.reactions != null) {
                    visibility = View.VISIBLE

                    setReactions(comment.reactions)
                } else {
                    visibility = View.GONE
                }
            }

            binding.commentOpText.visibility = if (comment.submitter) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                onCommentClick(bindingAdapterPosition)
            }

            itemView.setOnLongClickListener {
                onCommentLongClick(bindingAdapterPosition)
                true
            }

            binding.commentBody.apply {
                setText(comment.bodyText)
                setOnLinkClickListener(onLinkClickListener)
                setOnClickListener {
                    onCommentClick(bindingAdapterPosition)
                }
                setOnLongClickListener {
                    onCommentLongClick(bindingAdapterPosition)
                    true
                }
            }
        }

        fun bindCommentHiddenIndicator(comment: CommentItem, showAnimation: Boolean) {
            binding.commentHiddenIndicator.apply {
                if (comment.hasReplies && !comment.isExpanded) {
                    visibility = View.VISIBLE
                    text = comment.visibleReplyCount.toString()
                    if (showAnimation) startAnimation(popInAnimation)
                } else {
                    visibility = View.GONE
                    if (showAnimation) startAnimation(popOutAnimation)
                }
            }
        }
    }

    private inner class MoreViewHolder(
        private val binding: ItemMoreBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(more: MoreItem) {
            binding.more = more

            binding.progress.isVisible = more.isLoading
            binding.textError.isVisible = more.isError

            binding.commentColorIndicator.setCommentColor(more)

            itemView.setOnClickListener {
                onCommentClick(bindingAdapterPosition)
            }
        }
    }

    private fun ImageView.setCommentColor(comment: FeedItem) {
        val depth: Int
        val commentIndicator: Int?

        when (comment) {
            is CommentItem -> {
                depth = comment.depth ?: 0
                commentIndicator = comment.commentIndicator
            }
            is MoreItem -> {
                depth = comment.depth
                commentIndicator = comment.commentIndicator
            }
            else -> return
        }

        this.apply {
            if (depth == 0) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                commentIndicator?.let {
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, it)
                    )
                }
                val params = ConstraintLayout.LayoutParams(
                    layoutParams as ConstraintLayout.LayoutParams
                ).apply {
                    marginStart = (commentOffset * (depth - 1)).toInt()
                }
                layoutParams = params
            }
        }
    }

    private enum class Type(val value: Int) {
        COMMENT(0), MORE(1)
    }

    companion object {
        private val COMMENT_COMPARATOR = object : DiffUtil.ItemCallback<FeedItem>() {
            override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
                return when (oldItem) {
                    is CommentItem -> oldItem.id == (newItem as? CommentItem)?.id
                    is MoreItem -> oldItem.id == (newItem as? MoreItem)?.id
                    else -> error("Unsupported type")
                }
            }

            override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
