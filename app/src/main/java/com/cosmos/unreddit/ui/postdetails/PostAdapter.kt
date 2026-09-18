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
import com.cosmos.unreddit.util.VideoPreviewController
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio

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
         * In-header muted preview playback (2.5.74; 2.5.86 "play everything the
         * app plays in-app"). The detail screen used to be a static tap-to-play
         * preview — the one place the "Play video previews" setting visibly did
         * nothing (2026-09-17 report). Now EVERY video the app plays in its own
         * player (reddit native, imgur, gfycat, redgifs, streamable, generic)
         * loops muted in the header when the setting is on and the preview is
         * allowed to show; tapping still opens the full-screen MediaViewer with
         * the user's sound settings. The shared [VideoPreviewController] resolves
         * a playable URL (site APIs on demand) and owns the player.
         *
         * The header is the first item of the detail RecyclerView (header +
         * resource-state + comments), so it scrolls away with the comments —
         * the surface counts as visible only while >= half of it is inside the
         * list (same rule as the feed), so the stream stops when the user
         * scrolls to the comments.
         */
        private lateinit var controller: VideoPreviewController

        private fun findList(): RecyclerView? {
            var parent: android.view.ViewParent? = itemView.parent
            while (parent != null) {
                if (parent is RecyclerView) return parent
                parent = parent.parent
            }
            return null
        }

        /** At least half of the header is inside the list's visible bounds. */
        private fun isSufficientlyVisible(): Boolean {
            if (itemView.height <= 0) return false
            val rv = findList() ?: return false
            val loc = IntArray(2)
            val rvLoc = IntArray(2)
            itemView.getLocationInWindow(loc)
            rv.getLocationInWindow(rvLoc)
            val top = loc[1] - rvLoc[1]
            return PostViewHolder.VideoPostViewHolder.visibleFraction(top, itemView.height, rv.height) * 2 >= itemView.height
        }

        init {
            controller = VideoPreviewController(
                context = binding.root.context,
                playerView = binding.imagePostPlayer,
                playBadge = binding.buttonTypeIndicator,
                visibilityProvider = { isSufficientlyVisible() },
                sharpPosterCallback = { url ->
                    // Frost-baked external poster (redgifs): swap in the site's
                    // sharp still when the preview is allowed to show. Defensive
                    // binding check: a late dispatch after onDestroyView must
                    // never dereference a dead binding.
                    if (binding.root.isAttachedToWindow &&
                        post != null && post?.frostBakedPreview == true
                    ) {
                        binding.imagePost.load(url, false) {
                            error(R.drawable.preview_video_fallback)
                            fallback(R.drawable.preview_video_fallback)
                        }
                    }
                }
            )
            // Re-evaluate on scroll (the header scrolls away with the comments —
            // the stream must stop when it's mostly off-screen) and stop on
            // detach (view destroyed / list backgrounded).
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    findList()?.addOnScrollListener(scrollListener)
                    controller.onVisibleChanged()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    findList()?.removeOnScrollListener(scrollListener)
                    controller.release()
                }
            })
        }

        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (itemView.isAttachedToWindow) controller.onVisibleChanged()
            }

            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE &&
                    itemView.isAttachedToWindow
                ) controller.onVisibleChanged()
            }
        }

        fun bind(post: PostEntity) {
            // Stop any previous playback; the controller re-decides eligibility
            // (no-op playback-wise when this isn't an in-app video).
            controller.bind(post, contentPreferences)

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
            // badge are settled (the player overlays the poster once it renders),
            // and swap a frost-baked external poster for the site's sharp still
            // when the preview is allowed to show.
            controller.maybeLoadSharpPoster(contentPreferences)

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
            // rendition stops.
            controller.bind(post, contentPreferences)
            controller.maybeLoadSharpPoster(contentPreferences)

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
