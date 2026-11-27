package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.AutoPlayPriority
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.ui.components.StreamBadgeChips
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork feature (sonnysini): source prefetch on the detail page.
 *
 * While the detail page is open the source search for the Play target starts in the
 * background, with exactly the parameters the stream screen will use, so the stream
 * screen joins the running (or finished) search of [StreamRepository] instead of
 * starting a new one. A compact card in the top-right corner shows the progress and the
 * source autoplay would pick, with the native stream badges; then it folds into a round
 * button next to the hero actions. Only when autoplay is on.
 * Leaving the page drops a search that is still running.
 *
 * Kept in its own file so upstream rebases stay simple.
 */
sealed interface SourcePrefetchState {
    data object Hidden : SourcePrefetchState
    data class Searching(val level: Int?, val preparing: Boolean = false) : SourcePrefetchState
    data class Ready(
        val sourceName: String,
        val badges: List<StreamBadge>,
        val fileSizeBytes: Long?,
        val showFileSizeBadge: Boolean
    ) : SourcePrefetchState
    data object NotFound : SourcePrefetchState
}

private data class PrefetchTarget(
    val type: String,
    val videoId: String,
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

@HiltViewModel
class SourcePrefetchViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val streamBadgePresentation: StreamBadgePresentation,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val serverStreams: ServerStreams
) : ViewModel() {

    private val _state = MutableStateFlow<SourcePrefetchState>(SourcePrefetchState.Hidden)
    val state: StateFlow<SourcePrefetchState> = _state.asStateFlow()

    private var target: PrefetchTarget? = null
    private var bingeGroupOfTarget: String? = null
    private var job: Job? = null
    private var prewarmJob: Job? = null
    // The check only appears once the picked link is also resolved and its tail prefetched.
    private var pendingReady: SourcePrefetchState.Ready? = null
    private var prewarmDone = false
    private var startedAtMs = 0L

    fun start(type: String, videoId: String, contentId: String, season: Int?, episode: Int?) {
        val next = PrefetchTarget(type, videoId, contentId, season, episode)
        if (next == target) return
        val previous = target
        val previousBingeGroup = bingeGroupOfTarget
        target = next
        job?.cancel()
        prewarmJob?.cancel()
        pendingReady = null
        prewarmDone = false
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        previous?.let { cancelSearch(it, previousBingeGroup) }
        _state.value = SourcePrefetchState.Hidden
        job = viewModelScope.launch { run(next) }
    }

    private suspend fun run(t: PrefetchTarget) {
        val settings = playerSettingsDataStore.playerSettings.first()
        // Same gate as the stream screen's direct autoplay flow.
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) return
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
            !com.nuvio.tv.core.player.StreamAutoPlayPolicy.isRegexSelectionConfigured(settings.streamAutoPlayRegex)
        ) return

        val showFileSize = streamBadgeSettingsDataStore.settings.first().showFileSizeBadges
        var cachedLink: com.nuvio.tv.data.local.CachedStreamLink? = null

        // "Reuse last link": the stream screen plays the cached link straight away.
        if (settings.streamReuseLastLinkEnabled) {
            val cached = streamLinkCacheDataStore.getValid(
                contentKey = "${t.type.lowercase()}|${t.videoId}",
                maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
            )
            if (cached != null) {
                cachedLink = cached
                publishReady(
                    SourcePrefetchState.Ready(
                        sourceName = cached.addonName ?: cached.streamName,
                        badges = emptyList(),
                        fileSizeBytes = cached.videoSize,
                        showFileSizeBadge = showFileSize
                    ),
                    url = cached.url,
                    headers = cached.headers
                )
            }
        }

        val isNativeServerRequest = serverStreams.isNativeRequest(t.videoId)
        val installedAddonOrder = if (isNativeServerRequest) {
            emptyList()
        } else {
            addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }
        }
        val serverSourceNames = serverStreams.sources(t.type, t.videoId, t.season, t.episode).map { it.name }
        val preferredServerNames = serverStreams.preferredSourceNames(t.type, t.videoId)
        // Must match the stream screen, it is part of the shared search key.
        val persistedBingeGroup = if (settings.streamAutoPlayPreferBingeGroupForNextEpisode &&
            settings.streamAutoPlayReuseBingeGroup
        ) {
            bingeGroupCacheDataStore.get(t.contentId)
        } else null
        bingeGroupOfTarget = persistedBingeGroup

        val expectedSources = (installedAddonOrder + serverSourceNames).toSet()
        if (cachedLink == null) {
            _state.value = SourcePrefetchState.Searching(currentLevel(expectedSources))
        }

        var lastGroups: List<AddonStreams> = emptyList()
        var shown = cachedLink != null
        // The cached link carries no badges: once the search returns the same link,
        // show it again with the stream's own badges.
        var cachedLinkMatched = cachedLink == null

        suspend fun pick(groups: List<AddonStreams>): Stream? {
            val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder, preferredServerNames)
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = ordered.flatMap { it.streams },
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedAddonOrder.toSet(),
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                selectedPlugins = settings.streamAutoPlaySelectedPlugins,
                preferredBingeGroup = persistedBingeGroup,
                preferBingeGroupInSelection = persistedBingeGroup != null
            )
        }

        suspend fun show(stream: Stream) {
            val badged = withContext(Dispatchers.Default) {
                streamBadgePresentation.apply(
                    listOf(AddonStreams(addonName = stream.addonName, addonLogo = null, streams = listOf(stream)))
                ).firstOrNull()?.streams?.firstOrNull() ?: stream
            }
            publishReady(
                SourcePrefetchState.Ready(
                    sourceName = badged.addonName,
                    badges = badged.badges,
                    fileSizeBytes = badged.behaviorHints?.videoSize,
                    showFileSizeBadge = showFileSize
                ),
                url = stream.getStreamUrl(),
                headers = stream.behaviorHints?.proxyHeaders?.request
            )
            shown = true
        }

        streamRepository.getStreamsFromAllAddons(
            type = t.type,
            videoId = t.videoId,
            season = t.season,
            episode = t.episode,
            priorityBingeGroup = persistedBingeGroup
        ).collect { result ->
            if (result !is NetworkResult.Success) return@collect
            val cached = cachedLink
            if (!cachedLinkMatched && cached != null) {
                result.data.asSequence().flatMap { it.streams.asSequence() }.firstOrNull { stream ->
                    (cached.url.isNotBlank() && stream.url == cached.url) ||
                        (cached.infoHash != null && stream.infoHash.equals(cached.infoHash, ignoreCase = true)) ||
                        (cached.filename != null && stream.behaviorHints?.filename == cached.filename)
                }?.let { match ->
                    cachedLinkMatched = true
                    show(match)
                }
            }
            if (shown) return@collect
            lastGroups = result.data
            val responded = result.data.mapTo(hashSetOf()) { it.addonName }
            val pending = expectedSources - responded
            if (AutoPlayPriority.isActive) {
                val candidate = pick(result.data)
                val allStreams = result.data.flatMap { it.streams }
                if (candidate != null && AutoPlayPriority.isSettled(candidate, allStreams, pending)) {
                    show(candidate)
                    return@collect
                }
            }
            _state.value = SourcePrefetchState.Searching(currentLevel(pending))
        }
        // Search finished (or every result came from the cache).
        if (!shown) {
            val finalPick = pick(lastGroups)
            if (finalPick != null) show(finalPick) else _state.value = SourcePrefetchState.NotFound
        }
    }

    /**
     * Shows [ready] once the first link is prepared for the player (see ForkLinkPrewarm): resolved
     * and its tail prefetched. Until then the card says "Preparo il flusso…". The preparation
     * waits for a short dwell on the page, so scrolling through pages resolves nothing. A later
     * Ready (the cached link getting its badges) only replaces the pending one.
     */
    private fun publishReady(ready: SourcePrefetchState.Ready, url: String?, headers: Map<String, String>?) {
        if (prewarmDone) {
            _state.value = ready
            return
        }
        pendingReady = ready
        if (prewarmJob?.isActive == true) return
        if (url.isNullOrBlank()) {
            prewarmDone = true
            _state.value = ready
            return
        }
        _state.value = SourcePrefetchState.Searching(level = null, preparing = true)
        prewarmJob = viewModelScope.launch {
            val waited = android.os.SystemClock.elapsedRealtime() - startedAtMs
            if (waited < PREWARM_DWELL_MS) delay(PREWARM_DWELL_MS - waited)
            val placeholder = withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
                com.nuvio.tv.ui.screens.player.ForkLinkPrewarm.prewarm(url, headers)
            }
            if (placeholder != null) {
                // AIOStreams placeholder video (torrent not ready...): the link is now marked dead,
                // so the autoplay pick skips it. Drop it from "reuse last link" too and pick again;
                // the finished search is shared, so this sends no new requests to the addons.
                android.util.Log.w("ForkPrewarm", "detail pick was a placeholder ($placeholder), picking the next source")
                target?.let { t ->
                    val key = "${t.type.lowercase()}|${t.videoId}"
                    if (streamLinkCacheDataStore.getValid(key, Long.MAX_VALUE)?.url == url) {
                        streamLinkCacheDataStore.remove(key)
                    }
                }
                pendingReady = null
                prewarmDone = false
                val t = target ?: return@launch
                job?.cancel()
                job = viewModelScope.launch { run(t) }
                return@launch
            }
            prewarmDone = true
            pendingReady?.let { _state.value = it }
        }
    }

    /** Highest-priority level among sources still searching, null without levels. */
    private fun currentLevel(pendingSources: Collection<String>): Int? {
        if (!AutoPlayPriority.isActive) return null
        return pendingSources.map { AutoPlayPriority.levelOf(it) }
            .filter { it <= AutoPlayPriority.MAX_LEVEL }
            .minOrNull()
    }

    private fun cancelSearch(t: PrefetchTarget, bingeGroup: String?) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withContext(NonCancellable) {
                streamRepository.cancelStreamSearch(t.type, t.videoId, t.season, t.episode, bingeGroup)
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        prewarmJob?.cancel()
        target?.let { cancelSearch(it, bingeGroupOfTarget) }
        super.onCleared()
    }
}


