package com.cosmos.unreddit.util

import android.view.Gravity
import androidx.core.text.HtmlCompat
import com.cosmos.unreddit.data.model.Block.ImageBlock
import com.cosmos.unreddit.data.model.Block.TableBlock
import com.cosmos.unreddit.data.model.Block.TextBlock
import com.cosmos.unreddit.data.model.Block.VideoBlock
import com.cosmos.unreddit.data.model.HtmlBlock
import com.cosmos.unreddit.data.model.RedditText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.LinkedList

class HtmlParser(private val defaultDispatcher: CoroutineDispatcher) {

    private val TABLE_REGEX = Regex("<table>.*?</table>", RegexOption.DOT_MATCHES_ALL)
    private val CODE_REGEX = Regex("<pre><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)
    private val IMG_LINK_REGEX =
        Regex("""<a [^>]*?href="([^"]+)"[^>]*?>\s*<img([^>]*)>\s*</a>""", RegexOption.DOT_MATCHES_ALL)
    // Reddit wraps inline comment media in <figure class="rte-media">…</figure>:
    //  - video: <shreddit-player src="HLS" poster="…">…<style>…spinner…</style></shreddit-player>
    //    + a <template slot="error"> fallback ("Sorry, something went wrong… View in app").
    //  - image: <a href><img … width="240" height="auto"></a> (sometimes bare <img>).
    // Left in the text, the video figure renders as a turquoise placeholder square +
    // raw CSS (.buffering-track-fill) + the error-template text. It must be pulled out
    // whole and turned into a VIDEO block before anything else runs.
    private val FIGURE_REGEX =
        Regex("<figure[^>]*>.*?</figure>", RegexOption.DOT_MATCHES_ALL)
    private val FIGURE_PLAYER_SRC_REGEX = Regex("""<shreddit-player[^>]*\ssrc="([^"]+)""")
    private val FIGURE_POSTER_REGEX = Regex("""<shreddit-player[^>]*\bposter="([^"]+)""")
    private val FIGURE_POSTER_IMG_REGEX =
        Regex("""<img[^>]*alt="media poster"[^>]*\ssrc="([^"]+)"""")
    // v2.5.62: CSS aspect-ratio (spec: WIDTH/HEIGHT) — a plain number X (W/H = X)
    // or a fraction A/B (W/H = A/B). Used for video figures AND image styles.
    private val ASPECT_RATIO_REGEX =
        Regex("""aspect-ratio:\s*([0-9.]+)(?:\s*/\s*([0-9.]+))?""")
    // v2.5.60: GIF players have no CSS aspect-ratio and no poster; their box comes
    // from the wrapper div's inline style: style="width: 240px; height: 134.83px;".
    private val FIGURE_BOX_STYLE_REGEX =
        Regex("""style="[^"]*?width:\s*([0-9.]+)px;\s*height:\s*([0-9.]+)px""")
    // The reddit GIF "video" flag is a bare `gif` attribute on <shreddit-player>.
    private val FIGURE_PLAYER_TAG_REGEX = Regex("<shreddit-player[^>]*>")
    private val GIF_FLAG_REGEX = Regex("""\bgif\b""")
    private val IMG_SRC_REGEX = Regex("""src="([^"]+)""")
    private val IMG_WIDTH_REGEX = Regex("""width="([^"]+)""")
    private val IMG_HEIGHT_REGEX = Regex("""height="([^"]+)""")
    // v2.5.62: reddit's NEW comment-image markup (2026-09-12, r/3Dprinting 1wcscy6)
    // publishes a FRACTIONAL width attr + height="auto" and the real ratio ONLY in
    // the style's aspect-ratio: width="180.70588235294116" height="auto"
    // style="object-fit: cover;aspect-ratio:180.70588235294116/240". The width attr
    // may itself be a fraction (so toIntOrNull() returns null), and the ratio may be
    // a fraction (N/M) or a plain number (M). Captured group 1 = numerator,
    // group 2 = optional denominator.
    private val IMG_STYLE_ATTR_REGEX = Regex("""style="([^"]*)""")
    private val PLACEHOLDER_REGEX = Regex("<(table|code|img|video)_placeholder/>")

    private val tagHandler = RedditTagHandler()

    suspend fun separateHtmlBlocks(html: String?): RedditText = withContext(defaultDispatcher) {
            val redditText = RedditText()

            if (html == null) return@withContext redditText

            val tables = LinkedList<String>()
            val codes = LinkedList<String>()
            val images = LinkedList<ImageBlock>()
            val videos = LinkedList<VideoBlock>()

            var newHtml = html

            newHtml = newHtml.replace(TABLE_REGEX) {
                tables.add(it.groupValues[0])
                TABLE_PLACEHOLDER
            }

            newHtml = newHtml.replace(CODE_REGEX) {
                codes.add(it.groupValues[1])
                CODE_PLACEHOLDER
            }

            // v2.5.58: pull inline media out of the comment/post HTML before any text
            // processing. Reddit wraps it in <figure class="rte-media">…</figure>:
            //  - video figures hold a <shreddit-player> (HLS src + poster) plus a
            //    <style> spinner rule and a <template slot="error"> fallback; left in
            //    the text they render as raw CSS + "Sorry, something went wrong…".
            //  - image figures hold <a href><img width=240 height=auto></a>.
            // Both become blocks; the figure markup itself is consumed (never leaked).
            newHtml = replaceInlineMedia(newHtml, images, videos)

            if (PLACEHOLDER_REGEX.containsMatchIn(newHtml)) {
                var lastIndex = 0

                for (match in PLACEHOLDER_REGEX.findAll(newHtml)) {
                    if (match.range.first > 0) {
                        val previousBlock = newHtml.substring(lastIndex, match.range.first - 1)
                        redditText.addBlock(getTextBlock(previousBlock), HtmlBlock.BlockType.TEXT)
                    }

                    lastIndex = match.range.last + 1

                    when (match.value) {
                        TABLE_PLACEHOLDER -> {
                            val tableBlock = getTableFromHtmlTable(tables.pop())
                            redditText.addBlock(tableBlock, HtmlBlock.BlockType.TABLE)
                        }

                        CODE_PLACEHOLDER -> {
                            val codeBlock = Parser.unescapeEntities(codes.pop(), true)
                            redditText.addBlock(TextBlock(codeBlock), HtmlBlock.BlockType.CODE)
                        }

                        IMG_PLACEHOLDER -> redditText.addBlock(images.pop(), HtmlBlock.BlockType.IMAGE)

                        VIDEO_PLACEHOLDER -> redditText.addBlock(videos.pop(), HtmlBlock.BlockType.VIDEO)
                    }
                }

                if (lastIndex < newHtml.length) {
                    val lastBlock = newHtml.substring(lastIndex)
                    redditText.addBlock(getTextBlock(lastBlock), HtmlBlock.BlockType.TEXT)
                }
            } else {
                redditText.addBlock(getTextBlock(newHtml), HtmlBlock.BlockType.TEXT)
            }

            return@withContext redditText
    }

    private fun getTableFromHtmlTable(html: String): TableBlock {
        val table = TableBlock()

        val doc = Jsoup.parse(html)

        val rows = doc.select("tr")

        for (row in rows) {
            var isHeader = false

            val cols = if (row.select("td").isNotEmpty()) {
                row.select("td")
            } else {
                isHeader = true
                row.select("th")
            }

            val tableRow = TableBlock.Row(isHeader)

            for (col in cols) {
                val alignment = col.attr("align")

                val spannedHtml = fromHtml(col.html())
                val gravity = getGravityFromAlign(alignment)

                tableRow.addColumn(spannedHtml, gravity)
            }

            table.addRow(tableRow)
        }

        return table
    }

    private fun getGravityFromAlign(align: String): Int {
        return when (align) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            "center" -> Gravity.CENTER
            else -> Gravity.START
        }
    }

    private fun getTextBlock(html: String): TextBlock {
        return TextBlock(fromHtml(html))
    }

    private fun fromHtml(html: String): CharSequence {
        val overriddenHtml = tagHandler.overrideTags(html)
        return HtmlCompat.fromHtml(
            overriddenHtml,
            HtmlCompat.FROM_HTML_MODE_LEGACY,
            null,
            tagHandler
        ).removeSuffix("\n\n")
    }

    /**
     * Scans [html] for reddit inline-media figures and figure-less <a><img></a> and
     * replaces each with a placeholder token, recording the parsed [ImageBlock]/
     * [VideoBlock] into [images]/[videos] in document order. The figure markup (spinner
     * <style>, error <template>, player chrome) is consumed entirely so none of it can
     * leak into the rendered text.
     *
     * Pure string work (no Android / HtmlCompat) so it can be unit-tested on the JVM
     * against real captured markup.
     */
    internal fun replaceInlineMedia(
        html: String,
        images: MutableList<ImageBlock>,
        videos: MutableList<VideoBlock>
    ): String =
        html.replace(FIGURE_REGEX) { m ->
            val figure = m.value
            if (figure.contains("<shreddit-player", ignoreCase = true)) {
                videos.add(parseVideoFigure(figure))
                VIDEO_PLACEHOLDER
            } else if (figure.contains("<img", ignoreCase = true)) {
                images.add(parseImageFigure(figure))
                IMG_PLACEHOLDER
            } else {
                // A media figure with neither a player nor an image (e.g. a
                // placeholder-only figure). Consume it so nothing leaks, emit nothing.
                ""
            }
        }.replace(IMG_LINK_REGEX) { m ->
            // Figure-less inline image (the pre-2024 <a><img></a> shape, and any image
            // not wrapped in a figure). Keep the existing behaviour.
            val href = Parser.unescapeEntities(m.groupValues[1], true)
            val imgAttrs = m.groupValues[2]
            val src = IMG_SRC_REGEX.find(imgAttrs)?.groupValues?.get(1)
            val url = if (src.isNullOrBlank()) href else Parser.unescapeEntities(src, true)
            images.add(imageBlockFromAttrs(url, imgAttrs))
            IMG_PLACEHOLDER
        }

    private fun parseVideoFigure(figure: String): VideoBlock {
        val src = FIGURE_PLAYER_SRC_REGEX.find(figure)?.groupValues?.get(1)
        val url = Parser.unescapeEntities(src.orEmpty(), true)
        val posterAttr = FIGURE_POSTER_REGEX.find(figure)?.groupValues?.get(1)
        val posterImg = FIGURE_POSTER_IMG_REGEX.find(figure)?.groupValues?.get(1)
        val poster = Parser.unescapeEntities(posterAttr ?: posterImg ?: "", true)
            .ifBlank { null }
        // v2.5.60: reddit marks GIF "videos" with a bare `gif` attribute. Their box
        // ratio comes from the wrapper div's inline style (width:240px;height:134.8px)
        // rather than a CSS aspect-ratio, so that is the ratio fallback.
        val playerTag = FIGURE_PLAYER_TAG_REGEX.find(figure)?.groupValues?.get(0).orEmpty()
        val isGif = GIF_FLAG_REGEX.containsMatchIn(playerTag)
        // v2.5.62: CSS aspect-ratio is WIDTH/HEIGHT (spec + verified against the
        // real poster: 602x480, W/H = 1.254 = published 1.254). A plain number X
        // means W/H = X (H/W = 1/X); a fraction A/B means W/H = A/B (H/W = B/A).
        val ratio = ASPECT_RATIO_REGEX.find(figure)?.let { m ->
            val a = m.groupValues[1].toFloatOrNull() ?: return@let null
            if (a <= 0f) return@let null
            val b = m.groupValues[2]?.toFloatOrNull()
            if (b != null && b > 0f) b / a else 1f / a
        }
            ?: FIGURE_BOX_STYLE_REGEX.find(figure)?.let { m ->
                val w = m.groupValues[1].toFloatOrNull()
                val h = m.groupValues[2].toFloatOrNull()
                if (w != null && h != null && w > 0f) h / w else null
            }
        val width = if (ratio != null && ratio > 0f) 1000 else 0
        val height = if (ratio != null && ratio > 0f) (1000 * ratio).toInt() else 0
        return VideoBlock(url, poster, width, height, isGif)
    }

    private fun parseImageFigure(figure: String): ImageBlock {
        // Prefer an <a href><img></a>; fall back to the bare <img> inside the figure.
        val link = IMG_LINK_REGEX.find(figure)
        if (link != null) {
            val href = Parser.unescapeEntities(link.groupValues[1], true)
            val imgAttrs = link.groupValues[2]
            val src = IMG_SRC_REGEX.find(imgAttrs)?.groupValues?.get(1)
            val url = if (src.isNullOrBlank()) href else Parser.unescapeEntities(src, true)
            return imageBlockFromAttrs(url, imgAttrs)
        }
        val img = Regex("""<img([^>]*)>""").find(figure)
        val imgAttrs = img?.groupValues?.get(1).orEmpty()
        val url = Parser.unescapeEntities(
            IMG_SRC_REGEX.find(imgAttrs)?.groupValues?.get(1).orEmpty(), true
        )
        return imageBlockFromAttrs(url, imgAttrs)
    }

    /**
     * v2.5.62: width/height for an inline <img>. The HTML width/height attrs are
     * CSS px and MAY be fractional (width="180.70588235294116") or "auto" — both
     * fail toIntOrNull(), leaving (0, 0) so the renderer falls back to the
     * decoded bitmap's ratio, which reddit's width-capped renditions do not
     * preserve: the image renders clipped to the box and only re-fits after a
     * rebind (the 2026-09-12 "inline media not resized until I scroll away"
     * report). When the attrs do not both parse, the style's aspect-ratio is
     * authoritative: CSS aspect-ratio is WIDTH/HEIGHT, published as a plain
     * number X (W/H = X) or a fraction A/B (W/H = A/B). It is stored the same
     * way video ratios are — width 1000, height 1000 x (H/W) — so the renderer
     * locks the box to the true ratio.
     */
    private fun imageBlockFromAttrs(url: String, imgAttrs: String): ImageBlock {
        val w = IMG_WIDTH_REGEX.find(imgAttrs)?.groupValues?.get(1)?.toFloatOrNull()
        val h = IMG_HEIGHT_REGEX.find(imgAttrs)?.groupValues?.get(1)?.toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) {
            // Both attrs are usable ints: keep them as-is (existing behaviour).
            return ImageBlock(url, w.toInt(), h.toInt())
        }
        val ratio = aspectRatioFromAttrs(imgAttrs)
        return if (ratio != null && ratio > 0f) {
            ImageBlock(url, 1000, (1000 * ratio).toInt())
        } else {
            ImageBlock(url, 0, 0)
        }
    }

