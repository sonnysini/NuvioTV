package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork (sonnysini): detail-page prefetch cancellation. */
class StreamSearchSessionCacheForkTest {

    private val key = StreamSearchRequestKey(
        profileId = 1,
        type = "movie",
        videoId = "tt1",
        season = null,
        episode = null,
        sourceConfiguration = "config"
    )

    @Test
    fun `cancelIncomplete stops a running search`() = runTest {
        val cache = StreamSearchSessionCache()
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)

        val observer = launch {
            cache.observe(key, forceRefresh = false) {
                flow {
                    emit(NetworkResult.Loading)
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (error: CancellationException) {
                        cancelled.set(true)
                        throw error
                    }
                }
            }.collect { }
        }
        started.await()

        cache.cancelIncomplete(key)
        withContext(Dispatchers.IO) { delay(200) }

        assertTrue(cancelled.get())
        observer.cancel()
    }

    @Test
    fun `cancelIncomplete keeps a finished search`() = runTest {
        val cache = StreamSearchSessionCache()
        val producers = AtomicInteger(0)
        val producer = {
            flow<NetworkResult<List<AddonStreams>>> {
                producers.incrementAndGet()
                emit(NetworkResult.Success(listOf(AddonStreams("A", null, emptyList()))))
            }
        }

        cache.observe(key, forceRefresh = false, producer = producer).first { it is NetworkResult.Success }
        withContext(Dispatchers.IO) { delay(100) }
        cache.cancelIncomplete(key)
        cache.observe(key, forceRefresh = false, producer = producer).first { it is NetworkResult.Success }

        assertEquals(1, producers.get())
    }
}