// ---------------------------------------------------------------------------------------------
// UI: a compact card in the top-right corner shows the search and the picked source. A moment
// after the pick the card folds into a circle and flies next to the hero actions, where it
// becomes a round action button like the others. Focusing that button opens the card again.
// The card lives in a non-focusable popup, so it never changes the D-pad navigation of the page.
// ---------------------------------------------------------------------------------------------

private val CardHeight = 56.dp
private val CardMarginEnd = 32.dp
private val CardMarginTop = 20.dp
private val CardGlass = Color(0xFF1C1C1E).copy(alpha = 0.72f)
private val CheckGreen = Color(0xFF4CAF50)
private const val READY_HOLD_MS = 2500L
private const val COLLAPSE_MS = 350
private const val FLY_MS = 600
private const val NOT_FOUND_HOLD_MS = 3000L
private const val PREWARM_DWELL_MS = 1500L
private const val PREWARM_TIMEOUT_MS = 10_000L

private enum class Phase { Card, Collapse, Fly, Landed, Gone }

/**
 * Titles whose card already folded into the hero button during this app session, with the last
 * source shown: opening the title again shows the button at once, while the new page checks the
 * link again in the background.
 */
private object LandedTargets {
    private val ready = java.util.concurrent.ConcurrentHashMap<String, SourcePrefetchState.Ready>()
    fun contains(key: String) = ready.containsKey(key)
    fun readyFor(key: String): SourcePrefetchState.Ready? = ready[key]
    fun add(key: String, value: SourcePrefetchState.Ready) { ready[key] = value }
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

@Composable
fun SourcePrefetchInfo(
    type: String,
    videoId: String,
    contentId: String,
    season: Int?,
    episode: Int?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SourcePrefetchViewModel = hiltViewModel()
) {
    LaunchedEffect(type, videoId, contentId, season, episode, enabled) {
        if (enabled) viewModel.start(type, videoId, contentId, season, episode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!enabled) return

    val targetKey = "$type|$videoId|$season|$episode"
    // Process-wide: leaving the page and opening the same title again (a new back-stack entry,
    // so rememberSaveable would start over) shows the button straight away, no fold again.
    var phase by remember(targetKey) {
        mutableStateOf(if (LandedTargets.contains(targetKey)) Phase.Landed else Phase.Card)
    }
    val progress = remember(targetKey) { Animatable(0f) }
    var lastReady by remember(targetKey) { mutableStateOf(LandedTargets.readyFor(targetKey)) }
    LaunchedEffect(state) {
        (state as? SourcePrefetchState.Ready)?.let { ready ->
            lastReady = ready
            if (LandedTargets.contains(targetKey)) LandedTargets.add(targetKey, ready)
        }
    }
    // Shown under the circle for a couple of frames before the popup goes away, so the landing
    // never flashes an empty slot (popup and page are separate windows).
    var buttonUnderCircle by remember(targetKey) { mutableStateOf(false) }

    // Keyed on the kind of state, not the value: a Ready that only refreshes its badges must
    // not restart (and freeze) the fold animation.
    LaunchedEffect(targetKey, state::class) {
        when (state) {
            is SourcePrefetchState.Ready -> if (phase == Phase.Card) {
                delay(READY_HOLD_MS)
                phase = Phase.Collapse
                progress.snapTo(0f)
                progress.animateTo(1f, tween(COLLAPSE_MS, easing = FastOutSlowInEasing))
                phase = Phase.Fly
                progress.snapTo(0f)
                progress.animateTo(1f, tween(FLY_MS, easing = FastOutSlowInEasing))
                buttonUnderCircle = true
                repeat(2) { withFrameNanos { } }
                phase = Phase.Landed
                lastReady?.let { LandedTargets.add(targetKey, it) }
            }
            SourcePrefetchState.NotFound -> if (phase == Phase.Card) {
                delay(NOT_FOUND_HOLD_MS)
                phase = Phase.Gone
            } else if (phase == Phase.Landed) {
                // The source remembered from the last visit is gone: no button.
                lastReady = null
            }
            is SourcePrefetchState.Searching -> if (phase == Phase.Gone) phase = Phase.Card
            SourcePrefetchState.Hidden -> Unit
        }
    }

    var slotBounds by remember { mutableStateOf<Rect?>(null) }
    var cardWidthPx by remember(targetKey) { mutableStateOf(0) }
    var buttonFocused by remember { mutableStateOf(false) }
    val landed = (phase == Phase.Landed || buttonUnderCircle) && lastReady != null

    // The slot at the end of the hero actions: invisible and not focusable until the circle lands.
    PrefetchButton(
        visible = landed,
        onFocusChanged = { buttonFocused = it },
        modifier = modifier.onGloballyPositioned { slotBounds = it.boundsInWindow() }
    )

    val showOverlay = when (phase) {
        Phase.Card -> state !is SourcePrefetchState.Hidden
        Phase.Collapse, Phase.Fly -> true
        Phase.Landed -> buttonFocused
        Phase.Gone -> false
    }
    if (!showOverlay) return

    val configuration = LocalConfiguration.current
    Popup(
        popupPositionProvider = WindowOriginPositionProvider,
        properties = PopupProperties(focusable = false, clippingEnabled = false)
    ) {
        Box(Modifier.size(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)) {
            when (phase) {
                Phase.Card -> AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = CardMarginTop, end = CardMarginEnd)
                        .onSizeChanged { cardWidthPx = it.width },
                    label = "source_prefetch_card"
                ) { current -> CardFor(current) }

                Phase.Collapse, Phase.Fly -> FoldingCircle(
                    phase = phase,
                    t = progress.value,
                    slot = slotBounds,
                    ready = lastReady,
                    cardWidthPx = cardWidthPx
                )

                Phase.Landed -> {
                    val slot = slotBounds
                    val ready = lastReady
                    if (slot != null && ready != null) {
                        ReopenedCard(slot = slot, ready = ready)
                    }
                }

                Phase.Gone -> Unit
            }
        }
    }
}

