package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import androidx.annotation.ColorInt
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.children
import com.cosmos.unreddit.data.model.Block.*
import com.cosmos.unreddit.data.model.HtmlBlock
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.R
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.load
import coil.size.Scale
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

class RedditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr), ClickableMovementMethod.OnClickListener {

    interface OnLinkClickListener {
        fun onLinkClick(link: String)

        fun onLinkLongClick(link: String)
    }

    private val childParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private val clickableMovementMethod = ClickableMovementMethod(this)

    private var onLinkClickListener: OnLinkClickListener? = null

    // v2.5.60: inline GIF players (reddit `shreddit-player gif`). They must be
    // released when the view is re-bound or recycled, otherwise every scrolled
    // comment keeps a live ExoPlayer around.
    private val inlinePlayers = mutableListOf<SimpleExoPlayer>()

    init {
        orientation = VERTICAL
    }

    fun setText(redditText: RedditText) {
        releaseInlinePlayers()
        removeAllViews()

        val blocks = redditText.blocks
        for (block in blocks) {
            when (block.type) {
                HtmlBlock.BlockType.TEXT -> {
                    addText(block.block as TextBlock)
                }
                HtmlBlock.BlockType.CODE -> {
                    addCode(block.block as TextBlock)
                }
                HtmlBlock.BlockType.TABLE -> {
                    addTable(block.block as TableBlock)
                }
                HtmlBlock.BlockType.IMAGE -> {
                    addImage(block.block as ImageBlock)
                }
                HtmlBlock.BlockType.VIDEO -> {
                    addVideo(block.block as VideoBlock)
                }
            }
        }
    }

    fun setPreviewText(textBlock: TextBlock) {
        releaseInlinePlayers()
        removeAllViews()
        addText(textBlock)
    }

    fun setTextColor(@ColorInt color: Int) {
        for (child in children) {
            if (child is RedditTextView) {
                child.setTextColor(color)
            }
        }
    }

    private fun addText(textBlock: TextBlock) {
        val redditTextView = RedditTextView(context).apply {
            layoutParams = childParams
            text = textBlock.text
            movementMethod = this@RedditView.clickableMovementMethod
        }
        addView(redditTextView)
    }

