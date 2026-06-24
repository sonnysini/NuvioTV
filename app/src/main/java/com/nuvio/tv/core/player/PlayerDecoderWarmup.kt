package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prewarm (Fase D — decoder warm-up): the network/CDN is not the bottleneck on a
 * TV stick — the slow part of a cold "avvio flusso" is the hardware video decoder
 * spinning up and decoding the first frame. This builds a short-lived, **isolated**
 * ExoPlayer that renders the first frame of the resolved stream to an off-screen
 * surface (muted), then releases everything immediately.
 *
 * Effect when the user then opens the real player: the HW codec/driver is already
 * exercised, the debrid CDN edge is primed and the container is already demuxed, so
 * the real player reaches first frame markedly faster.
 *
 * Design constraints (regression-free, single-decoder safe):
 *  - Fire-and-forget: releases as soon as the first frame is rendered (or on error /
 *    after [WARM_TIMEOUT_MS]), so the single hardware decoder on the stick is free
 *    again well before the user presses play. No instance is handed off and the real
 *    player keeps its own fully-configured pipeline untouched.
 *  - Only one warm session at a time; a new request releases the previous one first.
 *  - All player access happens on the main thread (ExoPlayer's default looper).
 *  - Only meaningful for the ExoPlayer engine; the MPV path keeps the lighter
 *    connection-only warm-up (Fase B). The caller is responsible for that gate.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerDecoderWarmup @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: WarmSession? = null

    private class WarmSession(
        val player: ExoPlayer,
        val surface: Surface,
        val surfaceTexture: SurfaceTexture,
        val listener: Player.Listener,
        var timeoutJob: Job? = null,
    )

    /**
     * Decodes the first frame of [url] on an off-screen surface, then releases.
     * Safe to call repeatedly; the previous warm session is torn down first so the
     * stick never has more than one warm decoder alive.
     */
    fun warm(url: String, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return
        mainScope.launch {
            releaseInternal()
            runCatching { startWarm(url, headers) }
                .onFailure {
                    Log.d(TAG, "warm start failed: ${it.message}")
                    releaseInternal()
                }
        }
    }

    /** Releases any in-flight warm session (call when leaving the detail screen). */
    fun cancel() {
        mainScope.launch { releaseInternal() }
    }

    private fun startWarm(url: String, headers: Map<String, String>) {
        val dataSourceFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(headers)
        // Tiny playback buffer: we only need the first frame; we release right after.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                250,
                500,
            )
            .build()
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        // Off-screen surface (single-buffer mode, no GL context needed) so the
        // hardware decoder has somewhere to render the first frame.
        val surfaceTexture = SurfaceTexture(false)
        val surface = Surface(surfaceTexture)

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Log.d(TAG, "first frame decoded — decoder warm, releasing")
                releaseInternal()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.d(TAG, "warm error: ${error.errorCodeName}")
                releaseInternal()
            }
        }

        player.addListener(listener)
        player.setVideoSurface(surface)
        player.volume = 0f
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()

        val session = WarmSession(player, surface, surfaceTexture, listener)
        session.timeoutJob = mainScope.launch {
            delay(WARM_TIMEOUT_MS)
            Log.d(TAG, "warm timeout — releasing")
            releaseInternal()
        }
        current = session
    }

    private fun releaseInternal() {
        val session = current ?: return
        current = null
        session.timeoutJob?.cancel()
        runCatching { session.player.removeListener(session.listener) }
        runCatching { session.player.setVideoSurface(null) }
        runCatching { session.player.release() }
        runCatching { session.surface.release() }
        runCatching { session.surfaceTexture.release() }
    }

    companion object {
        private const val TAG = "DecoderWarmup"
        private const val WARM_TIMEOUT_MS = 6_000L
    }
}