private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

@Composable
private fun CardFor(state: SourcePrefetchState) {
    when (state) {
        SourcePrefetchState.Hidden -> Box(Modifier.size(0.dp))
        is SourcePrefetchState.Searching -> PrefetchCard(
            indicator = { PrefetchSpinner() },
            title = if (state.preparing) "Preparo il flusso…" else "Cerco fonti…",
            subtitle = state.level?.let { "Livello $it" }
        )
        is SourcePrefetchState.Ready -> PrefetchCard(
            indicator = { CheckBubble(fill = CheckGreen, size = 28.dp) },
            title = readyTitle(state),
            subtitle = null,
            ready = state
        )
        SourcePrefetchState.NotFound -> PrefetchCard(
            indicator = null,
            title = "Nessuna fonte per l'autoplay",
            subtitle = null
        )
    }
}

private fun readyTitle(state: SourcePrefetchState.Ready): String = listOfNotNull(
    state.sourceName,
    state.fileSizeBytes?.takeIf { state.showFileSizeBadge }?.let(::formatFileSize)
).joinToString("  ·  ")

@Composable
private fun PrefetchCard(
    indicator: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
    ready: SourcePrefetchState.Ready? = null,
    modifier: Modifier = Modifier,
    background: Color = CardGlass,
    startPadding: Dp = NuvioTheme.spacing.lg,
    textAlpha: Float = 1f,
    height: Dp = CardHeight
) {
    // Pill shape: it folds naturally into the round hero button.
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .padding(start = startPadding, end = NuvioTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        indicator?.invoke()
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .graphicsLayer { alpha = textAlpha },
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            if (ready != null && ready.badges.isNotEmpty()) {
                // File size is in the title line, so it is never cut off by many badges.
                // StreamBadgeChips fills the available width; measured unbounded here so the
                // pill is as wide as its badges (capped by the column's max width).
                StreamBadgeChips(
                    badges = ready.badges,
                    showFileSizeBadge = false,
                    animate = true,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
                            )
                            val width = placeable.width.coerceAtMost(constraints.maxWidth)
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                        .clipToBounds()
                )
            }
        }
    }
}

