package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
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
        val imageView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.comment_body_spacing)
            }
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
            adjustViewBounds = true
            contentDescription = null
            isClickable = true
            isFocusable = true
            setOnClickListener { onLinkClickListener?.onLinkClick(imageBlock.url) }
            setOnLongClickListener {
                onLinkClickListener?.onLinkLongClick(imageBlock.url)
                true
            }
            // v2.5.58: when the HTML published no usable ratio (reddit now writes
            // height="auto"), the box is NOT locked, so Coil must decode at the
            // bitmap's NATURAL aspect ratio (Scale.FIT) instead of squishing it to
            // the current (wrong) box (Scale.FILL). That is what clipped tall images
            // vertically and needed a scroll-away-and-back to fix. With a locked box
            // (ratio != null) FILL is correct because the box already matches.
            load(
                imageBlock.url,
                blur = false,
                scale = if (ratio == null) Scale.FIT else Scale.FILL
            )
        }
        addView(imageView)
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
        val poster = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = null
            if (ratio != null) {
                lockAspectOnLayout(this, ratio)
            }
            if (!videoBlock.poster.isNullOrBlank()) {
                load(videoBlock.poster, blur = false, scale = if (ratio == null) Scale.FIT else Scale.FILL)
            }
        }
        val badge = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.video_play_badge),
                context.resources.getDimensionPixelSize(R.dimen.video_play_badge),
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_play)
            contentDescription = context.getString(R.string.cd_play_video)
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
