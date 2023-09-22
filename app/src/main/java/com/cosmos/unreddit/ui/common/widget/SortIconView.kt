package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.cosmos.stealth.sdk.data.model.api.Order
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Sorting

class SortIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var icon: ImageView
    private var text: TextView

    private var sortType: SortType = SortType.GENERAL

    private val popInAnimation by lazy { AnimationUtils.loadAnimation(context, R.anim.pop_in) }
    private val popOutAnimation by lazy { AnimationUtils.loadAnimation(context, R.anim.pop_out) }

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.SortIconView,
            0, 0
        ).apply {
            try {
                val sortTypeValue = getInteger(
                    R.styleable.SortIconView_sortType,
                    SortType.GENERAL.value
                )
                sortType = SortType.fromValue(sortTypeValue)
            } finally {
                recycle()
            }
        }

        inflate(context, R.layout.view_sort_icon, this)

        icon = findViewById(R.id.icon)
        text = findViewById(R.id.text)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        clipToPadding = false
    }

    fun setFiltering(filtering: Filtering) {
        with(icon) {
            visibility = getIconVisibility(filtering)

            if (isVisible) {
                setImageResource(getIconResDrawable(filtering))
                startAnimation(popInAnimation)
            } else {
                startAnimation(popOutAnimation)
            }
        }

        with(text) {
            val showOutAnimation = isVisible

            visibility = getTextVisibility(filtering)

            text = when (filtering.time) {
                Time.hour -> context.getString(R.string.sort_time_hour_short)
                Time.day -> context.getString(R.string.sort_time_day_short)
                Time.week -> context.getString(R.string.sort_time_week_short)
                Time.month -> context.getString(R.string.sort_time_month_short)
                Time.year -> context.getString(R.string.sort_time_year_short)
                Time.all -> context.getString(R.string.sort_time_all_short)
                null -> {
                    if (showOutAnimation) startAnimation(popOutAnimation)
                    return@with
                }
            }

            startAnimation(popInAnimation)
        }
    }

    @Deprecated("Legacy function")
    fun setSorting(sorting: Sorting) { /* ignore */ }

    @DrawableRes
    private fun getIconResDrawable(filtering: Filtering): Int {
        return when (filtering.sort) {
            Sort.trending -> R.drawable.ic_rising
            Sort.date -> {
                if (filtering.order == Order.asc) {
                    R.drawable.ic_old
                } else {
                    R.drawable.ic_new
                }
            }
            Sort.score -> {
                if (filtering.order == Order.asc) {
                    R.drawable.ic_controversial
                } else {
                    R.drawable.ic_top
                }
            }
            Sort.comments -> R.drawable.ic_comments
            Sort.relevance -> R.drawable.ic_relevance
            null -> error("Sort cannot be null")
        }
    }

    private fun getIconVisibility(filtering: Filtering): Int {
        val shouldBeVisible = when (sortType) {
            SortType.GENERAL, SortType.POST -> filtering.sort != Sort.trending
            SortType.SEARCH, SortType.USER -> true
        }
        return if (shouldBeVisible) View.VISIBLE else View.INVISIBLE
    }

    private fun getTextVisibility(filtering: Filtering): Int {
        val shouldBeVisible = when (sortType) {
            SortType.GENERAL, SortType.USER -> filtering.sort == Sort.score
            SortType.SEARCH -> filtering.sort == Sort.score || filtering.sort == Sort.relevance ||
                    filtering.sort == Sort.comments
            SortType.POST -> false
        }
        return if (shouldBeVisible) View.VISIBLE else View.GONE
    }

    private enum class SortType(val value: Int) {
        GENERAL(0), SEARCH(1), USER(2), POST(3);

        companion object {
            fun fromValue(value: Int): SortType {
                return when (value) {
                    0 -> GENERAL
                    1 -> SEARCH
                    2 -> USER
                    3 -> POST
                    else -> throw IllegalArgumentException("Unknown value $value")
                }
            }
        }
    }
}
