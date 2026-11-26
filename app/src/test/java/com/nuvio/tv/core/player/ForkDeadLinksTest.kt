package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkDeadLinksTest {

    @Test
    fun `AIOStreams placeholder videos are recognised`() {
        assertEquals("downloading", ForkDeadLinks.placeholderName("https://aio.example.it/static/downloading.mp4"))
        assertEquals("download_failed", ForkDeadLinks.placeholderName("https://aio.example.it/static/download_failed.mp4?x=1"))
        assertEquals("no_matching_file", ForkDeadLinks.placeholderName("https://aio.example.it/static/no_matching_file.mp4"))
        assertEquals("429", ForkDeadLinks.placeholderName("https://aio.example.it/static/429.mp4"))
        assertEquals("500", ForkDeadLinks.placeholderName("https://aio.example.it/static/500.mp4#t"))
    }

    @Test
    fun `real streams are not placeholders`() {
        assertNull(ForkDeadLinks.placeholderName(null))
        assertNull(ForkDeadLinks.placeholderName(""))
        assertNull(ForkDeadLinks.placeholderName("https://store.torbox.app/dl/abc/Movie.2160p.mkv"))
        // The success clip and look-alike paths are not failures.
        assertNull(ForkDeadLinks.placeholderName("https://aio.example.it/static/200.mp4"))
        assertNull(ForkDeadLinks.placeholderName("https://cdn.example.com/static/downloading.mp4.mkv"))
        assertNull(ForkDeadLinks.placeholderName("https://cdn.example.com/media/downloading.mp4"))
    }
}
