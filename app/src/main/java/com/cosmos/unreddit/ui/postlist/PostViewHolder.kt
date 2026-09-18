package com.cosmos.unreddit.ui.postlist

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.IncludePostFlairsBinding
import com.cosmos.unreddit.databinding.IncludePostInfoBinding
import com.cosmos.unreddit.databinding.IncludePostMetricsBinding
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.widget.AwardView
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.InAppVideoResolver
import com.cosmos.unreddit.util.VideoPreviewController
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio

abstract class PostViewHolder(
    itemView: View,
    private val postInfoBinding: IncludePostInfoBinding,
    private val postMetricsBinding: IncludePostMetricsBinding,
    private val postFlairsBinding: IncludePostFlairsBinding,
    listener: PostListAdapter.Listener
) : RecyclerView.ViewHolder(itemView) {

    private val title = itemView.findViewById<TextView>(R.id.text_post_title)
    private val awards = itemView.findViewById<AwardView>(R.id.awards)

    /** The post timestamp, exposed so list adapters can decorate it (e.g. a "(cached)" suffix). */
    val postInfoTextPostDate: TextView? = postInfoBinding.root.findViewById(R.id.text_post_date)

    init {
        itemView.apply {
            setOnClickListener {
                listener.onClick(bindingAdapterPosition)
            }
            setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                return@setOnLongClickListener true
            }
        }

        postMetricsBinding.buttonMore.setOnClickListener {
            listener.onMenuClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonSave.setOnClickListener {
            listener.onSaveClick(bindingAdapterPosition)
        }
    }

    open fun bind(
        postEntity: PostEntity,
        contentPreferences: ContentPreferences
    ) {
        postMetricsBinding.post = postEntity
        postFlairsBinding.post = postEntity

        postInfoBinding.run {
            this.post = postEntity
            textPostAuthor.text = postEntity.author
            textSubreddit.text = postEntity.subreddit
        }

        title.apply {
            text = postEntity.title
            setTextColor(ContextCompat.getColor(context, postEntity.textColor))
        }

        postMetricsBinding.setRatio(postEntity.ratio)

        awards.apply {
            if (postEntity.awards.isNotEmpty()) {
                visibility = View.VISIBLE
                setAwards(postEntity.awards, postEntity.totalAwards)
            } else {
                visibility = View.GONE
            }
        }

        postInfoBinding.textPostAuthor.apply {
            setTextColor(ContextCompat.getColor(context, postEntity.posterType.color))
        }

        when {
            postEntity.hasFlairs -> {
                postFlairsBinding.root.visibility = View.VISIBLE
                postFlairsBinding.postFlair.apply {
                    if (!postEntity.flair.isEmpty()) {
                        visibility = View.VISIBLE

                        setFlair(postEntity.flair)
                    } else {
                        visibility = View.GONE
                    }
                }
            }

            postEntity.isSelf -> {
                postFlairsBinding.root.visibility = View.GONE
            }

            else -> {
                postFlairsBinding.postFlair.visibility = View.GONE
            }
        }

        when {
            postEntity.crosspost != null -> {
                postInfoBinding.groupCrosspost.isVisible = true
                postInfoBinding.textCrosspostSubreddit.text = postEntity.crosspost.subreddit
                postInfoBinding.textCrosspostAuthor.text = postEntity.crosspost.author
            }

            postEntity.crosspostScrap != null -> {
                postInfoBinding.groupCrosspost.isVisible = true
                postInfoBinding.textCrosspostSubreddit.text = postEntity.crosspostScrap?.subreddit
                postInfoBinding.textCrosspostAuthor.text = postEntity.crosspostScrap?.author
            }

            else -> postInfoBinding.groupCrosspost.isVisible = false
        }

        postMetricsBinding.buttonSave.isChecked = postEntity.saved
    }

    open fun update(post: PostEntity) {
        title.setTextColor(ContextCompat.getColor(title.context, post.textColor))
        postMetricsBinding.buttonSave.isChecked = post.saved
    }

    class ImagePostViewHolder(
        private val binding: ItemPostImageBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences),
                blurUrl = postEntity.previewBlurUrl
            ) {
                error(R.drawable.preview_image_fallback)
                fallback(R.drawable.preview_image_fallback)
            }

            binding.buttonTypeIndicator.apply {
                when (postEntity.mediaType) {
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
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        /**
         * In-feed muted preview playback (2.5.74 request; 2.5.86 "play everything
         * the app plays in-app"). The cell decides VISIBILITY (>= half on screen);
         * the shared [VideoPreviewController] does the rest — resolving a playable
         * URL for ANY in-app video type (reddit native, imgur, gfycat, redgifs,
         * streamable, generic; site APIs resolved on demand), and the frost-baked
         * poster swap is handled in [bind] via [InAppVideoResolver.sharpPreview].
         * Tapping still opens the MediaViewer, which plays with the user's sound
         * settings.
         */
        private lateinit var controller: VideoPreviewController

        /**
         * The feed's RecyclerView enclosing this cell (walk the parent chain —
         * intermediate containers are possible depending on the list wrapper).
         */
        private fun findList(): RecyclerView? {
            var parent: android.view.ViewParent? = itemView.parent
            while (parent != null) {
                if (parent is RecyclerView) return parent
                parent = parent.parent
            }
            return null
        }

        /**
         * Scroll detection on the list itself. A ViewTreeObserver scroll listener
         * on the ITEM would never fire: RecyclerView scrolls by translating its
         * children, not by scrolling them, so only the list's own scroll events
         * reach us.
         */
        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                controller.onVisibleChanged()
            }

            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) controller.onVisibleChanged()
            }
        }

        /** The list this cell is attached to (listener attached/detached with it). */
        private var list: RecyclerView? = null

        /**
         * Fires on fresh layouts (a cold feed fill never scrolls, so without this
         * the first on-screen video would wait for the first scroll to start).
         */
        private val layoutTrigger = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height > 0) controller.onVisibleChanged()
        }

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
            // The playing PlayerView overlays the preview image, so it must carry
            // the same "open media" tap (otherwise a tap on a playing video hits
            // the player, not the image beneath).
            binding.imagePostPreviewPlayer.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
            controller = VideoPreviewController(
                context = itemView.context,
                playerView = binding.imagePostPreviewPlayer,
                playBadge = binding.buttonTypeIndicator,
                visibilityProvider = { isSufficientlyVisible() },
                // Feed video cells always show the play badge when not playing.
                restoreStillBadge = {
                    binding.buttonTypeIndicator.apply {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_play)
                    }
                },
                sharpPosterCallback = { url ->
                    binding.imagePostPreview.load(url, false) {
                        fallback(R.drawable.preview_video_fallback)
                    }
                }
            )
            itemView.addOnLayoutChangeListener(layoutTrigger)
            // Attach/detach owns the list scroll listener (bind() can run while the
            // cell is still detached), and stops playback when the cell leaves the
            // window (recycled off-screen, list backgrounded) so no stream keeps
            // running unseen.
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    list = findList()
                    list?.addOnScrollListener(scrollListener)
                    controller.onVisibleChanged()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    list?.removeOnScrollListener(scrollListener)
                    list = null
                    controller.release()
                }
            })
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            val previewAllowed = postEntity.shouldShowPreview(contentPreferences)

            binding.imagePostPreview.load(
                postEntity.preview,
                !previewAllowed,
                blurUrl = postEntity.previewBlurUrl
            ) {
                error(R.drawable.preview_video_fallback)
                fallback(R.drawable.preview_video_fallback)
            }

            // Frost-baked CDN poster (external video embeds): reddit's frosted
            // ?blur=40 file has no sharp twin on reddit's CDNs, so the "Show
            // NSFW/spoiler preview" toggle could never un-blur it. When the
            // preview is allowed, swap in the site's own sharp still (redgifs
            // exposes one via its API; others keep the frosted poster).
            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_play)
            }

            // A rebind re-evaluates: the controller stops whatever the previous
            // post was playing, then decides whether this post autoplays at all.
            // When the preview is hidden by the NSFW/spoiler setting, playing
            // would reveal the content — so the cell keeps its blurred still and
            // never plays. (bind must run before maybeLoadSharpPoster: the swap
            // keys off the bound post.)
            controller.bind(postEntity, contentPreferences)
            controller.maybeLoadSharpPoster(contentPreferences)
        }

        /** At least half of the cell is inside the list's visible bounds. */
        private fun isSufficientlyVisible(): Boolean {
            if (itemView.height <= 0) return false
            val rv = findList() ?: return false
            val loc = IntArray(2)
            val rvLoc = IntArray(2)
            itemView.getLocationInWindow(loc)
            rv.getLocationInWindow(rvLoc)
            val top = loc[1] - rvLoc[1]
            return visibleFraction(top, itemView.height, rv.height) * 2 >= itemView.height
        }

        companion object {
            /**
             * Pure autoplay-eligibility check for a feed video cell — the only
             * state read is [post]'s media type and the two booleans, so it is
             * unit tested directly (no view/player). 2.5.86: EVERY video the app
             * plays in its own player autoplays (reddit native, imgur, gfycat,
             * redgifs, streamable, generic video); only site-opening links and
             * non-videos don't. Gated by the setting and the preview allowance.
             */
            internal fun canAutoplay(
                post: PostEntity,
                autoplayEnabled: Boolean,
                previewAllowed: Boolean
            ): Boolean = InAppVideoResolver.canAutoplay(
                post.mediaType,
                autoplayEnabled,
                previewAllowed
            )

            /**
             * Pure visibility geometry (unit tested): the number of pixels of a
             * cell [height] tall positioned at [top] (in list coordinates) that
             * fall inside a list whose visible height is [listHeight].
             */
            internal fun visibleFraction(top: Int, height: Int, listHeight: Int): Int {
                if (height <= 0 || listHeight <= 0) return 0
                val bottom = top + height
                return (bottom.coerceAtMost(listHeight) - top.coerceAtLeast(0)).coerceAtLeast(0)
            }
        }
    }

    class TextPostViewHolder(
        private val binding: ItemPostTextBinding,
        listener: PostListAdapter.Listener,
        clickableMovementMethod: ClickableMovementMethod
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.textPostSelf.movementMethod = clickableMovementMethod
            binding.textPostSelf.setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                true
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            val previewText = postEntity.previewText

            binding.textPostSelf.apply {
                if (postEntity.shouldShowPreview(contentPreferences) && previewText != null) {
                    binding.textPostSelfCard.visibility = View.VISIBLE
                    setText(previewText, false)
                    setTextColor(ContextCompat.getColor(context, postEntity.textColor))
                } else {
                    binding.textPostSelfCard.visibility = View.GONE
                }
            }
        }

        override fun update(post: PostEntity) {
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
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostLinkPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            binding.imagePostLinkPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences),
                blurUrl = postEntity.previewBlurUrl
            ) {
                error(R.drawable.preview_link_fallback)
                fallback(R.drawable.preview_link_fallback)
            }
        }
    }

    class PollPostViewHolder() {

    }
}
