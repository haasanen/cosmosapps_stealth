package com.cosmos.unreddit.util

import com.cosmos.unreddit.data.model.Block.ImageBlock
import com.cosmos.unreddit.data.model.Block.VideoBlock
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for inline comment media (reddit `rte-media` figures), verified
 * against REAL markup captured from reddit.com (r/LocalLLaMA post 1wapjaw, 2026-09-10):
 *
 * 1. A video figure (<shreddit-player> + spinner <style> + <template slot="error">
 *    fallback) must become a single VIDEO block carrying the playable HLS url, the
 *    poster frame and the published aspect ratio — and NONE of the player chrome may
 *    leak into the surrounding text (raw CSS ".buffering-track-fill {…}",
 *    "Sorry, something went wrong when loading this video.", "View in app").
 *
 * 2. An image figure (<a href><img width="240" height="auto" style="…aspect-ratio:240/134">)
 *    must become an IMAGE block with the full-resolution url and the published
 *    ratio (height "auto" can't parse, so the style's CSS aspect-ratio is
 *    authoritative; stored as width 1000, height 1000 x H/W).
 *
 * 3. Figure-less <a><img></a> inline images (the pre-2024 shape) still parse.
 *
 * The tested function [HtmlParser.replaceInlineMedia] is pure string work (no
 * HtmlCompat/Android), so it runs on the plain JVM unit-test runtime.
 */
class HtmlParserInlineMediaTest {

    private val parser = HtmlParser(Dispatchers.Unconfined)

    private val videoFigure =
        File("src/test/resources/reddit_ssr/inline_video_figure.html").readText()
    private val imageFigure =
        File("src/test/resources/reddit_ssr/inline_image_figure.html").readText()
    private val gifFigure =
        File("src/test/resources/reddit_ssr/inline_gif_figure.html").readText()
    private val fractionalWidthImageFigure =
        File(
            "src/test/resources/reddit_ssr/inline_fractional_width_image_figure.html"
        ).readText()

    @Test
    fun videoFigureBecomesVideoBlockWithHlsUrlPosterAndAspectRatio() {
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>I will never forget this quote: they distilled it all.</p> $videoFigure"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals("expected exactly one video block", 1, videos.size)
        assertEquals("no image block from a video figure", 0, images.size)

        val video = videos[0]
        assertTrue(
            "HLS url not parsed: ${video.url}",
            video.url.startsWith("https://v.redd.it/link/1wapjaw/asset/6qwh23hvuboh1/HLSPlaylist.m3u8")
        )
        assertFalse("entities must be unescaped", video.url.contains("&amp;"))
        assertTrue(
            "poster not parsed: ${video.poster}",
            video.poster?.startsWith("https://preview.redd.it/6qwh23hvuboh1.jpg") == true
        )
        // aspect-ratio: 1.2541666666666667 is CSS W/H (verified: real poster is
        // 602x480, W/H = 1.254). Stored H/W => 1000 x ~797 (landscape), NOT the
        // old inverted 1000x1254 that squished landscape video into a portrait box.
        assertEquals("width", 1000, video.width)
        assertTrue("height ratio wrong: ${video.height}", video.height in 793..801)

        // The placeholder is the ONLY thing left of the figure.
        assertTrue("video placeholder missing", "<video_placeholder/>" in out)
        // NOTHING from the player chrome leaks into the text.
        for (leak in listOf(
            ".buffering-track-fill", "shreddit-player", "buffering",
            "Sorry, something went wrong when loading this video.",
            "View in app", "app-link", "stroke-dasharray", "template slot"
        )) {
            assertFalse("player chrome leaked into text: $leak", leak in out)
        }
        // The surrounding text survived.
        assertTrue("surrounding text missing", "I will never forget this quote" in out)
    }

    @Test
    fun imageFigureBecomesImageBlockWithFullResUrlAndPublishedWidth() {
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>Here is the proof:</p> $imageFigure"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals("expected exactly one image block", 1, images.size)
        assertEquals("no video from an image figure", 0, videos.size)

        val image = images[0]
        assertTrue(
            "url not parsed: ${image.url}",
            image.url.startsWith(
                "https://preview.redd.it/openai-alleged-of-stealing-mathematicians-work-v0-hbp1d9ki3boh1.png"
            )
        )
        assertFalse("entities must be unescaped", image.url.contains("&amp;"))
        // height=auto fails toFloatOrNull, so the style's aspect-ratio 240/134
        // (CSS W/H) is authoritative: stored H/W => 1000 x ~558, box locked.
        assertEquals("width", 1000, image.width)
        assertTrue("height ratio wrong: ${image.height}", image.height in 554..562)

        assertTrue("image placeholder missing", "<img_placeholder/>" in out)
        assertFalse("figure chrome leaked", "rte-media" in out)
        assertFalse("img attrs leaked", "srcset" in out)
        assertTrue("surrounding text missing", "Here is the proof:" in out)
    }

