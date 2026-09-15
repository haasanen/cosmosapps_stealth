package com.cosmos.unreddit.ui.postlist

import android.view.View
import android.view.ViewTreeObserver
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
import com.cosmos.unreddit.util.FeedPreviewPlayerPool
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio
import com.google.android.exoplayer2.Player
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
                !postEntity.shouldShowPreview(contentPreferences)
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
         * In-feed muted preview playback (2.5.74 request: "preview videos should
         * start playing automatically when they are visible … without sound, sound
         * only when opened to fullscreen / the comments").
         *
         * Native Reddit videos (v.redd.it MP4/HLS) loop muted in the cell while at
         * least half of the cell is on screen; tapping still opens the MediaViewer,
         * which plays with the user's sound settings (the in-cell player is a
         * different, always-muted instance). External videos (redgifs/YouTube/
         * imgur/gfycat/streamable) keep the still poster + play badge: their
         * playable rendition needs the MediaViewer's per-site pipeline (redgifs
         * resolution lookup, audio track merging, request-property signing), so
         * auto-playing them in a cell is not a plain URL swap.
         */
        private val pool = FeedPreviewPlayerPool.get()

        private val token = object : FeedPreviewPlayerPool.Token {
            override fun playerAttached(player: Player) {
                binding.imagePostPreviewPlayer.player = player
            }

            override fun playerDetached() {
                // The pool evicted (or released) this token's player. Forget it so a
                // later updatePlayback() re-acquires instead of touching a released
                // instance; the PlayerView is cleared either way.
                this@VideoPostViewHolder.player = null
                binding.imagePostPreviewPlayer.player = null
                binding.imagePostPreviewPlayer.visibility = View.GONE
            }
        }

        /** The in-cell player bound to the current post, or null. */
        private var player: Player? = null

        /** The post this cell is bound to (null before bind / after detach). */
        private var boundPost: PostEntity? = null

        /** True while autoplay applies to the bound post. */
        private var autoplay = false

        /** Re-checks playback whenever the view tree scrolls (cell enters/leaves). */
        private val scrollChecker = ViewTreeObserver.OnScrollChangedListener {
            if (autoplay) updatePlayback()
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
            // Version-proof attach/detach (RecyclerView 1.2.1 has no ViewHolder
            // attach/detach callbacks): stop muted playback when the cell leaves
            // the window (recycled off-screen, list backgrounded) so no stream
            // keeps running unseen; re-evaluate when it comes back.
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!autoplay) return
                    if (isSufficientlyVisible()) {
                        updatePlayback()
                    } else {
                        // Not laid out yet (height 0): re-check after the next layout.
                        v.post { if (autoplay) updatePlayback() }
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    stopPlayback()
                    boundPost = null
                }
            })
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_video_fallback)
                fallback(R.drawable.preview_video_fallback)
            }

            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_play)
            }

            // A rebind re-evaluates: stop whatever the previous post was playing,
            // then decide whether this post autoplays at all. When the preview is
            // hidden by the NSFW/spoiler setting, playing would reveal the content
            // — so the cell keeps its blurred still and never plays.
            stopPlayback()
            boundPost = postEntity
            autoplay = canAutoplay(
                postEntity,
                contentPreferences.autoplayPreviews,
                postEntity.shouldShowPreview(contentPreferences)
            )
            if (autoplay) {
                itemView.viewTreeObserver.addOnScrollChangedListener(scrollChecker)
                updatePlayback()
            }
        }

        /** At least half of the cell is inside the list's clip bounds. */
        private fun isSufficientlyVisible(): Boolean {
            if (itemView.height <= 0) return false
            val rv = itemView.parent as? RecyclerView ?: return false
            val loc = IntArray(2)
            val rvLoc = IntArray(2)
            itemView.getLocationInWindow(loc)
            rv.getLocationInWindow(rvLoc)
            val top = loc[1] - rvLoc[1]
            val bottom = top + itemView.height
            val visible = (bottom.coerceAtMost(rv.height) - top.coerceAtLeast(0)).coerceAtLeast(0)
            return visible >= itemView.height * 0.5f
        }

        companion object {
            /**
             * Pure autoplay-eligibility check for a feed video cell — the only state
             * read is [post]'s media type/url and the two booleans, so it is unit
             * tested directly (no view/player). A preview plays muted in the cell
             * only when the user enabled it, the NSFW/spoiler settings allow the
             * preview to show, and it is a native Reddit video (a plain v.redd.it
             * MP4/HLS a player can stream). External videos (redgifs/YouTube/imgur/
             * gfycat/streamable) need the MediaViewer's per-site pipeline, so they
             * keep the still poster + play badge.
             */
            internal fun canAutoplay(
                post: PostEntity,
                autoplayEnabled: Boolean,
                previewAllowed: Boolean
            ): Boolean {
                if (!autoplayEnabled || !previewAllowed) return false
                if (post.mediaType != MediaType.REDDIT_VIDEO &&
                    post.mediaType != MediaType.REDDIT_GIF
                ) return false
                val host = post.mediaUrl.toHttpUrlOrNull()?.host ?: return false
                return host == "v.redd.it"
            }
        }

        private fun updatePlayback() {
            val post = boundPost ?: return
            if (isSufficientlyVisible()) {
                if (player == null) {
                    player = pool.acquire(itemView.context, post.mediaUrl, token)
                    binding.imagePostPreviewPlayer.visibility = View.VISIBLE
                    binding.buttonTypeIndicator.visibility = View.GONE
                } else {
                    player?.play()
                }
            } else {
                player?.pause()
            }
        }

        private fun stopPlayback() {
            if (player != null) {
                pool.release(token)
                player = null
            }
            if (autoplay) {
                itemView.viewTreeObserver.removeOnScrollChangedListener(scrollChecker)
            }
            autoplay = false
            binding.imagePostPreviewPlayer.player = null
            binding.imagePostPreviewPlayer.visibility = View.GONE
            binding.buttonTypeIndicator.visibility = View.VISIBLE
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
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_link_fallback)
                fallback(R.drawable.preview_link_fallback)
            }
        }
    }

    class PollPostViewHolder() {

    }
}
