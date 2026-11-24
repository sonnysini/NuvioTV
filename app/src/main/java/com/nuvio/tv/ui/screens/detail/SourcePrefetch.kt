package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
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
 * starting a new one. Next to the hero actions a small line shows the progress and the
 * source autoplay would pick, with the native stream badges. Only when autoplay is on.
 * Leaving the page drops a search that is still running.
 *
 * Kept in its own file so upstream rebases stay simple.
 */
sealed interface SourcePrefetchState {
    data object Hidden : SourcePrefetchState
    data class Searching(val level: Int?) : SourcePrefetchState
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

    fun start(type: String, videoId: String, contentId: String, season: Int?, episode: Int?) {
        val next = PrefetchTarget(type, videoId, contentId, season, episode)
        if (next == target) return
        val previous = target
        val previousBingeGroup = bingeGroupOfTarget
        target = next
        job?.cancel()
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

        // "Reuse last link": the stream screen plays the cached link straight away.
        if (settings.streamReuseLastLinkEnabled) {
            val cached = streamLinkCacheDataStore.getValid(
                contentKey = "${t.type.lowercase()}|${t.videoId}",
                maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
            )
            if (cached != null) {
                _state.value = SourcePrefetchState.Ready(
                    sourceName = cached.addonName ?: cached.streamName,
                    badges = emptyList(),
                    fileSizeBytes = cached.videoSize,
                    showFileSizeBadge = showFileSize
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
        if (_state.value !is SourcePrefetchState.Ready) {
            _state.value = SourcePrefetchState.Searching(currentLevel(expectedSources))
        }

        var lastGroups: List<AddonStreams> = emptyList()
        var shown = _state.value is SourcePrefetchState.Ready

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
            _state.value = SourcePrefetchState.Ready(
                sourceName = badged.addonName,
                badges = badged.badges,
                fileSizeBytes = badged.behaviorHints?.videoSize,
                showFileSizeBadge = showFileSize
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
            if (shown || result !is NetworkResult.Success) return@collect
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
        target?.let { cancelSearch(it, bingeGroupOfTarget) }
        super.onCleared()
    }
}

private val PrefetchTextShadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 6f)

/**
 * The status line next to the hero actions. Not focusable: it never changes D-pad
 * navigation of the detail page.
 */
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

    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        modifier = modifier,
        label = "source_prefetch"
    ) { current ->
        when (current) {
            SourcePrefetchState.Hidden -> Box(Modifier.size(0.dp))
            is SourcePrefetchState.Searching -> PrefetchLine(
                indicator = { PrefetchSpinner() },
                title = "Cerco fonti…",
                subtitle = current.level?.let { "Livello $it" }
            )
            is SourcePrefetchState.Ready -> PrefetchLine(
                indicator = { PrefetchCheck() },
                title = current.sourceName,
                subtitle = null,
                badges = current
            )
            SourcePrefetchState.NotFound -> PrefetchLine(
                indicator = null,
                title = "Nessuna fonte per l'autoplay",
                subtitle = null
            )
        }
    }
}

@Composable
private fun PrefetchLine(
    indicator: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
    badges: SourcePrefetchState.Ready? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        indicator?.invoke()
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = PrefetchTextShadow
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(shadow = PrefetchTextShadow),
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1
                )
            }
            if (badges != null &&
                (badges.badges.isNotEmpty() || (badges.showFileSizeBadge && badges.fileSizeBytes != null))
            ) {
                StreamBadgeChips(
                    badges = badges.badges,
                    fileSizeBytes = badges.fileSizeBytes,
                    showFileSizeBadge = badges.showFileSizeBadge,
                    animate = true
                )
            }
        }
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
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 2.5.dp.toPx()
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

@Composable
private fun PrefetchCheck() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(12.dp)
        )
    }
}