    @Test
    fun fractionalWidthImageUsesStyleAspectRatio() {
        // Real markup from r/3Dprinting 1wcscy6 (2026-09-12): the width attr is a
        // FRACTION (180.70588235294116) and height is "auto" — both fail int/float
        // parsing as a pair, and the true ratio lives ONLY in the style's
        // aspect-ratio: 180.70588235294116/240 (CSS W/H, portrait: real image is
        // 3072x4080). Before v2.5.62 the block was (0,0) => the renderer fell back
        // to the decoded rendition's ratio, clipped the image to the box, and only
        // re-fit it on a rebind. It must now carry the locked published ratio.
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>Ded</p> $fractionalWidthImageFigure"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals("expected exactly one image block", 1, images.size)
        assertEquals("no video from an image figure", 0, videos.size)

        val image = images[0]
        assertTrue(
            "full-res url not parsed: ${image.url}",
            image.url.startsWith(
                "https://preview.redd.it/first-jump-test-of-my-3d-printed-robot-didnt-go-as-planned-v0-ey9puvb3qqoh1.jpeg"
            )
        )
        assertFalse("entities must be unescaped", image.url.contains("&amp;"))
        // aspect-ratio 180.70588235294116/240 = W/H 0.7529 => H/W 1.3281
        // => stored as width 1000, height ~1328 (portrait, matching 3072x4080).
        assertEquals("width", 1000, image.width)
        assertTrue("height ratio wrong: ${image.height}", image.height in 1322..1334)

        assertTrue("image placeholder missing", "<img_placeholder/>" in out)
        assertFalse("style leaked", "aspect-ratio" in out)
        assertTrue("surrounding text missing", "Ded" in out)
    }

    @Test
    fun figurelessAImgInlineImageStillParses() {
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html =
            "<p>old style</p> <a href=\"https://preview.redd.it/full.jpg\"><img src=\"https://preview.redd.it/thumb.jpg\" width=\"240\" height=\"134\"></a>"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals(1, images.size)
        assertEquals(0, videos.size)
        assertEquals("https://preview.redd.it/thumb.jpg", images[0].url)
        assertEquals(240, images[0].width)
        assertEquals(134, images[0].height)
        assertTrue("image placeholder missing", "<img_placeholder/>" in out)
    }

    @Test
    fun mixedCommentTextVideoAndImageKeepsDocumentOrder() {
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>before</p> $videoFigure <p>between</p> $imageFigure <p>after</p>"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals(1, videos.size)
        assertEquals(1, images.size)
        // Order in the surviving markup: video placeholder before image placeholder.
        assertTrue(
            "document order broken: $out",
            out.indexOf("<video_placeholder/>") < out.indexOf("<img_placeholder/>")
        )
        assertTrue("text around media missing", "before" in out && "between" in out && "after" in out)
    }

    @Test
    fun noMediaInPlainTextLeavesItUntouched() {
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>just a <strong>text</strong> comment with an <a href=\"/r/linux\">link</a></p>"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals(0, images.size)
        assertEquals(0, videos.size)
        assertEquals(html, out)
    }

    @Test
    fun gifFigureBecomesGifVideoBlockWithBoxRatioAndNoPoster() {
        // Real markup from r/Amd 1wbeqyp (2026-09-11): a reddit GIF "video" — a
        // <shreddit-player ... gif> whose box ratio comes from the wrapper div's
        // inline style (width:240px; height:134.83px), with NO poster and NO CSS
        // aspect-ratio. It must be flagged isGif (so the view autoplays+loops it
        // inline) and carry the box ratio so the box keeps its shape.
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "<p>I'm doing my part!</p> $gifFigure"

        val out = parser.replaceInlineMedia(html, images, videos)

        assertEquals("expected exactly one video block", 1, videos.size)
        assertEquals("no image block from a gif figure", 0, images.size)

        val video = videos[0]
        assertTrue("isGif not detected", video.isGif)
        assertTrue(
            "playable mp4 url not parsed: ${video.url}",
            video.url.startsWith(
                "https://external-preview.redd.it/Mt6wSwNQlYIdMhepPzcDbHpiMA306hWAKb1cpl9AaAg.gif"
            ) && video.url.contains("format=mp4")
        )
        assertFalse("entities must be unescaped", video.url.contains("&amp;"))
        // reddit publishes no poster for GIFs.
        assertTrue("GIF must have no poster, got: ${video.poster}", video.poster == null)
        // Box ratio 134.83/240 = 0.5618 => stored as 1000 x ~562.
        assertEquals("width", 1000, video.width)
        assertTrue("height ratio wrong: ${video.height}", video.height in 558..566)

        assertTrue("video placeholder missing", "<video_placeholder/>" in out)
        assertFalse("figure chrome leaked", "shreddit-player" in out)
        assertFalse("wrapper style leaked", "width: 240px" in out)
        assertTrue("surrounding text missing", "I'm doing my part!" in out)
    }

    @Test
    fun hlsVideoFigureIsNotFlaggedAsGif() {
        // The r/LocalLLaMA video figure has a poster and NO bare `gif` attribute —
        // it must stay a poster+badge video (isGif=false), not an inline autoplay.
        val images = mutableListOf<ImageBlock>()
        val videos = mutableListOf<VideoBlock>()
        val html = "$videoFigure"

        parser.replaceInlineMedia(html, images, videos)

        assertEquals(1, videos.size)
        assertFalse("HLS video must not be flagged as a gif", videos[0].isGif)
        assertTrue("HLS video must keep its poster", !videos[0].poster.isNullOrBlank())
    }
}
