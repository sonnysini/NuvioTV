package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prewarm (Fase A): proactively resolves the auto-play "top" stream for the
 * content shown on the detail screen and stores the playable URL in the same
 * [StreamLinkCacheDataStore] the stream screen already consumes ("reuse last
 * link"). When the user then opens the stream screen the slow debrid resolution
 * has already happened, so auto-play starts instantly.
 *
 * Design constraints (regression-free):
 *  - Only active when the user has "reuse last link" enabled (the cache that
 *    consumes our entry). Otherwise it does nothing.
 *  - Only writes when auto-play would actually select a stream (returns null in
 *    MANUAL mode without a binge group) — so it never changes manual behavior.
 *  - Only prewarms streams that need debrid resolution (the slow path). Direct
 *    URLs are already instant and are left untouched.
 *  - Never overwrites a still-valid cached link (preserves a real "last link").
 */
@Singleton
class StreamPrewarmManager @Inject constructor(
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val directDebridResolver: DirectDebridResolver,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val playerDecoderWarmup: PlayerDecoderWarmup,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentKey: String? = null

    /**
     * Requests a prewarm for the given play target. Safe to call repeatedly:
     * a prewarm already in flight for the same content key is not restarted.
     */
    fun prewarm(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        contentLanguage: String? = null,
        year: String? = null,
    ) {
        if (videoId.isBlank() || type.isBlank()) return
        val cacheKey = "${type.lowercase()}|$videoId"
        if (cacheKey == currentKey && job?.isActive == true) return
        job?.cancel()
        currentKey = cacheKey
        job = scope.launch {
            runCatching {
                prewarmInternal(type, videoId, season, episode, cacheKey, contentLanguage, year)
            }.onFailure { Log.d(TAG, "prewarm failed for $cacheKey: ${it.message}") }
        }
    }

    /** Cancels any in-flight prewarm (call when leaving the detail screen). */
    fun cancel() {
        job?.cancel()
        job = null
        currentKey = null
        playerDecoderWarmup.cancel()
    }

    private suspend fun prewarmInternal(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        cacheKey: String,
        contentLanguage: String?,
        year: String?,
    ) {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.streamPrewarmEnabled) return

        val reuseEnabled = settings.streamReuseLastLinkEnabled
        val ttlMs = settings.streamReuseLastLinkCacheHours.coerceAtLeast(1) * 60L * 60L * 1000L

        // Reuse path: a valid link is already cached, so the slow resolve is done.
        // Still warm its connection/CDN edge (Fase B) so playback reaches first frame
        // faster even when reusing a known-good link.
        if (reuseEnabled) {
            val cached = streamLinkCacheDataStore.getValid(cacheKey, ttlMs)
            if (cached != null) {
                warmPlayback(cached.url, cached.headers, settings.internalPlayerEngine)
                return
            }
        }

        val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        if (installedAddons.isEmpty()) return
        val installedOrder = installedAddons.map { it.displayName }

        var lastData: List<AddonStreams> = emptyList()
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
            ).collect { result ->
                if (result is NetworkResult.Success && result.data.isNotEmpty()) {
                    lastData = result.data
                }
            }
        }
        if (lastData.isEmpty()) return

        val ordered = StreamAutoPlaySelector.orderAddonStreams(lastData, installedOrder)
        val allStreams = ordered.flatMap { it.streams }
        val top = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = allStreams,
            mode = settings.streamAutoPlayMode,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            installedAddonNames = installedOrder.toSet(),
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        ) ?: return

        // Direct URLs are already instant — only the debrid resolve path is worth prewarming.
        if (!directDebridResolver.shouldResolveToPlayableStream(top)) return

        // Re-check: a real playback may have populated the cache while we fetched.
        if (reuseEnabled && streamLinkCacheDataStore.getValid(cacheKey, ttlMs) != null) return

        when (val result = directDebridResolver.resolve(top, season, episode)) {
            is DirectDebridResolveResult.Success -> {
                if (result.url.isNotBlank()) {
                    // Fase A: persist the resolved link for instant "reuse last link" auto-play.
                    if (reuseEnabled) {
                        streamLinkCacheDataStore.save(
                            contentKey = cacheKey,
                            url = result.url,
                            streamName = top.name.orEmpty(),
                            headers = null,
                            filename = result.filename,
                            videoSize = result.videoSize,
                            bingeGroup = top.behaviorHints?.bingeGroup,
                            contentLanguage = contentLanguage,
                            year = year,
                        )
                    }
                    // Warm the resolved link: decode the first frame on ExoPlayer
                    // (Fase D) or just prime the connection/CDN on MPV (Fase B).
                    warmPlayback(result.url, emptyMap(), settings.internalPlayerEngine)
                    Log.d(TAG, "prewarmed $cacheKey via debrid (${top.name})")
                }
            }
            else -> Unit // NotCached / Stale / MissingApiKey / Error -> skip silently
        }
    }

    /**
     * Warms the resolved playback link. On the ExoPlayer engine this decodes the
     * first frame on an off-screen surface (Fase D) so the hardware decoder is hot
     * before play; on MPV it falls back to the lighter connection/CDN warm-up
     * (Fase B), since warming an ExoPlayer decoder would not help the MPV pipeline
     * and would needlessly occupy the stick's single hardware decoder.
     */
    private suspend fun warmPlayback(
        url: String,
        headers: Map<String, String>,
        engine: InternalPlayerEngine,
    ) {
        if (engine == InternalPlayerEngine.EXOPLAYER) {
            playerDecoderWarmup.warm(url, headers)
        } else {
            PlayerPlaybackNetworking.warmConnection(url, headers)
        }
    }

    companion object {
        private const val TAG = "StreamPrewarm"
        private const val FETCH_TIMEOUT_MS = 25_000L
    }
}
