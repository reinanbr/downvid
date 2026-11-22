package com.downvid.downvid.browser

import com.downvid.downvid.core.MediaOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLogicTest {
    @Test
    fun infersRequestTypesForTheAdBlocker() {
        val t = RequestType::of
        assertEquals(RequestType.DOCUMENT, t(true, emptyMap(), "/"))
        assertEquals(RequestType.SCRIPT, t(false, mapOf("Sec-Fetch-Dest" to "script"), "/x"))
        assertEquals(RequestType.XHR, t(false, mapOf("sec-fetch-dest" to "empty"), "/api"))
        assertEquals(RequestType.SUBDOCUMENT, t(false, mapOf("Accept" to "text/html,application/xhtml+xml"), "/frame"))
        assertEquals(RequestType.STYLESHEET, t(false, mapOf("Accept" to "text/css,*/*;q=0.1"), "/s"))
        assertEquals(RequestType.IMAGE, t(false, mapOf("Accept" to "image/avif,image/webp,*/*"), "/pixel"))
        assertEquals(RequestType.MEDIA, t(false, mapOf("Accept" to "*/*", "Range" to "bytes=0-"), "/v"))
        assertEquals(RequestType.SCRIPT, t(false, mapOf("Accept" to "*/*"), "/ads/tag.JS"))
        assertEquals(RequestType.MEDIA, t(false, emptyMap(), "/ad/preroll.mp4"))
        assertEquals(RequestType.OTHER, t(false, emptyMap(), "/collect"))
    }

    @Test
    fun classifiesMediaUrls() {
        val c = MediaUrls::classify
        assertEquals(MediaKind.HLS, c("https://cdn.x.com/v/master.m3u8?token=1", null))
        assertEquals(MediaKind.HLS, c("https://cdn.x.com/play?id=3&format=m3u8", null))
        assertEquals(MediaKind.HLS, c("https://cdn.x.com/play/3", "application/vnd.apple.mpegurl"))
        assertEquals(MediaKind.DASH, c("https://cdn.x.com/v/manifest.mpd", null))
        assertEquals(MediaKind.PROGRESSIVE, c("https://cdn.x.com/v/clip.mp4?sig=abc", null))
        assertEquals(MediaKind.PROGRESSIVE, c("https://cdn.x.com/v/clip.webm", null))
        // A poster in a folder named after the video is not the video.
        assertEquals(MediaKind.NONE, c("https://thumb.example.org/c/c0/Bunny.webm/960px--Bunny.webm.jpg?_=1", null))
        assertEquals(MediaKind.NONE, c("https://cdn.x.com/v/clip.mp4/player.js", null))
        assertEquals(MediaKind.SEGMENT, c("https://cdn.x.com/v/seg-12.ts", null))
        assertEquals(MediaKind.SEGMENT, c("https://cdn.x.com/v/chunk-stream0-00012.m4s", null))
        assertEquals(MediaKind.SEGMENT, c("https://cdn.x.com/v/init-stream0.mp4", null))
        assertEquals(MediaKind.SEGMENT, c("https://cdn.x.com/v/segment_3.mp4", null))
        assertEquals(MediaKind.SEGMENT, c("https://cdn.x.com/v/video.mp4/range/0-1000", null))
        assertEquals(MediaKind.NONE, c("https://x.com/app.js", null))
        assertEquals(MediaKind.NONE, c("blob:https://x.com/abc", null))
        assertEquals(MediaKind.NONE, c("data:video/mp4;base64,AA", null))
    }

    @Test
    fun recognizesPreviewClips() {
        assertTrue(MediaUrls.isPreview("https://cdn.x.com/pre_videos/003/039/190/3039190/mp4_720.mp4"))
        assertTrue(MediaUrls.isPreview("https://cdn.x.com/v/123/preview.mp4?x=1"))
        assertTrue(MediaUrls.isPreview("https://cdn.x.com/thumbs/123/clip.webm"))
        assertFalse(MediaUrls.isPreview("https://cdn.x.com/videos/123/1080p.mp4"))
        assertFalse(MediaUrls.isPreview("https://cdn.x.com/v/master.m3u8?preview=0"))
    }

    @Test
    fun samePageIgnoresFragments() {
        assertTrue(MediaDetector.samePage("https://a.com/v#t=10", "https://a.com/v"))
        assertFalse(MediaDetector.samePage("https://a.com/v?id=1", "https://a.com/v?id=2"))
    }

    @Test
    fun playerSendsTheBrowsersReferer() {
        val page = "https://pt.site.com/view_video.php?viewkey=1"
        val ref = com.downvid.downvid.player.PlayerHub::browserReferer
        assertEquals("https://pt.site.com/" to "https://pt.site.com", ref(page, "https://cdn.site-cdn.com/hls/master.m3u8"))
        assertEquals(page to "https://pt.site.com", ref(page, "https://pt.site.com/media/1.mp4"))
        assertEquals("https://a.com:8443/" to "https://a.com:8443", ref("https://a.com:8443/v", "https://a.com/v.mp4"))
    }

    @Test
    fun playsTheMasterPlaylistOfAVariant() {
        val master = "https://cdn.x.com/v/master.m3u8"
        fun hls(url: String, group: String) = MediaOption(kind = "hls", url = url, group = group)
        val options = listOf(
            hls("https://cdn.x.com/v/1080_a.m3u8", master),
            hls("https://cdn.x.com/v/720.m3u8", master),
            // A variant the master's options do not list (another 1080p).
            hls("https://cdn.x.com/v/1080_b.m3u8", "https://cdn.x.com/v/1080_b.m3u8"),
        )
        assertEquals(master, BrowserModel.masterOf(options, master))
        assertEquals(master, BrowserModel.masterOf(options, "https://cdn.x.com/v/720.m3u8"))
        assertEquals(master, BrowserModel.masterOf(options, "https://cdn.x.com/v/1080_b.m3u8"))
        // No master on the page: the playlist itself.
        val alone = "https://cdn.y.com/media.m3u8"
        assertEquals(alone, BrowserModel.masterOf(listOf(hls(alone, alone)), alone))
    }
}
