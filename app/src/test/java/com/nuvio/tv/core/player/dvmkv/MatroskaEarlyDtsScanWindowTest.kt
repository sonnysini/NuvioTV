package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaEarlyDtsScanWindowTest {

    private class CountingReader(private val data: ByteArray) : DataReader {
        var position = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            return n
        }
    }

    private val data = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `window mirrors the input and only the missing bytes are fetched`() {
        val reader = CountingReader(data)
        val input = DefaultExtractorInput(reader, 0, data.size.toLong())
        val buffer = ByteArray(data.size)
        var peeked = 0
        for (want in intArrayOf(12, 12, 300, 40, 1000, 1000)) {
            peeked = MatroskaExtractor.extendScanWindow(input, buffer, peeked, want)
        }
        assertEquals(1000, peeked)
        assertEquals(1000, reader.position)
        assertArrayEquals(data.copyOfRange(0, 1000), buffer.copyOfRange(0, 1000))
        assertEquals(0L, input.position)
    }

    @Test
    fun `end of input before the missing bytes reports minus one`() {
        val input = DefaultExtractorInput(CountingReader(data), 0, data.size.toLong())
        val buffer = ByteArray(data.size + 64)
        val peeked = MatroskaExtractor.extendScanWindow(input, buffer, 0, data.size)
        assertEquals(data.size, peeked)
        assertEquals(-1, MatroskaExtractor.extendScanWindow(input, buffer, peeked, data.size + 16))
    }
}