/**
 * The card folding into a circle (Collapse) and the circle flying into the hero slot (Fly).
 * Green check only while the card is open ("found"): while folding the green fades and the
 * check turns into a white bolt ("stream ready"), so it can't be mistaken for the hero's
 * own library check and lands with the look of the other hero icons.
 */
@Composable
private fun FoldingCircle(
    phase: Phase,
    t: Float,
    slot: Rect?,
    ready: SourcePrefetchState.Ready?,
    cardWidthPx: Int
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val buttonBg = NuvioTheme.colors.BackgroundCard
    val startPad = NuvioTheme.spacing.lg
    with(density) {
        val screenW = configuration.screenWidthDp.dp.toPx()
        val cardH = CardHeight.toPx()
        val cardRight = screenW - CardMarginEnd.toPx()
        val cardTop = CardMarginTop.toPx()
        val startCenterX = cardRight - cardH / 2f
        val startCenterY = cardTop + cardH / 2f

        if (phase == Phase.Collapse) {
            // The card keeps its right edge and shrinks to a circle; text fades out first,
            // the check slides to the centre, loses its green and turns into the bolt.
            val fullW = if (cardWidthPx > 0) cardWidthPx.toFloat() else cardH
            val w = mix(fullW, cardH, t)
            Box(
                modifier = Modifier
                    .offset { IntOffset((cardRight - w).toInt(), cardTop.toInt()) }
                    .size(w.toDp(), CardHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(lerp(CardGlass, buttonBg, t))
            ) {
                PrefetchCard(
                    indicator = { CheckBubble(fill = lerp(CheckGreen, Color.Transparent, t), size = 28.dp, bolt = t) },
                    title = ready?.let(::readyTitle).orEmpty(),
                    subtitle = null,
                    ready = ready,
                    background = Color.Transparent,
                    startPadding = mix(startPad.toPx(), (cardH - 28.dp.toPx()) / 2f, t).toDp(),
                    textAlpha = (1f - t * 2.5f).coerceIn(0f, 1f),
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                )
            }
        } else {
            val tx = slot?.center?.x ?: startCenterX
            val ty = slot?.center?.y ?: startCenterY
            val size = mix(cardH, NuvioTheme.spacing.xxxl.toPx(), t)
            val cx = mix(startCenterX, tx, t)
            val cy = mix(startCenterY, ty, t)
            Box(
                modifier = Modifier
                    .offset { IntOffset((cx - size / 2f).toInt(), (cy - size / 2f).toInt()) }
                    .size(size.toDp())
                    .clip(CircleShape)
                    .background(buttonBg),
                contentAlignment = Alignment.Center
            ) {
                CheckBubble(fill = Color.Transparent, size = mix(28f, 34f, t).dp, bolt = 1f)
            }
        }
    }
}

/** Opened again while the landed button has focus: grows to the right of the button. */
@Composable
private fun ReopenedCard(slot: Rect, ready: SourcePrefetchState.Ready) {
    val density = LocalDensity.current
    val gap = with(density) { NuvioTheme.spacing.md.toPx() }
    val pillHeight = NuvioTheme.spacing.xxxl
    val cardH = with(density) { pillHeight.toPx() }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    PrefetchCard(
        indicator = null,
        title = readyTitle(ready),
        subtitle = null,
        ready = ready,
        height = pillHeight,
        startPadding = NuvioTheme.spacing.xl,
        modifier = Modifier
            .offset { IntOffset((slot.right + gap).toInt(), (slot.center.y - cardH / 2f).toInt()) }
            .graphicsLayer {
                alpha = reveal.value
                scaleX = mix(0.85f, 1f, reveal.value)
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    )
}

/** The landed button, styled like the hero's own round action buttons. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrefetchButton(visible: Boolean, onFocusChanged: (Boolean) -> Unit, modifier: Modifier) {
    IconButton(
        onClick = {},
        modifier = modifier
            .size(NuvioTheme.spacing.xxxl)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .focusProperties {
                canFocus = visible
                up = FocusRequester.Cancel
            }
            .onFocusChanged { onFocusChanged(it.isFocused) },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = "Fonte pronta",
            modifier = Modifier.size(NuvioTheme.spacing.xl)
        )
    }
}

@Composable
private fun CheckBubble(fill: Color, size: Dp, bolt: Float = 0f) {
    Box(
        modifier = Modifier
            .size(size)
            .background(fill, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Cross-fade check -> bolt (bolt = 0 check only, 1 bolt only).
        if (bolt < 1f) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 1f - bolt),
                modifier = Modifier.size(size * 0.72f)
            )
        }
        if (bolt > 0f) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = Color.White.copy(alpha = bolt),
                modifier = Modifier.size(size * 0.8f)
            )
        }
    }
}

/** Same rounding as the stream list's size badge. */
private fun formatFileSize(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        "${kotlin.math.round(gib * 10.0) / 10.0} GB"
    } else {
        "${kotlin.math.round(bytes.toDouble() / (1024.0 * 1024.0)).toInt()} MB"
    }
}

@Composable
private fun PrefetchSpinner() {
    val rotation by rememberInfiniteTransition(label = "prefetch_spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "prefetch_rotation"
    )
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 3.dp.toPx()
        drawCircle(color = Color.White.copy(alpha = 0.25f), style = Stroke(width = stroke))
        drawArc(
            color = Color.White,
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

