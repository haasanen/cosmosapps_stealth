package com.cosmos.unreddit.util.extension

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.cosmos.unreddit.R
import com.cosmos.unreddit.util.BlurTransformation
import com.google.android.material.textfield.TextInputLayout

fun TextView.applyGradient(text: String, @ColorInt colors: IntArray) {
    val width = paint.measureText(text)
    val gradientShader = LinearGradient(
        0F,
        0F,
        width,
        textSize,
        colors,
        null,
        Shader.TileMode.CLAMP
    )
    paint.shader = gradientShader
}

fun TextView.blurText(blur: Boolean, coefficient: Int = 3) {
    if (blur) {
        val radius = textSize / coefficient
        val blurMaskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        paint.maskFilter = blurMaskFilter
    } else {
        paint.maskFilter = null
    }
}

fun ImageView.load(
    data: Any?,
    blur: Boolean,
    radius: Float = 25F,
    sampling: Float = 4F,
    scale: Scale = Scale.FILL,
    /**
     * The source's own frosted rendition (reddit's signed `?blur=40` CDN file
     * for flagged posts). When [blur] is set, THIS is loaded instead of the
     * sharp image + client-side blur: it reads exactly like the official
     * reddit blur (2.5.77's downscale blur was visibly weaker). If the
     * rendition fails (stale signed URL from the offline cache, network
     * error) Coil's `listener(onError = …)` falls back to blurring [data]
     * via [BlurTransformation] so the cell is never left unblurred.
     */
    blurUrl: String? = null,
    builder: ImageRequest.Builder.() -> Unit = {}
) {
    val source: Any? = if (blur && !blurUrl.isNullOrBlank()) blurUrl else data
    val frosted = source != null && source != data
    val self: ImageView = this
    val request = ImageRequest.Builder(context)
        .data(source)
        .target(self)
        .crossfade(true)
        .scale(scale)
        .precision(Precision.AUTOMATIC)
        .placeholder(R.drawable.image_placeholder)
        .apply(builder)
        .apply {
            if (blur) {
                if (source == data) {
                    // No frosted rendition available: blur the image client-side.
                    transformations(BlurTransformation(context, radius, sampling))
                }
                if (frosted) {
                    // A bare `error { … }` here does NOT register a Coil callback:
                    // Coil 2.2.2's ImageRequest.Builder only has error(Int) and
                    // error(Drawable), so `error { … }` silently resolves to the
                    // KOTLIN STDLIB `kotlin.error(message)` — i.e.
                    // `throw IllegalStateException(…)` — thrown on EVERY frosted
                    // (spoiler) bind, mid layout pass. That leaked RecyclerView
                    // 1.2.1's mLayoutOrScrollCounter (no try/finally around
                    // onLayoutChildren) and crashed the list on the next page-insert,
                    // and it also meant the frosted preview was never enqueued/loaded.
                    // The real Coil error callback is listener(onError = …).
                    listener(onError = { _, _ ->
                        // The frosted rendition failed (stale signed URL from the
                        // offline cache, network error); blur the sharp image
                        // instead so the cell is never left unblurred.
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(data)
                                .target(self)
                                .scale(scale)
                                .precision(Precision.AUTOMATIC)
                                .placeholder(R.drawable.image_placeholder)
                                .transformations(BlurTransformation(context, radius, sampling))
                                .apply(builder)
                                .build()
                        )
                    })
                }
            }
        }
        .build()
    context.imageLoader.enqueue(request)
}

fun TextInputLayout.text(): String? {
    return editText?.text?.toString()
}

fun View.applyWindowInsets(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

        val paddingLeft = if (left) insets.left else view.paddingLeft
        val paddingTop = if (top) insets.top else view.paddingTop
        val paddingRight = if (right) insets.right else view.paddingRight
        val paddingBottom = if (bottom) insets.bottom else view.paddingBottom

        view.run {
            updatePadding(
                left = paddingLeft,
                top = paddingTop,
                right = paddingRight,
                bottom = paddingBottom
            )

            clearWindowInsetsListener()
        }

        windowInsets
    }
}

fun View.clearWindowInsetsListener() {
    ViewCompat.setOnApplyWindowInsetsListener(this, null)
}

fun View.applyMarginWindowInsets(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

        view.run {
            updateLayoutParams<ViewGroup.MarginLayoutParams> {
                if (left) leftMargin += insets.left
                if (top) topMargin += insets.top
                if (right) rightMargin += insets.right
                if (bottom) bottomMargin += insets.bottom
            }

            clearWindowInsetsListener()
        }

        windowInsets
    }
}

fun View.showWithAlpha(show: Boolean, duration: Long) {
    val fromAlpha = if (show) 0F else 1F
    val toAlpha = if (show) 1F else 0F

    alpha = fromAlpha

    animate()
        .alpha(toAlpha)
        .withStartAction {
            if (show) {
                isVisible = true
            }
        }
        .withEndAction {
            if (!show) {
                isVisible = false
            }
        }
        .setDuration(duration)
        .start()
}

val Int.asBoolean: Boolean
    get() = this == View.VISIBLE
