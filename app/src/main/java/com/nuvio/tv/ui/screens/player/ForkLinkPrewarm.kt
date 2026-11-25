package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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

    private data class Entry(val resolvedUrl: String, val atMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun resolvedFor(url: String): String? {
        val entry = entries[url] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
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

    /** Follows the link's redirects with the player's own client; never throws. */
    suspend fun prewarm(rawUrl: String, rawHeaders: Map<String, String>?) {
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(rawUrl, rawHeaders)
        val url = request.url
        if (!isPrewarmable(url) || resolvedFor(url) != null) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            runInterruptible(Dispatchers.IO) {
                val client = PlayerPlaybackNetworking.createHttpClient(request.headers)
                val call = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
                    if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                    }
                }.build()
                client.newCall(call).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val redirected = canonical(finalUrl) != canonical(url)
                    Log.i(
                        TAG,
                        "PREWARM code=${response.code} redirected=$redirected " +
                            "ms=${SystemClock.elapsedRealtime() - startedAt} host=${response.request.url.host}"
                    )
                    if (response.isSuccessful && redirected) remember(url, finalUrl)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "PREWARM failed after ${SystemClock.elapsedRealtime() - startedAt}ms: ${e.javaClass.simpleName}")
        }
    }

    private fun isPrewarmable(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (PlayerMediaSourceFactory.isLoopbackUrl(url)) return false
        val path = parsed.encodedPath.lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    private fun canonical(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url
}

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

    override fun supportsByteBufferRead(): Boolean =
        upstream is androidx.media3.common.ByteBufferDataReader && upstream.supportsByteBufferRead()

    override fun read(buffer: java.nio.ByteBuffer, length: Int): Int =
        (upstream as androidx.media3.common.ByteBufferDataReader).read(buffer, length)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.toString() != originalUrl) return upstream.open(dataSpec)
        ForkLinkPrewarm.resolvedFor(originalUrl)?.let { resolved ->
            try {
                return upstream.open(dataSpec.withUri(Uri.parse(resolved)))
            } catch (e: IOException) {
                Log.w("ForkPrewarm", "resolved url failed (${e.javaClass.simpleName}), back to the original link")
                ForkLinkPrewarm.forget(originalUrl)
                runCatching { upstream.close() }
            }
        }
        val length = upstream.open(dataSpec)
        upstream.uri?.toString()?.let { ForkLinkPrewarm.remember(originalUrl, it) }
        return length
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val originalUrl: String
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ForkPrewarmedDataSource(upstream.createDataSource(), originalUrl)
    }
}
