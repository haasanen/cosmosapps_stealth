package com.cosmos.unreddit.ui.common.viewholder

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.IncludePostFlairsBinding
import com.cosmos.unreddit.databinding.IncludePostInfoBinding
import com.cosmos.unreddit.databinding.IncludePostMetricsBinding
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.listener.ViewHolderItemListener
import com.cosmos.unreddit.ui.common.widget.ReactionView
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.extension.formatNumber
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.orFalse
import com.cosmos.unreddit.util.extension.setRatio
import com.cosmos.unreddit.util.extension.toPercentage

abstract class PostItemViewHolder(
    itemView: View,
    private val postInfoBinding: IncludePostInfoBinding,
    private val postMetricsBinding: IncludePostMetricsBinding,
    private val postFlairsBinding: IncludePostFlairsBinding,
    viewHolderItemListener: ViewHolderItemListener
) : RecyclerView.ViewHolder(itemView) {

    private val title = itemView.findViewById<TextView>(R.id.text_post_title)
    private val reactions = itemView.findViewById<ReactionView>(R.id.reactions)

    init {
        itemView.apply {
            setOnClickListener {
                viewHolderItemListener.onClick(bindingAdapterPosition)
            }
            setOnLongClickListener {
                viewHolderItemListener.onClick(bindingAdapterPosition, true)
                return@setOnLongClickListener true
            }
        }

        postMetricsBinding.buttonMore.setOnClickListener {
            viewHolderItemListener.onMenuClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonSave.setOnClickListener {
            viewHolderItemListener.onSaveClick(bindingAdapterPosition)
        }
    }

    open fun bind(
        post: PostItem,
        contentPreferences: ContentPreferences
    ) {
        postMetricsBinding.post = post
        postFlairsBinding.post = post

        postInfoBinding.run {
            this.post = post
            textPostAuthor.text = post.author
            textSubreddit.text = post.community
        }

        title.run {
            text = post.title
            setTextColor(ContextCompat.getColor(context, post.textColor))
        }

        postMetricsBinding.run {
            buttonSave.isChecked = post.saved

            setRatio(post.ratio?.toPercentage() ?: -1)

            textPostVote.text = post.score.formatNumber()
            textPostComments.text = post.commentCount.formatNumber()
        }

        reactions.apply {
            if (post.reactions != null && post.reactions.reactions.isNotEmpty()) {
                visibility = View.VISIBLE
                setReactions(post.reactions)
            } else {
                visibility = View.GONE
            }
        }

        postInfoBinding.textPostAuthor.apply {
            setTextColor(ContextCompat.getColor(context, post.posterType.color))
        }

        when {
            post.hasBadges -> {
                postFlairsBinding.root.visibility = View.VISIBLE
                postFlairsBinding.postBadge.apply {
                    if (post.postBadge != null && post.postBadge.badgeDataList.isNotEmpty()) {
                        visibility = View.VISIBLE

                        setBadge(post.postBadge)
                    } else {
                        visibility = View.GONE
                    }
                }
            }

            post.self.orFalse() -> {
                postFlairsBinding.root.visibility = View.GONE
            }

            else -> {
                postFlairsBinding.postBadge.visibility = View.GONE
            }
        }

        postInfoBinding.groupCrosspost.isVisible = false
    }

    open fun update(post: PostItem) {
        title.setTextColor(ContextCompat.getColor(title.context, post.textColor))
        postMetricsBinding.buttonSave.isChecked = post.saved
    }

    class ImagePostViewHolder(
        private val binding: ItemPostImageBinding,
        viewHolderItemListener: ViewHolderItemListener
    ) : PostItemViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        viewHolderItemListener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                viewHolderItemListener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            post: PostItem,
            contentPreferences: ContentPreferences
        ) {
            super.bind(post, contentPreferences)

            binding.imagePostPreview.load(
                post.preview?.source?.url,
                !post.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_image_fallback)
                fallback(R.drawable.preview_image_fallback)
            }

            binding.buttonTypeIndicator.apply {
                when (post.mediaType) {
                    MediaType.REDDIT_GALLERY, MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_gallery)
                    }

                    else -> {
                        visibility = View.GONE
                    }
                }
            }
        }
    }

    class VideoPostViewHolder(
        private val binding: ItemPostImageBinding,
        viewHolderItemListener: ViewHolderItemListener
    ) : PostItemViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        viewHolderItemListener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                viewHolderItemListener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            post: PostItem,
            contentPreferences: ContentPreferences
        ) {
            super.bind(post, contentPreferences)

            binding.imagePostPreview.load(
                post.preview?.source?.url,
                !post.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_video_fallback)
                fallback(R.drawable.preview_video_fallback)
            }

            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_play)
            }
        }
    }

    class TextPostViewHolder(
        private val binding: ItemPostTextBinding,
        viewHolderItemListener: ViewHolderItemListener,
        clickableMovementMethod: ClickableMovementMethod
    ) : PostItemViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        viewHolderItemListener
    ) {

        init {
            binding.textPostSelf.movementMethod = clickableMovementMethod
            binding.textPostSelf.setOnLongClickListener {
                viewHolderItemListener.onClick(bindingAdapterPosition, true)
                true
            }
        }

        override fun bind(
            post: PostItem,
            contentPreferences: ContentPreferences
        ) {
            super.bind(post, contentPreferences)

            val previewText = post.previewText

            binding.textPostSelf.apply {
                if (post.shouldShowPreview(contentPreferences) && previewText != null) {
                    binding.textPostSelfCard.visibility = View.VISIBLE
                    setText(previewText, false)
                    setTextColor(ContextCompat.getColor(context, post.textColor))
                } else {
                    binding.textPostSelfCard.visibility = View.GONE
                }
            }
        }

        override fun update(post: PostItem) {
            super.update(post)
            if (binding.textPostSelfCard.isVisible) {
                binding.textPostSelf.apply {
                    setTextColor(ContextCompat.getColor(context, post.textColor))
                }
            }
        }
    }

    class LinkPostViewHolder(
        private val binding: ItemPostLinkBinding,
        viewHolderItemListener: ViewHolderItemListener
    ) : PostItemViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        viewHolderItemListener
    ) {

        init {
            binding.imagePostLinkPreview.setOnClickListener {
                viewHolderItemListener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            post: PostItem,
            contentPreferences: ContentPreferences
        ) {
            super.bind(post, contentPreferences)

            binding.imagePostLinkPreview.load(
                post.preview?.source?.url,
                !post.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_link_fallback)
                fallback(R.drawable.preview_link_fallback)
            }
        }
    }
}
