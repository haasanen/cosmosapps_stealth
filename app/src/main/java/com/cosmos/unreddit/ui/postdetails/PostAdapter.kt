package com.cosmos.unreddit.ui.postdetails

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostHeaderBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.ui.postlist.PostListAdapter
import com.cosmos.unreddit.ui.postlist.PostViewHolder
import com.cosmos.unreddit.util.FeedPreviewPlayerPool
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio
import com.google.android.exoplayer2.Player

class PostAdapter(
    initialPreferences: ContentPreferences,
    private val postClickListener: PostListAdapter.PostClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null
) : RecyclerView.Adapter<PostAdapter.ViewHolder>() {

    // The NSFW/spoiler preview settings are collected live by
    // PostDetailsFragment (2026-09-14 goblin_girl report: the detail header
    // snapshotted the preferences ONCE at screen init, so toggling the blur
    // setting while a post was open never re-rendered the header image).
    var contentPreferences: ContentPreferences = initialPreferences
        set(value) {
            if (field.showNsfwPreview != value.showNsfwPreview ||
                field.showSpoilerPreview != value.showSpoilerPreview ||
                field.autoplayPreviews != value.autoplayPreviews
            ) {
                field = value
                // No payload: a full rebind so bindImage reloads with the new
                // blur decision AND the video preview player is (re)evaluated —
                // toggling "Play video previews" while a post is open must start
                // or stop the in-header playback immediately.
                notifyItemChanged(0)
            }
        }

    private var post: PostEntity? = null
    private var preview: String? = null

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

    fun setPost(post: PostEntity, fromCache: Boolean) {
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

        /**
         * In-header muted preview playback, mirroring the feed's in-cell player
         * (2.5.74). The detail screen used to be a static tap-to-play preview —
         * the one place the "Play video previews" setting visibly did nothing
         * (2026-09-17 report, e.g. a video post opened from the feed). Now a
         * native reddit video (see canAutoplay: v.redd.it, or a GIF/animated
         * card's signed ?format=mp4 rendition) loops muted in the header when
         * the setting is on and the preview is allowed to show; tapping still
         * opens the full-screen MediaViewer with the user's sound settings.
         * External videos keep the still poster + play badge.
         */
        private val pool = FeedPreviewPlayerPool.get()

        private val token = object : FeedPreviewPlayerPool.Token {
            override fun playerAttached(player: Player) {
                binding.imagePostPlayer.player = player
            }

            override fun playerDetached() {
                binding.imagePostPlayer.player = null
                binding.imagePostPlayer.visibility = View.GONE
            }
        }

        /** The in-header player for the current post, or null. */
        private var player: Player? = null

        init {
            // Stop the stream when the header leaves the window (scrolled past,
            // view destroyed) so nothing keeps running unseen.
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    stopVideoPreview()
                }
            })
        }

        private fun startVideoPreview(post: PostEntity) {
            stopVideoPreview()
            if (post.type != PostType.VIDEO) return
            if (!PostViewHolder.VideoPostViewHolder.canAutoplay(
                    post,
                    contentPreferences.autoplayPreviews,
                    post.shouldShowPreview(contentPreferences)
                )
            ) return
            player = pool.acquire(binding.root.context, post.mediaUrl, token)
            binding.imagePostPlayer.visibility = View.VISIBLE
            binding.buttonTypeIndicator.visibility = View.GONE
        }

        private fun stopVideoPreview() {
            if (player != null) {
                pool.release(token)
                player = null
            }
            binding.imagePostPlayer.player = null
            binding.imagePostPlayer.visibility = View.GONE
        }

        fun bind(post: PostEntity) {
            stopVideoPreview()

            binding.includePostMetrics.post = post
            binding.includePostFlairs.post = post

            binding.includePostInfo.run {
                this.post = post
                textPostAuthor.text = post.author
                textSubreddit.text = post.subreddit
            }

            binding.textPostTitle.text = post.title

            binding.includePostMetrics.setRatio(post.ratio)

            binding.includePostInfo.groupCrosspost.isVisible = false
            binding.includePostInfo.textPostAuthor.apply {
                setTextColor(ContextCompat.getColor(context, post.posterType.color))
            }

            bindText(post)

            bindAwards(post)

            bindFlairs(post)

            when (post.type) {
                PostType.IMAGE -> {
                    bindImage(post) {
                        error(R.drawable.preview_image_fallback)
                        fallback(R.drawable.preview_image_fallback)
                    }
                    binding.imagePost.setOnClickListener { postClickListener.onImageClick(post) }
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
                    binding.imagePost.setOnClickListener { postClickListener.onVideoClick(post) }
                    // The playing PlayerView overlays the preview, so it must carry
                    // the same "open media" tap (otherwise a tap on a playing video
                    // hits the player, not the image beneath).
                    binding.imagePostPlayer.setOnClickListener { postClickListener.onVideoClick(post) }
                }
                else -> {
                    // Ignore
                }
            }

            binding.buttonTypeIndicator.apply {
                when {
                    post.mediaType == MediaType.REDDIT_GALLERY ||
                            post.mediaType == MediaType.IMGUR_ALBUM ||
                            post.mediaType == MediaType.IMGUR_GALLERY -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_gallery)
                    }
                    post.type == PostType.VIDEO -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_play)
                    }
                    post.type == PostType.LINK -> {
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

            // Start the muted in-header preview after the still poster + play
            // badge are settled (the player overlays the poster once it renders).
            startVideoPreview(post)

            when {
                post.crosspost != null -> {
                    binding.includeCrosspost.run {
                        root.isVisible = true
                        root.setOnClickListener { postClickListener.onClick(post.crosspost) }
                        title.text = post.crosspost.title
                        includePostInfo.post = post.crosspost
                        includePostInfo.textPostAuthor.text = post.crosspost.author
                        includePostInfo.textSubreddit.text = post.crosspost.subreddit
                        includePostInfo.groupCrosspost.isVisible = false
                    }
                }

                post.crosspostScrap != null -> {
                    binding.includeCrosspost.run {
                        root.isVisible = true
                        title.text = post.crosspostScrap?.title
                        includePostInfo.textPostAuthor.text = post.crosspostScrap?.author
                        includePostInfo.textSubreddit.text = post.crosspostScrap?.subreddit
                        includePostInfo.textPostDate.isVisible = false
                        includePostInfo.groupCrosspost.isVisible = false
                    }
                }

                else -> binding.includeCrosspost.root.isVisible = false
            }

            binding.includePostMetrics.buttonSave.isChecked = post.saved
        }

        fun update(post: PostEntity) {
            binding.includePostMetrics.post = post
            binding.includePostFlairs.post = post

            binding.includePostMetrics.buttonSave.isChecked = post.saved

            // The post data can change on a payload rebind (e.g. the cached
            // stub is replaced by the full post): re-evaluate the preview so a
            // video that wasn't known yet can start, and one that lost its
            // native rendition stops.
            if (post.type == PostType.VIDEO) {
                startVideoPreview(post)
            } else {
                stopVideoPreview()
            }

            bindText(post)

            bindAwards(post)

            bindFlairs(post)
        }

        private fun bindText(post: PostEntity) {
            binding.textPost.apply {
                if (post.selfRedditText.isNotEmpty()) {
                    visibility = View.VISIBLE
                    setText(post.selfRedditText)
                    setOnLinkClickListener(onLinkClickListener)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindFlairs(post: PostEntity) {
            when {
                post.hasFlairs -> {
                    binding.includePostFlairs.root.visibility = View.VISIBLE
                    binding.includePostFlairs.postFlair.apply {
                        if (!post.flair.isEmpty()) {
                            visibility = View.VISIBLE

                            setFlair(post.flair)
                        } else {
                            visibility = View.GONE
                        }
                    }
                }
                post.isSelf -> {
                    binding.includePostFlairs.root.visibility = View.GONE
                }
                else -> {
                    binding.includePostFlairs.postFlair.visibility = View.GONE
                }
            }
            binding.includePostInfo.postFlair.apply {
                if (!post.authorFlair.isEmpty()) {
                    visibility = View.VISIBLE

                    setFlair(post.authorFlair)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindAwards(post: PostEntity) {
            binding.awards.apply {
                if (post.totalAwards > 0) {
                    visibility = View.VISIBLE
                    setAwards(post.awards)
                } else {
                    visibility = View.GONE
                }
            }
        }

        private fun bindImage(
            post: PostEntity,
            requestBuilder: ImageRequest.Builder.() -> Unit = {}
        ) {
            binding.imagePost.apply {
                visibility = View.VISIBLE
                load(
                    preview,
                    !post.shouldShowPreview(contentPreferences),
                    blurUrl = post.previewBlurUrl,
                    builder = requestBuilder
                )
            }
        }
    }
}
