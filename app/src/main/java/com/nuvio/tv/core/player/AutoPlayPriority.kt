package com.nuvio.tv.core.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork feature (sonnysini): cascading autoplay priority.
 *
 * Every addon / plugin can be given a priority level (1 = highest). Autoplay first
 * applies the normal mode (first stream / regex) only to the level-1 sources, then
 * level 2, and so on. Sources without a level come last, in the upstream order.
 * Several sources can share a level (e.g. the same addon on two servers).
 *
 * Kept in its own file and its own SharedPreferences so upstream rebases stay simple.
 */
object AutoPlayPriority {
    const val MAX_LEVEL = 9
    private const val PREFS = "fork_autoplay_priority"
    private const val SEP = '\u0001'
    private const val UNRANKED = Int.MAX_VALUE

    private var prefs: SharedPreferences? = null
    private val _levels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val levels: StateFlow<Map<String, Int>> = _levels.asStateFlow()

    val isActive: Boolean get() = _levels.value.isNotEmpty()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _levels.value = decode(p.getStringSet(KEY, emptySet()).orEmpty())
    }

    fun levelOf(sourceName: String): Int = _levels.value[sourceName] ?: UNRANKED

    /** null or 0 removes the level. */
    fun setLevel(sourceName: String, level: Int?) {
        val updated = _levels.value.toMutableMap()
        if (level == null || level <= 0) updated.remove(sourceName) else updated[sourceName] = level.coerceAtMost(MAX_LEVEL)
        _levels.value = updated
        prefs?.edit()?.putStringSet(KEY, encode(updated))?.apply()
    }

    fun clear() {
        _levels.value = emptyMap()
        prefs?.edit()?.remove(KEY)?.apply()
    }

    /**
     * Runs [pick] on each priority level in turn and returns the first hit.
     * Each source keeps its own order (e.g. AIOStreams already sorts best-first):
     * inside a level [pick] runs on every source separately, then among those
     * per-source picks the best labelled quality wins (ties: upstream order).
     * Without configured levels it is exactly `pick(candidates)` (upstream behaviour).
     */
    fun cascade(candidates: List<Stream>, pick: (List<Stream>) -> Stream?): Stream? {
        if (!isActive || candidates.isEmpty()) return pick(candidates)
        val byLevel = candidates.groupBy { levelOf(it.addonName) }
        for (level in byLevel.keys.sorted()) {
            val perSource = byLevel.getValue(level).groupBy { it.addonName }.values.mapNotNull(pick)
            perSource.maxByOrNull { qualityRank(it) }?.let { return it }
        }
        return null
    }

    private val QUALITY_TIERS = listOf(
        4 to Regex("""(?<![a-z0-9])(2160p|4k|uhd)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        3 to Regex("""(?<![a-z0-9])(1440p|2k|qhd)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        2 to Regex("""(?<![a-z0-9])(1080p|fhd)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        1 to Regex("""(?<![a-z0-9])720p(?![a-z0-9])""", RegexOption.IGNORE_CASE)
    )

    /** Quality declared in the stream label: 4 = 2160p/4K … 1 = 720p, 0 = lower/unknown. */
    fun qualityRank(stream: Stream): Int {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        return QUALITY_TIERS.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first ?: 0
    }

    /**
     * True when [selected] can be played before every source has answered: all sources
     * with the same or a higher priority have responded and none of their torrents is
     * still waiting for a debrid cache check. Unranked picks always wait (upstream).
     */
    fun isSettled(selected: Stream, allStreams: List<Stream>, pendingSourceNames: Collection<String>): Boolean {
        if (!isActive) return false
        val level = levelOf(selected.addonName)
        if (level == UNRANKED) return false
        if (pendingSourceNames.any { levelOf(it) <= level }) return false
        return allStreams.none { stream ->
            levelOf(stream.addonName) <= level &&
                stream.debridCacheStatus?.state == StreamDebridCacheState.CHECKING
        }
    }

    private const val KEY = "levels"

    private fun encode(levels: Map<String, Int>): Set<String> =
        levels.mapTo(linkedSetOf()) { (name, level) -> "$level$SEP$name" }

    private fun decode(raw: Set<String>): Map<String, Int> = raw.mapNotNull { entry ->
        val idx = entry.indexOf(SEP)
        if (idx <= 0) return@mapNotNull null
        val level = entry.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
        entry.substring(idx + 1) to level
    }.toMap()
}
