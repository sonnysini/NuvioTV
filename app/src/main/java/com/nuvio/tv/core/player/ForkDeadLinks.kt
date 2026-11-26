package com.nuvio.tv.core.player

import android.os.SystemClock
import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork (sonnysini): links that turned out to be an AIOStreams placeholder video.
 *
 * When a debrid link cannot be served (torrent not cached any more, download failed, no matching
 * file, rate limit...) AIOStreams answers with a 307 redirect to a short video under `/static/`
 * that explains the problem. The player plays that video fine, so neither autoplay nor the
 * upstream "fallback to next source" ever notices. Links seen redirecting there are kept here for
 * a while and left out of the autoplay pick, so the next matching source is chosen instead.
 */
object ForkDeadLinks {
    /** Matches AIOStreams' own availability cache, after which a link may work again. */
    private const val TTL_MS = 30 * 60_000L
    private const val MAX_ENTRIES = 64

    private val placeholderPath = Regex(
        "/static/(downloading|download_failed|no_matching_file|payment_required|" +
            "store_limit_exceeded|content_proxy_limit_reached|unavailable_for_legal_reasons|" +
            "4\\d\\d|5\\d\\d)\\.mp4",
        RegexOption.IGNORE_CASE
    )

    private val dead = ConcurrentHashMap<String, Long>()

    /** The placeholder name ("downloading", "429"...) when [resolvedUrl] is one, else null. */
    fun placeholderName(resolvedUrl: String?): String? {
        if (resolvedUrl.isNullOrBlank()) return null
        val path = resolvedUrl.substringBefore('?').substringBefore('#')
        val match = placeholderPath.find(path) ?: return null
        // The whole path must end with the placeholder, not just contain it.
        if (match.range.last != path.lastIndex) return null
        return match.groupValues[1].lowercase()
    }

    fun markDead(vararg urls: String?) {
        if (dead.size >= MAX_ENTRIES) dead.clear()
        val now = SystemClock.elapsedRealtime()
        urls.filterNotNull().filter { it.isNotBlank() }.forEach { dead[it] = now }
    }

    fun isDead(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val at = dead[url] ?: return false
        if (SystemClock.elapsedRealtime() - at > TTL_MS) {
            dead.remove(url)
            return false
        }
        return true
    }

    fun isDead(stream: Stream): Boolean = isDead(stream.getStreamUrl())

    /** Drops dead links; returns the same list when there are none. */
    fun filterAlive(streams: List<Stream>): List<Stream> =
        if (dead.isEmpty()) streams else streams.filterNot { isDead(it) }

    /** User-facing text for the player error shown when no fallback takes over. */
    fun describe(name: String): String = when (name) {
        "downloading" -> "Fonte non pronta: il torrent è in download sul servizio debrid"
        "download_failed" -> "Fonte non disponibile: download fallito sul servizio debrid"
        "no_matching_file" -> "Fonte non disponibile: nessun file corrispondente nel torrent"
        "429" -> "Fonte non disponibile: troppe richieste al servizio debrid (429)"
        else -> "Fonte non disponibile (AIOStreams: $name)"
    }
}
