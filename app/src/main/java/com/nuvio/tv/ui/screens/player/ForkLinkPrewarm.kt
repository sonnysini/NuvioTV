package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nuvio.tv.core.player.ForkDeadLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: resolves a stream link before playback starts.
 *
 * Addon links (AIOStreams, Comet, ...) usually redirect to the debrid CDN, and the first open pays
 * for the whole resolution (1.5-2.5 s on a Fire TV). The detail page calls [prewarm] once the
 * autoplay source is known: the redirect is followed in the background, the resolved url is kept
 * and the TLS connection stays in the shared playback pool. The player then opens the resolved url
 * straight away through [ForkPrewarmedDataSource]; if that fails it falls back to the original link.
 */
internal object ForkLinkPrewarm {
    private const val TAG = "ForkPrewarm"
    private const val TTL_MS = 20 * 60_000L
    private const val MAX_ENTRIES = 32
    /** Covers the Matroska Cues of big remuxes (seen up to 3.6 MB) and an MP4 moov at the end. */
    internal const val TAIL_BYTES = 6 * 1024 * 1024
    private const val MIN_TAIL_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_TAILS = 2

    private data class Entry(val resolvedUrl: String, val atMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val tails = ConcurrentHashMap<String, TailBuffer>()

    fun tailFor(url: String): TailBuffer? = tails[url]

    private fun keepTail(url: String, tail: TailBuffer) {
        if (tails.size >= MAX_TAILS) tails.keys.filter { it != url }.forEach { tails.remove(it) }
        tails[url] = tail
    }

    fun resolvedFor(url: String): String? {
        val entry = entries[url] ?: return null
        // A url whose tail is still in memory keeps its resolved link: dropping one would send the
        // player back through the redirect while the tail is served anyway. A dead link still falls
        // back to the original one in ForkPrewarmedDataSource.
        if (!tails.containsKey(url) && SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
            entries.remove(url)
            return null
        }
        return entry.resolvedUrl
    }

    fun remember(url: String, resolvedUrl: String) {
        if (canonical(resolvedUrl) == canonical(url)) return
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[url] = Entry(resolvedUrl, SystemClock.elapsedRealtime())
    }

    fun forget(url: String) {
        entries.remove(url)
    }

    /**
     * Follows the link's redirects with the player's own client; never throws. Returns the
     * AIOStreams placeholder name ("downloading", ...) when the link ends on one: the link is then
     * marked dead (see [ForkDeadLinks]) and nothing is kept for it.
     */
    suspend fun prewarm(rawUrl: String, rawHeaders: Map<String, String>?): String? {
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(rawUrl, rawHeaders)
        val url = request.url
        if (!isPrewarmable(url) || tails.containsKey(url)) return null
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            runInterruptible(Dispatchers.IO) prewarm@{
                val client = PlayerPlaybackNetworking.createHttpClient(request.headers)
                val call = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
                    if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                    }
                }.build()
                val (finalUrl, totalLength) = client.newCall(call).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val redirected = canonical(finalUrl) != canonical(url)
                    Log.i(
                        TAG,
                        "PREWARM code=${response.code} redirected=$redirected " +
                            "ms=${SystemClock.elapsedRealtime() - startedAt} host=${response.request.url.host}"
                    )
                    val placeholder = ForkDeadLinks.placeholderName(finalUrl)
                    if (placeholder != null) {
                        Log.w(TAG, "PLACEHOLDER $placeholder: link marked dead")
                        ForkDeadLinks.markDead(rawUrl, url)
                        forget(url)
                        return@prewarm placeholder
                    }
                    if (response.isSuccessful && redirected) remember(url, finalUrl)
                    val total = if (response.code == 206) totalFromContentRange(response.header("Content-Range")) else null
                    finalUrl to total
                }
                // The tail holds the Matroska index the player reads before the first frame.
                if (totalLength != null && totalLength >= MIN_TAIL_FILE_BYTES) {
                    val tailStartedAt = SystemClock.elapsedRealtime()
                    val start = totalLength - TAIL_BYTES
                    val tailCall = call.newBuilder()
                        .url(finalUrl)
                        .header("Range", "bytes=$start-${totalLength - 1}")
                        .build()
                    client.newCall(tailCall).execute().use { response ->
                        val range = response.header("Content-Range")
                        val bytes = if (response.code == 206 &&
                            range?.startsWith("bytes $start-") == true &&
                            totalFromContentRange(range) == totalLength
                        ) response.body?.bytes() else null
                        if (bytes != null && bytes.size == TAIL_BYTES) {
                            keepTail(url, TailBuffer(totalLength, bytes))
                        }
                        Log.i(
                            TAG,
                            "TAIL code=${response.code} kept=${bytes?.size == TAIL_BYTES} " +
                                "ms=${SystemClock.elapsedRealtime() - tailStartedAt}"
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "PREWARM failed after ${SystemClock.elapsedRealtime() - startedAt}ms: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun isPrewarmable(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (PlayerMediaSourceFactory.isLoopbackUrl(url)) return false
        val path = parsed.encodedPath.lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    internal fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun canonical(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url
}

/** The last bytes of a stream, kept in memory. Pure Kotlin so it can be unit tested. */
internal class TailBuffer(val totalLength: Long, private val bytes: ByteArray) {
    val start: Long = totalLength - bytes.size

    /** True when a read of [length] bytes (-1 = to the end) at [position] lies entirely in the tail. */
    fun covers(position: Long, length: Long): Boolean {
        if (position < start || position >= totalLength) return false
        return length < 0 || position + length <= totalLength
    }

    /** Copies up to [length] bytes at [position]; -1 at the end of the stream. */
    fun copy(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        val n = count(position, length)
        if (n <= 0) return -1
        System.arraycopy(bytes, (position - start).toInt(), target, offset, n)
        return n
    }

    fun copy(position: Long, target: java.nio.ByteBuffer, length: Int): Int {
        val n = count(position, minOf(length, target.remaining()))
        if (n <= 0) return -1
        target.put(bytes, (position - start).toInt(), n)
        return n
    }

    private fun count(position: Long, length: Int): Int =
        if (position < start) 0 else minOf(length.toLong(), totalLength - position).toInt()
}

/**
 * The link redirected to an AIOStreams placeholder video. A FileNotFoundException, so the default
 * load error policy fails the source at once instead of retrying the same placeholder.
 */
internal class ForkPlaceholderException(message: String) : java.io.FileNotFoundException(message)

/**
 * Opens [originalUrl] at its resolved location when one is known (prewarmed by the detail page or
 * learned from an earlier open), so neither the first open nor the seeks repeat the redirect.
 * Any failure on the resolved url drops it and retries the original link once.
 */
@OptIn(UnstableApi::class)
internal class ForkPrewarmedDataSource(
    private val upstream: DataSource,
    private val originalUrl: String
) : DataSource by upstream, androidx.media3.common.ByteBufferDataReader {

    // Set while an open is served from the prefetched tail instead of the network.
    private var tail: TailBuffer? = null
    private var tailPosition = 0L
    private var tailRemaining = 0L
    private var tailUri: Uri? = null

    // Diagnostics (tag ForkDS): one line per open and per close, with read counts and timings.
    private val dsId = nextDsId.incrementAndGet()
    private var openedAtMs = 0L
    private var firstReadAtMs = 0L
    private var arrayReads = 0
    private var bufferReads = 0
    private var bytesRead = 0L
    private var lastCloseAtMs = 0L

    private fun countRead(n: Int, byteBuffer: Boolean) {
        if (firstReadAtMs == 0L) firstReadAtMs = SystemClock.elapsedRealtime()
        if (byteBuffer) bufferReads++ else arrayReads++
        if (n > 0) bytesRead += n
    }

    private fun logOpen(kind: String, dataSpec: DataSpec, length: Long, startedAt: Long) {
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastCloseAtMs > 0) now - lastCloseAtMs else -1
        Log.i(
            DIAG_TAG,
            "OPEN#$dsId $kind pos=${dataSpec.position} reqLen=${dataSpec.length} len=$length " +
                "openMs=${now - startedAt} gapSinceCloseMs=$gap bb=${supportsByteBufferRead()} " +
                "thread=${Thread.currentThread().name}"
        )
        openedAtMs = now
        firstReadAtMs = 0L
        arrayReads = 0
        bufferReads = 0
        bytesRead = 0L
    }

    private fun logClose(kind: String) {
        val now = SystemClock.elapsedRealtime()
        val firstRead = if (firstReadAtMs > 0) firstReadAtMs - openedAtMs else -1
        Log.i(
            DIAG_TAG,
            "CLOSE#$dsId $kind openForMs=${if (openedAtMs > 0) now - openedAtMs else -1} " +
                "firstReadMs=$firstRead arrayReads=$arrayReads bufferReads=$bufferReads bytes=$bytesRead"
        )
        lastCloseAtMs = now
        openedAtMs = 0L
    }

    override fun supportsByteBufferRead(): Boolean =
        upstream is androidx.media3.common.ByteBufferDataReader && upstream.supportsByteBufferRead()

    override fun read(buffer: java.nio.ByteBuffer, length: Int): Int {
        val t = tail ?: return (upstream as androidx.media3.common.ByteBufferDataReader).read(buffer, length)
            .also { countRead(it, byteBuffer = true) }
        if (tailRemaining <= 0) return C.RESULT_END_OF_INPUT.also { countRead(it, byteBuffer = true) }
        val n = t.copy(tailPosition, buffer, minOf(length.toLong(), tailRemaining).toInt())
        countRead(n, byteBuffer = true)
        if (n < 0) return C.RESULT_END_OF_INPUT
        tailPosition += n
        tailRemaining -= n
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val t = tail ?: return upstream.read(buffer, offset, length).also { countRead(it, byteBuffer = false) }
        if (length == 0) return 0
        if (tailRemaining <= 0) return C.RESULT_END_OF_INPUT.also { countRead(it, byteBuffer = false) }
        val n = t.copy(tailPosition, buffer, offset, minOf(length.toLong(), tailRemaining).toInt())
        countRead(n, byteBuffer = false)
        if (n < 0) return C.RESULT_END_OF_INPUT
        tailPosition += n
        tailRemaining -= n
        return n
    }

    override fun getUri(): Uri? = tailUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (tail != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        if (tail != null) {
            logClose("tail")
            tail = null
            tailUri = null
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        upstream.close()
        if (openedAtMs > 0) logClose("net closeMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    override fun open(dataSpec: DataSpec): Long {
        val startedAt = SystemClock.elapsedRealtime()
        if (dataSpec.uri.toString() != originalUrl) {
            return upstream.open(dataSpec).also { logOpen("other", dataSpec, it, startedAt) }
        }
        ForkLinkPrewarm.tailFor(originalUrl)
            ?.takeIf { it.covers(dataSpec.position, dataSpec.length) }
            ?.let { t ->
                val available = t.totalLength - dataSpec.position
                tail = t
                tailPosition = dataSpec.position
                tailRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
                tailUri = Uri.parse(ForkLinkPrewarm.resolvedFor(originalUrl) ?: originalUrl)
                Log.i("ForkPrewarm", "TAIL_HIT pos=${dataSpec.position} len=$tailRemaining")
                logOpen("tail", dataSpec, tailRemaining, startedAt)
                return tailRemaining
            }
        ForkLinkPrewarm.resolvedFor(originalUrl)?.let { resolved ->
            try {
                return upstream.open(dataSpec.withUri(Uri.parse(resolved)))
                    .also { logOpen("resolved", dataSpec, it, startedAt) }
            } catch (e: IOException) {
                // The loader was cancelled (seek, track change, release): not the link's fault.
                if (isCancellation(e)) throw e
                Log.w("ForkPrewarm", "resolved url failed (${e.javaClass.simpleName}), back to the original link")
                ForkLinkPrewarm.forget(originalUrl)
                runCatching { upstream.close() }
            }
        }
        val length = upstream.open(dataSpec)
        val landedOn = upstream.uri?.toString()
        ForkDeadLinks.placeholderName(landedOn)?.let { name ->
            // AIOStreams answered with its placeholder video: fail the source so the upstream
            // "fallback to next source" can take over, and keep autoplay away from it.
            Log.w("ForkPrewarm", "PLACEHOLDER $name while opening: link marked dead")
            ForkDeadLinks.markDead(originalUrl)
            runCatching { upstream.close() }
            throw ForkPlaceholderException(ForkDeadLinks.describe(name))
        }
        landedOn?.let { ForkLinkPrewarm.remember(originalUrl, it) }
        logOpen("original", dataSpec, length, startedAt)
        return length
    }

    companion object {
        private const val DIAG_TAG = "ForkDS"

        internal fun isCancellation(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is java.io.InterruptedIOException && cause !is java.net.SocketTimeoutException) return true
                if (cause is InterruptedException) return true
                cause = cause.cause
            }
            return Thread.currentThread().isInterrupted
        }
        private val nextDsId = java.util.concurrent.atomic.AtomicInteger()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val originalUrl: String
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ForkPrewarmedDataSource(upstream.createDataSource(), originalUrl)
    }
}
