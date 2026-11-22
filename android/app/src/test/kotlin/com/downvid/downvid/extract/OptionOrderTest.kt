package com.downvid.downvid.extract

import com.downvid.downvid.core.MediaOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionOrderTest {
    private fun hls(master: String, h: Int) = MediaOption(id = "$master-$h", kind = "hls", url = "$master/$h.m3u8", group = "$master.m3u8", height = h)

    // A feed: two videos' variants arrive mixed, sorted by quality alone.
    private val a = listOf(hls("a", 1080), hls("a", 720), hls("a", 360))
    private val b = listOf(hls("b", 720), hls("b", 480))
    private val mixed = listOf(b[0], a[2], a[0], b[1], a[1])

    @Test
    fun keepsEachVideosQualitiesTogether() {
        assertEquals(a + b, Extractor.ordered(mixed, null).toList())
    }

    @Test
    fun listsTheWatchedVideoFirst() {
        // By its master playlist, or by the variant the page played.
        assertEquals(b + a, Extractor.ordered(mixed, "b.m3u8"))
        assertEquals(b + a, Extractor.ordered(mixed, "b/480.m3u8"))
        assertEquals("b.m3u8", Extractor.focusGroupOf(mixed, "b/720.m3u8"))
    }

    @Test
    fun ignoresAFocusThatIsNotListed() {
        assertNull(Extractor.focusGroupOf(mixed, "https://elsewhere/clip.mp4"))
        assertEquals(a + b, Extractor.ordered(mixed, "https://elsewhere/clip.mp4"))
    }

    @Test
    fun previewsStayLast() {
        val preview = MediaOption(id = "p", url = "p.mp4", height = 1080, small = true)
        assertEquals(a + preview, Extractor.ordered(listOf(preview) + a, null))
    }
}
