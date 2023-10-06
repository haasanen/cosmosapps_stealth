package com.cosmos.unreddit.ui.postdetails

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostHeaderBinding
import com.cosmos.unreddit.ui.common.listener.ItemClickListener
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.extension.color
import com.cosmos.unreddit.util.extension.formatNumber
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.orFalse
import com.cosmos.unreddit.util.extension.setRatio
import com.cosmos.unreddit.util.extension.setTint
import com.cosmos.unreddit.util.extension.toPercentage

class PostAdapter(
    private val contentPreferences: ContentPreferences,
    private val postClickListener: ItemClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null
) : RecyclerView.Adapter<PostAdapter.ViewHolder>() {

    private var post: PostItem? = null
    private var preview: Media? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemPostHeaderBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        post?.let { holder.bind(it) }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            post?.let { holder.update(it) }
        }
    }

    override fun getItemCount(): Int = 1

    fun setPost(post: PostItem, fromCache: Boolean) {
        var payload: Any? = null

        if (fromCache || this.post == null) {
            preview = post.preview
        } else {
            payload = post
        }

        this.post = post

        notifyItemChanged(0, payload)
    }

    inner class ViewHolder(
        private val binding: ItemPostHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: PostItem) {
            binding.includePostMetrics.post = post
            binding.includePostFlairs.post = post

            binding.includePostInfo.run {
                this.post = post
                textPostAuthor.text = post.author
                textSubreddit.text = post.community
                imageLabel.setTint(post.service.name.color)
            }

            binding.textPostTitle.text = post.title

            binding.includePostMetrics.run {
                buttonSave.isChecked = post.saved

                setRatio(post.ratio?.toPercentage() ?: -1)

                textPostVote.text = post.score.formatNumber()
                textPostComments.text = post.commentCount.formatNumber()
            }

            binding.includePostInfo.groupCrosspost.isVisible = false
            binding.includePostInfo.textPostAuthor.apply {
                setTextColor(ContextCompat.getColor(context, post.posterType.color))
            }

            bindText(post)

            bindAwards(post)

            bindFlairs(post)

            when (post.postType) {
                PostType.IMAGE -> {
                    bindImage(post) {
                        error(R.drawable.preview_image_fallback)
                        fallback(R.drawable.preview_image_fallback)
                    }
                    binding.imagePost.setOnClickListener { postClickListener.onMediaClick(post) }
                }

                PostType.LINK -> {
                    bindImage(post) {
                        error(R.drawable.preview_link_fallback)
                        fallback(R.drawable.preview_link_fallback)
                    }
                    binding.imagePost.setOnClickListener { postClickListener.onLinkClick(post) }
                }

                PostType.VIDEO -> {
                    bindImage(post) {
                        error(R.drawable.preview_video_fallback)
                        fallback(R.drawable.preview_video_fallback)
                    }
                    binding.imagePost.setOnClickListener { postClickListener.onMediaClick(post) }
                }

                else -> {
                    // Ignore
                }
            }

            binding.buttonTypeIndicator.apply {
                when {
                    post.mediaType == MediaType.REDDIT_GALLERY ||
                            post.mediaType == MediaType.IMGUR_ALBUM ||
                            post.mediaType == MediaType.IMGUR_GALLERY ||
                            post.mediaType == MediaType.GALLERY -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_gallery)
                    }

                    post.postType == PostType.VIDEO -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_play)
                    }

                    post.postType == PostType.LINK -> {
                        isVisible = true
                        setIcon(R.drawable.ic_link)
                    }

                    else -> {
                        visibility = View.GONE
                    }
                }
            }

            binding.includePostMetrics.buttonMore.setOnClickListener {
                postClickListener.onMenuClick(post)
            }

            binding.includePostMetrics.buttonSave.setOnClickListener {
                postClickListener.onSaveClick(post)
            }

            binding.includeCrosspost.root.isVisible = false
        }

        fun update(post: PostItem) {
            binding.includePostMetrics.post = post
            binding.includePostFlairs.post = post

            binding.includePostMetrics.buttonSave.isChecked = post.saved

            bindText(post)

            bindAwards(post)

            bindFlairs(post)
        }

        private fun bindText(post: PostItem) {
            binding.textPost.apply {
                if (post.bodyText.isNotEmpty()) {
                    visibility = View.VISIBLE
                    setText(post.bodyText)
                    setOnLinkClickListener(onLinkClickListener)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindFlairs(post: PostItem) {
            when {
                post.hasBadges -> {
                    binding.includePostFlairs.root.visibility = View.VISIBLE
                    binding.includePostFlairs.postBadge.apply {
                        if (post.postBadge != null && post.postBadge.badgeDataList.isNotEmpty()) {
                            visibility = View.VISIBLE

                            setBadge(post.postBadge)
                        } else {
                            visibility = View.GONE
                        }
                    }
                }

                post.self.orFalse() -> {
                    binding.includePostFlairs.root.visibility = View.GONE
                }

                else -> {
                    binding.includePostFlairs.postBadge.visibility = View.GONE
                }
            }
            binding.includePostInfo.postBadge.apply {
                if (post.authorBadge != null && post.authorBadge.badgeDataList.isNotEmpty()) {
                    visibility = View.VISIBLE

                    setBadge(post.authorBadge)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindAwards(post: PostItem) {
            binding.reactions.apply {
                if (post.reactions != null && post.reactions.total > 0) {
                    visibility = View.VISIBLE
                    setReactions(post.reactions)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindImage(
            post: PostItem,
            requestBuilder: ImageRequest.Builder.() -> Unit = {}
        ) {
            binding.imagePost.apply {
                visibility = View.VISIBLE
                load(
                    preview?.source?.url,
                    !post.shouldShowPreview(contentPreferences),
                    builder = requestBuilder
                )
            }
        }
    }
}
