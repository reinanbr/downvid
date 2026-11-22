package com.downvid.downvid.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    @Test
    fun returnsNullWithoutUrl() {
        assertNull(LinkParser.parse(null))
        assertNull(LinkParser.parse("sem link aqui"))
        assertNull(LinkParser.parse("ftp://example.com/a"))
    }

    @Test
    fun extractsUrlAndTrimsPunctuation() {
        val l = LinkParser.parse("Olha isso: https://youtu.be/dQw4w9WgXcQ?si=abc!")!!
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", l.url)
        assertEquals(SourcePlatform.YOUTUBE, l.platform)
    }

    @Test
    fun takesFirstUrl() {
        assertEquals(SourcePlatform.X, LinkParser.parse("https://x.com/a/status/1 e https://tiktok.com/@b")!!.platform)
    }

    @Test
    fun hostIgnoresUserInfoAndPort() {
        assertEquals("example.com", LinkParser.hostOf("https://user:pw@Example.com:8443/x"))
    }

    @Test
    fun prefersAudio() {
        assertTrue(LinkParser.parse("https://music.youtube.com/watch?v=x")!!.prefersAudio)
        assertTrue(LinkParser.parse("https://open.spotify.com/track/abc")!!.isMusicService)
        assertFalse(LinkParser.parse("https://www.youtube.com/watch?v=x")!!.prefersAudio)
    }

    @Test
    fun detectsPlatforms() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=1" to SourcePlatform.YOUTUBE,
            "https://m.youtube.com/shorts/1" to SourcePlatform.YOUTUBE,
            "https://www.instagram.com/reel/abc/" to SourcePlatform.INSTAGRAM,
            "https://www.threads.com/@u/post/DTvidE0xAmp" to SourcePlatform.THREADS,
            "https://www.threads.net/@u/post/DTvidE0xAmp?xmt=a" to SourcePlatform.THREADS,
            "https://vm.tiktok.com/ZMabc/" to SourcePlatform.TIKTOK,
            "https://www.kwai.com/@u/video/1" to SourcePlatform.KWAI,
            "https://twitter.com/u/status/1" to SourcePlatform.X,
            "https://fb.watch/abc/" to SourcePlatform.FACEBOOK,
            "https://m.facebook.com/watch/?v=1" to SourcePlatform.FACEBOOK,
            "https://example.com/video.mp4" to SourcePlatform.GENERIC,
            // Lookalike domains must not match.
            "https://notyoutube.com/watch" to SourcePlatform.GENERIC,
            "https://box.com/x" to SourcePlatform.GENERIC,
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT" to SourcePlatform.SPOTIFY,
            "https://www.deezer.com/br/track/14408104" to SourcePlatform.DEEZER,
            "https://music.apple.com/br/album/x/1559885415?i=1559885421" to SourcePlatform.APPLE_MUSIC,
            "https://soundcloud.com/artist/track" to SourcePlatform.SOUNDCLOUD,
            "https://music.youtube.com/watch?v=x" to SourcePlatform.YOUTUBE,
        )
        for ((url, expected) in cases) assertEquals(url, expected, LinkParser.parse(url)!!.platform)
    }
}
