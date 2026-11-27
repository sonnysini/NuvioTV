package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Fork: one row of tags for the picked source, used by the detail page prefetch pill and by
 * the player loading screen. Order: the stream's own badges (imported badge pack), then the
 * file size, then the add-on name. Size and add-on are drawn as outlined chips that match
 * the white-outlined badges. Kept in its own file so upstream rebases stay simple.
 */
data class ForkSourceTagInfo(
    val sourceName: String?,
    val badges: List<StreamBadge>,
    val fileSizeBytes: Long?
)

private data class TagItem(val id: String, val badge: StreamBadge?, val text: String?)

private val TagShape = RoundedCornerShape(5.dp)
private const val TAG_STAGGER_MS = 140L
private const val TAG_ENTER_MS = 320

@Composable
fun ForkSourceTagRow(
    info: ForkSourceTagInfo,
    showFileSize: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val items = remember(info, showFileSize) {
        buildList {
            info.badges.filter { it.imageURL.isNotBlank() }.forEachIndexed { i, badge ->
                add(TagItem("b$i:${badge.imageURL}", badge, null))
            }
            info.fileSizeBytes?.takeIf { showFileSize && it > 0 }?.let { bytes ->
                add(TagItem("size", null, formatForkFileSize(bytes)))
            }
            info.sourceName?.takeIf { it.isNotBlank() }?.let { name ->
                add(TagItem("src:$name", null, name))
            }
        }
    }
    if (items.isEmpty()) return
    val slide = with(LocalDensity.current) { 8.dp.toPx() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                // Tags enter one by one; a tag already shown (same id) does not animate again.
                val progress = remember { Animatable(if (animate) 0f else 1f) }
                LaunchedEffect(Unit) {
                    if (progress.value < 1f) {
                        delay(index * TAG_STAGGER_MS)
                        progress.animateTo(1f, tween(TAG_ENTER_MS, easing = FastOutSlowInEasing))
                    }
                }
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = progress.value
                        translationX = -slide * (1f - progress.value)
                    }
                ) {
                    if (item.badge != null) StreamImportedBadgeChip(badge = item.badge)
                    else ForkTextTag(item.text.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun ForkTextTag(text: String) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .border(1.25.dp, Color.White, TagShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1
        )
    }
}

/** Same rounding as the stream list's size badge, Italian decimal comma. */
fun formatForkFileSize(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        "${(kotlin.math.round(gib * 10.0) / 10.0).toString().replace('.', ',')} GB"
    } else {
        "${kotlin.math.round(bytes.toDouble() / (1024.0 * 1024.0)).toInt()} MB"
    }
}

/**
 * Remembers which source each playback URL came from, so the player loading screen can show
 * the same tags. Filled when the stream screen hands a stream to the player and by the
 * detail page prefetch (which also covers the "reuse last link" path, where no Stream exists).
 */
object ForkPlaybackSources {
    private const val MAX = 8
    private val byUrl = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX
    }

    private class Entry(val info: ForkSourceTagInfo, val stream: Stream?)

    fun remember(url: String?, stream: Stream) {
        if (url.isNullOrBlank()) return
        val info = ForkSourceTagInfo(stream.addonName, stream.badges, stream.behaviorHints?.videoSize)
        synchronized(byUrl) {
            // Keep badges already known for this URL if the new stream has none.
            val previous = byUrl[url]?.info
            val merged = if (info.badges.isEmpty() && previous != null && previous.badges.isNotEmpty()) {
                info.copy(badges = previous.badges)
            } else info
            byUrl[url] = Entry(merged, stream)
        }
    }

    fun remember(url: String?, info: ForkSourceTagInfo) {
        if (url.isNullOrBlank()) return
        synchronized(byUrl) {
            val previous = byUrl[url]
            if (info.badges.isEmpty() && previous != null && previous.info.badges.isNotEmpty()) return
            byUrl[url] = Entry(info, previous?.stream)
        }
    }

    internal fun lookup(url: String?): Pair<ForkSourceTagInfo, Stream?>? {
        if (url.isNullOrBlank()) return null
        return synchronized(byUrl) { byUrl[url]?.let { it.info to it.stream } }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ForkSourceTagsEntryPoint {
    fun streamBadgePresentation(): StreamBadgePresentation
    fun streamBadgeSettingsDataStore(): StreamBadgeSettingsDataStore
}

/** Tags under "Avvio flusso…" on the player loading screen. Nothing if the URL is unknown. */
@Composable
fun ForkLoadingSourceTags(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<Pair<ForkSourceTagInfo, Boolean>?>(null, url) {
        val (info, stream) = ForkPlaybackSources.lookup(url) ?: return@produceState
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ForkSourceTagsEntryPoint::class.java)
        val showSize = runCatching { entry.streamBadgeSettingsDataStore().settings.first().showFileSizeBadges }
            .getOrDefault(true)
        // A stream picked straight from the add-on results has no badges yet: apply the badge pack.
        val badged = if (info.badges.isEmpty() && stream != null) {
            runCatching {
                entry.streamBadgePresentation()
                    .apply(listOf(AddonStreams(addonName = stream.addonName, addonLogo = null, streams = listOf(stream))))
                    .firstOrNull()?.streams?.firstOrNull()?.badges
            }.getOrNull().orEmpty()
        } else info.badges
        value = info.copy(badges = badged) to showSize
    }
    val current = state ?: return
    ForkSourceTagRow(info = current.first, showFileSize = current.second, animate = true, modifier = modifier)
}