    private fun addImage(imageBlock: ImageBlock) {
        // Inline image inside a comment/post body. v2.5.54: full width, aspect
        // ratio kept — the HTML width/height attrs (CSS px) give the ratio when
        // present; without them the ImageView measures the bitmap and keeps its
        // natural ratio (adjustViewBounds). No width cap any more.
        val ratio = if (imageBlock.width > 0 && imageBlock.height > 0) {
            imageBlock.height.toFloat() / imageBlock.width
        } else {
            null
        }
        // v2.5.68: the image lives in a box that carries a loading spinner
        // while the media is being downloaded — previously the slot rendered
        // as dead empty space until the bitmap arrived (and the old
        // placeholder was an unmarked colorSurface solid: invisible on the
        // card).
        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.comment_body_spacing)
            }
        }
        val onMediaReady = attachLoadingSpinner(frame)
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // Full width, height locked to the published aspect ratio when the
            // HTML carried one (a 16:9 gifv frame must not render as 1:1).
            if (ratio != null) {
                addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        val w = right - left
                        if (w > 0) {
                            val newH = (w * ratio).toInt().coerceAtLeast(1)
                            if (newH != bottom - top) {
                                layoutParams = layoutParams.apply { height = newH }
                            }
                        }
                    }
                })
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // v2.5.63: adjustViewBounds must NOT be active when the box is locked
            // to the published ratio. It resizes the box to the *decoded bitmap's*
            // intrinsic ratio, which on a first load can override the locked ratio
            // (Coil decodes at the transient box size before the layout listener
            // settles), leaving the image wrong until a rebind. Only the ratio-less
            // path needs it (bitmap ratio is the source of truth there).
            adjustViewBounds = ratio == null
            // v2.5.68: without a known ratio the box measures 0x0 while the
            // bitmap is in flight (a WRAP_CONTENT ImageView with no drawable
            // yet), so the spinner would have nowhere to sit. A floor keeps
            // the slot visible until the media lands — cleared in the load
            // listener so the box snaps to the bitmap's natural size.
            if (ratio == null) {
                setMinimumHeight(
                    context.resources.getDimensionPixelSize(R.dimen.media_loading_min_height)
                )
            }
            contentDescription = null
            isClickable = true
            isFocusable = true
            setOnClickListener { onLinkClickListener?.onLinkClick(imageBlock.url) }
            setOnLongClickListener {
                onLinkClickListener?.onLinkLongClick(imageBlock.url)
                true
            }
            // v2.5.63: the locked box is authoritative for the height (the layout
            // listener above sets it to width x ratio). Coil must therefore FIT,
            // never FILL. FILL (centerCrop) crops the image to the box — correct
            // only if the box already matches, but on a first load the box can be
            // transiently off before the listener settles, and FILL then crops the
            // image to that wrong box (the 2026-09-12 "preview crops the actual
            // image" report: labels clipped on both sides). FIT never crops: a
            // transiently-wrong box only letterboxes briefly, then the listener
            // corrects the box and the image refits. The ratio-less path also FITs
            // (bitmap's natural ratio is the source of truth there).
            load(
                imageBlock.url,
                blur = false,
                scale = Scale.FIT
            ) {
                // v2.5.68: drop the spinner as soon as the media — or its
                // failure — lands.
                listener(
                    onSuccess = { _, _ ->
                        setMinimumHeight(0)
                        onMediaReady()
                    },
                    onError = { _, _ -> onMediaReady() },
                    onCancel = { onMediaReady() }
                )
            }
            // TEMP (v2.5.63): confirm the locked ratio reaches the render. The
            // on-device first-load race that survives (if any) shows up here.
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "inline image w=${imageBlock.width} h=${imageBlock.height} " +
                    "ratio=${if (ratio != null) String.format(java.util.Locale.US, "%.4f", ratio) else "null"} " +
                    "lock=${ratio != null} …${imageBlock.url.takeLast(40)}"
            )
        }
        frame.addView(imageView)
        addView(frame)
    }

    private fun addVideo(videoBlock: VideoBlock) {
        if (videoBlock.isGif) {
            addGif(videoBlock)
        } else {
            addVideoPoster(videoBlock)
        }
    }

    /**
     * v2.5.60: reddit GIF "videos" (…gif?format=mp4) autoplay + loop inline, muted,
     * no controls — matching what reddit's own page does. The box is locked to the
     * published aspect ratio so a tall or wide gif keeps its shape from the first
     * frame. The player plays only while attached to the window (a RecyclerView row
     * detaches when scrolled off-screen or the activity stops), and is released when
     * the RedditView is re-bound (setText / setPreviewText). Tapping opens the media
     * viewer for the full-screen play.
     */
    private fun addGif(videoBlock: VideoBlock) {
        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.comment_body_spacing)
            }
        }
        val playerView = PlayerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            useController = false
            // 2.18.1: the resize constants live on AspectRatioFrameLayout, not PlayerView.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isClickable = true
            contentDescription = context.getString(R.string.cd_play_video)
            setOnClickListener { onLinkClickListener?.onLinkClick(videoBlock.url) }
            setOnLongClickListener {
                onLinkClickListener?.onLinkLongClick(videoBlock.url)
                true
            }
        }
        // Lock the box to the published ratio so the comment keeps its layout stable
        // from the first frame (RESIZE_MODE_FIT would only know the video's ratio once
        // the first format has loaded).
        if (videoBlock.width > 0 && videoBlock.height > 0) {
            lockAspectOnLayout(frame, videoBlock.height.toFloat() / videoBlock.width)
        }
        val player = SimpleExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setUserAgent(LinkUtil.USER_AGENT)
                )
            )
            .build()
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0F // muted, like reddit's inline autoplay
        player.setMediaItem(MediaItem.fromUri(videoBlock.url))
        player.prepare()
        playerView.player = player

        // Play only while the view is attached to the window; pause otherwise. In a
        // RecyclerView a row detaches when it scrolls off-screen (and is detached again
        // when the activity stops), so this both stops off-screen gifs from decoding and
        // stops them when the app is backgrounded. Players are released when the
        // RedditView is re-bound (setText / setPreviewText) — ListAdapter always re-binds
        // a recycled row before reuse, so live players are bounded to visible rows.
        player.setPlayWhenReady(false)
        playerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                player.setPlayWhenReady(true)
            }

            override fun onViewDetachedFromWindow(v: View) {
                player.setPlayWhenReady(false)
            }
        })

        inlinePlayers.add(player)
        frame.addView(playerView)
        // v2.5.68: spinner until the first frame is decoded — the gif "video"
        // is an mp4, and until the first format is known the box is just its
        // (black) player background. The spinner goes on top: the PlayerView
        // paints an opaque surface once playing, which would hide a
        // below-order spinner.
        val onMediaReady = attachLoadingSpinner(frame)
        player.addListener(object : Player.Listener {
            // 2.18.1: the first actually-drawn frame — the spinner goes when a
            // pixel appears, not when a format is merely known.
            override fun onRenderedFirstFrame() {
                onMediaReady()
            }

            override fun onPlayerError(error: PlaybackException) {
                onMediaReady()
            }
        })
        addView(frame)
    }

    private fun addVideoPoster(videoBlock: VideoBlock) {
        // Non-GIF inline video: tappable poster + play badge; tapping opens the media
        // viewer which plays the HLS/DASH url. The poster keeps the published aspect
        // ratio when known, else the poster's natural ratio.
        val ratio = if (videoBlock.width > 0 && videoBlock.height > 0) {
            videoBlock.height.toFloat() / videoBlock.width
        } else {
            null
        }
        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.comment_body_spacing)
            }
        }
        // v2.5.68: spinner until the poster lands (see addImage).
        val onMediaReady = attachLoadingSpinner(frame)
        // v2.5.68: the play badge waits for the poster — a floating play icon
        // over a bare spinner reads as broken.
        val badge = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.video_play_badge),
                context.resources.getDimensionPixelSize(R.dimen.video_play_badge),
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_play)
            contentDescription = context.getString(R.string.cd_play_video)
            visibility = View.GONE
        }
        val poster = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            // v2.5.63: same first-load race as addImage — adjustViewBounds resizes
            // the box to the decoded bitmap's ratio, overriding the locked ratio
            // until a rebind. Only the ratio-less path needs it.
            adjustViewBounds = ratio == null
            contentDescription = null
            if (ratio != null) {
                lockAspectOnLayout(this, ratio)
            }
            // v2.5.68: a ratio-less poster measures 0x0 while in flight; a
            // floor keeps the slot visible until the poster lands (cleared in
            // the load listener).
            if (ratio == null) {
                setMinimumHeight(
                    context.resources.getDimensionPixelSize(R.dimen.media_loading_min_height)
                )
            }
            if (!videoBlock.poster.isNullOrBlank()) {
                // v2.5.63: FIT, never FILL — a transiently-wrong box must letterbox,
                // not crop the poster (see addImage).
                load(videoBlock.poster, blur = false, scale = Scale.FIT) {
                    // v2.5.68: spinner until the poster lands (see addImage).
                    listener(
                        onSuccess = { _, _ ->
                            setMinimumHeight(0)
                            badge.visibility = View.VISIBLE
                            onMediaReady()
                        },
                        onError = { _, _ -> onMediaReady() },
                        onCancel = { onMediaReady() }
                    )
                }
            } else {
                // v2.5.68: no poster at all — nothing will ever land in this
                // box, so no spinner either.
                onMediaReady()
            }
        }
        frame.addView(poster)
        frame.addView(badge)
        frame.isClickable = true
        frame.isFocusable = true
        frame.setOnClickListener { onLinkClickListener?.onLinkClick(videoBlock.url) }
        frame.setOnLongClickListener {
            onLinkClickListener?.onLinkLongClick(videoBlock.url)
            true
        }
        addView(frame)
    }

    /**
     * v2.5.68: adds a small indeterminate spinner to [frame], centered, and
     * returns a one-shot callback that hides it. Inline media used to render
     * as dead empty space while the download was in flight (and the Coil
     * placeholder was an unmarked colorSurface solid — invisible on the card);
     * the spinner says "media is coming to this slot" until the media, or its
     * failure, lands. Idempotent: cached-hit loads may report success before
     * the first frame is even visible.
     */
    private fun attachLoadingSpinner(frame: FrameLayout): () -> Unit {
        val spinner = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.media_loading_spinner),
                context.resources.getDimensionPixelSize(R.dimen.media_loading_spinner),
                Gravity.CENTER
            )
            isIndeterminate = true
            contentDescription = null
        }
        frame.addView(spinner)
        return {
            if (spinner.visibility == View.VISIBLE) {
                spinner.visibility = View.GONE
            }
        }
    }

    /**
     * Locks [view]'s height to width x [ratio] once it is laid out, so an inline
     * media box keeps its published aspect ratio even when the HTML gave no usable
     * ratio. Shared by images and video posters.
     */
    private fun lockAspectOnLayout(view: View, ratio: Float) {
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                val w = right - left
                if (w > 0) {
                    val newH = (w * ratio).toInt().coerceAtLeast(1)
                    if (newH != bottom - top) {
                        v.layoutParams = v.layoutParams.apply { height = newH }
                    }
                }
            }
        })
    }

    /**
     * v2.5.60: release every inline GIF player. Called on re-bind (setText /
     * setPreviewText) so a RedditView that is re-bound with different content does
     * not keep the old gifs' ExoPlayer instances.
     */
    fun releaseInlinePlayers() {
        for (player in inlinePlayers) {
            player.release()
        }
        inlinePlayers.clear()
    }


    private fun addCode(codeBlock: TextBlock) {
        val redditTextView = RedditTextView(context).apply {
            layoutParams = childParams
            text = codeBlock.text
        }
        addView(wrapWithScrollView(redditTextView))
    }

    private fun addTable(tableBlock: TableBlock) {
        addView(wrapWithScrollView(tableBlock.getTableLayout(context, clickableMovementMethod)))
    }

    private fun wrapWithScrollView(view: View): View {
        return HorizontalScrollView(context).apply {
            layoutParams = childParams
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(view)
        }
    }

    fun setOnLinkClickListener(onLinkClickListener: OnLinkClickListener?) {
        this.onLinkClickListener = onLinkClickListener
    }

    override fun onLinkClick(link: String) {
        onLinkClickListener?.onLinkClick(link)
    }

    override fun onLinkLongClick(link: String) {
        onLinkClickListener?.onLinkLongClick(link)
    }

    override fun onClick() {
        performClick()
    }

    override fun onLongClick() {
        performLongClick()
    }
}
