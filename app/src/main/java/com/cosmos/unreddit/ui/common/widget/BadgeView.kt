package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.cosmos.stealth.sdk.data.model.api.BadgeData
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Badge

class BadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    private val childParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)

    private val flairImageSize: Int by lazy {
        context.resources.getDimension(R.dimen.flair_image_size).toInt()
    }

    init {
        orientation = HORIZONTAL
    }

    fun setBadge(badge: Badge) {
        removeAllViews()

        for (data in badge.badgeDataList) {
            when (data.type) {
                BadgeData.Type.text -> {
                    val textView = TextView(
                        context,
                        null,
                        0,
                        R.style.TextAppearancePostFlair
                    ).apply {
                        layoutParams = childParams
                        setSingleLine()
                        isHorizontalFadingEdgeEnabled = true
                        text = data.text
                    }
                    addView(textView)
                }
                BadgeData.Type.image -> {
                    val imageView = ImageView(context).apply {
                        layoutParams = LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            flairImageSize
                        )
                    }
                    imageView.load(data.url) {
                        crossfade(true)
                        scale(Scale.FIT)
                        precision(Precision.AUTOMATIC)
                    }
                    addView(imageView)
                }
            }
        }
    }
}
