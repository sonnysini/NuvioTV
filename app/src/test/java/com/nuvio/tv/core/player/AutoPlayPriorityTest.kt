package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fork feature (sonnysini): cascading autoplay priority levels.
class AutoPlayPriorityTest {

    @After
    fun reset() = AutoPlayPriority.clear()

    private fun select(streams: List<Stream>, mode: StreamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM, regex: String = "") =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regex,
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B", "C", "D"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

    @Test
    fun `without levels upstream order is kept`() {
        val a = stream("A", "a-1080p")
        val c = stream("C", "c-1080p")
        assertEquals(a, select(listOf(a, c)))
    }

    @Test
    fun `level 1 wins over upstream order`() {
        AutoPlayPriority.setLevel("C", 1)
        AutoPlayPriority.setLevel("A", 2)
        val a = stream("A", "a-1080p")
        val c = stream("C", "c-1080p")
        assertEquals(c, select(listOf(a, c)))
    }

    @Test
    fun `falls to next level when level has no regex match`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("B", 1)
        AutoPlayPriority.setLevel("D", 2)
        AutoPlayPriority.setLevel("C", 3)
        val a = stream("A", "a-720p")
        val b = stream("B", "b-720p")
        val c = stream("C", "c-1080p")
        val d = stream("D", "d-1080p")
        assertEquals(d, select(listOf(a, b, c, d), StreamAutoPlayMode.REGEX_MATCH, "1080p"))
    }

    @Test
    fun `same level considers both sources`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("B", 1)
        AutoPlayPriority.setLevel("C", 2)
        val a = stream("A", "a-720p")
        val b = stream("B", "b-1080p")
        val c = stream("C", "c-1080p")
        assertEquals(b, select(listOf(a, c, b), StreamAutoPlayMode.REGEX_MATCH, "1080p"))
    }

    @Test
    fun `unranked sources come last`() {
        AutoPlayPriority.setLevel("D", 1)
        val a = stream("A", "a-1080p")
        val d = stream("D", "d-1080p")
        assertEquals(d, select(listOf(a, d)))
        assertEquals(a, select(listOf(a)))
    }

    @Test
    fun `nothing playable returns null`() {
        AutoPlayPriority.setLevel("A", 1)
        assertNull(select(listOf(stream("A", "x", url = null))))
    }

    @Test
    fun `settled only when same and higher levels answered`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("B", 1)
        AutoPlayPriority.setLevel("D", 2)
        val a = stream("A", "a")
        val d = stream("D", "d")
        assertFalse(AutoPlayPriority.isSettled(a, listOf(a), pendingSourceNames = listOf("B")))
        assertTrue(AutoPlayPriority.isSettled(a, listOf(a), pendingSourceNames = listOf("D", "Unranked")))
        assertFalse(AutoPlayPriority.isSettled(d, listOf(d), pendingSourceNames = listOf("A")))
        val unranked = stream("X", "x")
        assertFalse(AutoPlayPriority.isSettled(unranked, listOf(unranked), emptyList()))
    }

    @Test
    fun `not settled while a higher level torrent is still checking`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("D", 2)
        val checking = stream("A", "a", url = null, infoHash = "abc", cacheState = StreamDebridCacheState.CHECKING)
        val d = stream("D", "d")
        assertFalse(AutoPlayPriority.isSettled(d, listOf(checking, d), emptyList()))
    }

    @Test
    fun `inside a level the best quality wins`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("B", 1)
        val a720 = stream("A", "a-720p")
        val b1080 = stream("B", "b-1080p")
        val a4k = stream("A", "a 4K HDR")
        assertEquals(b1080, select(listOf(a720, b1080), StreamAutoPlayMode.REGEX_MATCH, "(2160p|4k|1080p|720p)"))
        assertEquals(a4k, select(listOf(a720, b1080, a4k)))
    }

    @Test
    fun `higher level wins over better quality on a lower level`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("C", 3)
        val a720 = stream("A", "a-720p")
        val c1080 = stream("C", "c-1080p")
        assertEquals(a720, select(listOf(c1080, a720), StreamAutoPlayMode.REGEX_MATCH, "(1080p|720p)"))
    }

    @Test
    fun `same quality keeps upstream order and unknown quality goes last`() {
        AutoPlayPriority.setLevel("A", 1)
        AutoPlayPriority.setLevel("B", 1)
        val aUnknown = stream("A", "a-sd")
        val b1 = stream("B", "b1-1080p")
        val b2 = stream("B", "b2-1080p")
        assertEquals(b1, select(listOf(aUnknown, b1, b2)))
        assertEquals(0, AutoPlayPriority.qualityRank(aUnknown))
        assertEquals(4, AutoPlayPriority.qualityRank(stream("A", "Film.2160p.mkv")))
    }

    private fun stream(
        addonName: String,
        name: String,
        url: String? = "https://example.com/$name.m3u8",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = null
        ),
        addonName = addonName,
        addonLogo = null,
        debridCacheStatus = cacheState?.let {
            StreamDebridCacheStatus(providerId = "torbox", providerName = "Torbox", state = it)
        }
    )
}
