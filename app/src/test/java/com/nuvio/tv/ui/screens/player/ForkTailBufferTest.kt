package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ForkTailBufferTest {

    private val total = 1_000L
    private val bytes = ByteArray(100) { it.toByte() } // file positions 900..999
    private val tail = TailBuffer(total, bytes)

    @Test
    fun coversOnlyReadsInsideTheTail() {
        assertEquals(900L, tail.start)
        assertTrue(tail.covers(900, -1))
        assertTrue(tail.covers(950, 50))
        assertTrue(tail.covers(999, 1))
        assertFalse(tail.covers(899, -1))
        assertFalse(tail.covers(899, 10))
        assertFalse(tail.covers(950, 51))
        assertFalse(tail.covers(1_000, -1))
    }

    @Test
    fun copiesTheBytesAtTheirFilePosition() {
        val target = ByteArray(10)
        assertEquals(10, tail.copy(950, target, 0, 10))
        assertArrayEquals(ByteArray(10) { (50 + it).toByte() }, target)
    }

    @Test
    fun stopsAtTheEndOfTheStream() {
        val target = ByteArray(10)
        assertEquals(5, tail.copy(995, target, 2, 10))
        assertEquals(95.toByte(), target[2])
        assertEquals(99.toByte(), target[6])
        assertEquals(-1, tail.copy(1_000, target, 0, 10))
    }

    @Test
    fun copiesIntoByteBuffers() {
        val target = ByteBuffer.allocate(4)
        assertEquals(4, tail.copy(900, target, 10))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), target.array())
    }

    @Test
    fun readsTheTotalFromContentRange() {
        assertEquals(65_970_331_942L, ForkLinkPrewarm.totalFromContentRange("bytes 0-0/65970331942"))
        assertEquals(null, ForkLinkPrewarm.totalFromContentRange("bytes 0-0/*"))
        assertEquals(null, ForkLinkPrewarm.totalFromContentRange(null))
    }
}