    /**
     * The aspect ratio H/W of a `style="…aspect-ratio: …"` value, or null.
     * CSS aspect-ratio is WIDTH/HEIGHT (verified 2026-09-12 against a real
     * reddit video poster: aspect-ratio 1.2541666666666667, decoded 602x480,
     * 602/480 = 1.25416…): a plain number X means W/H = X (H/W = 1/X), and a
     * fraction A/B means W/H = A/B (H/W = B/A).
     */
    private fun aspectRatioHW(style: String): Float? {
        val m = ASPECT_RATIO_REGEX.find(style) ?: return null
        val a = m.groupValues[1].toFloatOrNull() ?: return null
        if (a <= 0f) return null
        val b = m.groupValues[2]?.toFloatOrNull()
        val hOverW = if (b != null && b > 0f) b / a else 1f / a
        return hOverW
    }

    /** The img's aspect ratio (H/W) from its style attribute, or null. */
    private fun aspectRatioFromAttrs(imgAttrs: String): Float? {
        val style = IMG_STYLE_ATTR_REGEX.find(imgAttrs)?.groupValues?.get(1) ?: return null
        return aspectRatioHW(style)
    }

    companion object {
        private const val TABLE_PLACEHOLDER = "<table_placeholder/>"
        private const val CODE_PLACEHOLDER = "<code_placeholder/>"
        private const val IMG_PLACEHOLDER = "<img_placeholder/>"
        private const val VIDEO_PLACEHOLDER = "<video_placeholder/>"
    }
}
