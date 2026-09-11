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
import com.cosmos.unreddit.util.extension.load
import coil.size.Scale

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

    init {
        orientation = VERTICAL
    }

    fun setText(redditText: RedditText) {
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
        // Inline video inside a comment/post body (a reddit `shreddit-player`).
        // Rendered as a tappable poster + play badge; tapping opens the media
        // viewer which plays the HLS/DASH url. The poster keeps the published
        // aspect ratio when known, else the poster's natural ratio.
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
