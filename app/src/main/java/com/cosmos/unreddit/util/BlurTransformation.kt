@file:Suppress("unused")

package com.cosmos.unreddit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * A [Transformation] that blurs an image so its content is unrecognizable
 * (the feed-preview spoiler/NSFW hide state).
 *
 * RenderScript-free on purpose: [android.renderscript.RenderScript] is
 * deprecated since API 31 and GONE on recent Android (Pixel 8a, Android 16) —
 * `RenderScript.create` throws at runtime there, the Coil request errored,
 * and the cell fell back to its near-black placeholder instead of showing a
 * blur (2026-09-15 report: option OFF showed a black image instead of a
 * blur). The previous implementation only worked while the loaded URL was
 * reddit's CDN-frosted `?blur=40` rendition, which already looked blurred
 * whatever the transformation did.
 *
 * The blur is a stack of 2× downscale passes: each halving with bilinear
 * filtering is a lowpass, so N passes ≈ a strong Gaussian. The final bitmap
 * is then drawn up to the output size. Pure Bitmap/Canvas — no native
 * dependencies, same API surface as the RenderScript version.
 *
 * @param context Accepted for source compatibility with the old
 *  RenderScript-based constructor; unused.
 * @param radius Controls blur strength: more 2× passes as it grows.
 * @param sampling The sampling multiplier used to scale the image. Values > 1
 *  will downscale the image. Values between 0 and 1 will upscale the image.
 */
class BlurTransformation @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val radius: Float = DEFAULT_RADIUS,
    private val sampling: Float = DEFAULT_SAMPLING
) : Transformation {

    init {
        require(radius in 0.0..25.0) { "radius must be in [0, 25]." }
        require(sampling > 0) { "sampling must be > 0." }
    }

    override val cacheKey = "${BlurTransformation::class.java.name}-$radius-$sampling"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val outWidth = ((input.width / sampling).toInt()).coerceAtLeast(1)
        val outHeight = ((input.height / sampling).toInt()).coerceAtLeast(1)

        // Each 2× downscale (bilinear) is a lowpass pass; more radius = more
        // passes = stronger blur. Total memory stays bounded: every
        // intermediate is ¼ of the previous one.
        val passes = (1 + (radius / 10.0).toInt()).coerceAtLeast(1)
        var current = input
        var width = input.width
        var height = input.height
        repeat(passes) {
            val nextWidth = (width / 2).coerceAtLeast(4)
            val nextHeight = (height / 2).coerceAtLeast(4)
            if (nextWidth == width && nextHeight == height) return@repeat
            val next = createBitmap(nextWidth, nextHeight, input.config ?: Bitmap.Config.ARGB_8888)
            next.applyCanvas {
                scale(nextWidth.toFloat() / width, nextHeight.toFloat() / height)
                drawBitmap(current, 0f, 0f, paint)
            }
            if (current !== input) current.recycle()
            current = next
            width = nextWidth
            height = nextHeight
        }

        // Draw the heavily low-passed bitmap up to the output size.
        val output = createBitmap(outWidth, outHeight, input.config ?: Bitmap.Config.ARGB_8888)
        output.applyCanvas {
            scale(outWidth.toFloat() / width, outHeight.toFloat() / height)
            drawBitmap(current, 0f, 0f, paint)
        }
        if (current !== input) current.recycle()
        return output
    }

    private companion object {
        private const val DEFAULT_RADIUS = 10f
        private const val DEFAULT_SAMPLING = 1f
    }
}
