@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

// Fork feature (sonnysini): settings UI for cascading autoplay priority levels.
// Self-contained (own dialog state, own strings) so the upstream files only need one call.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.AutoPlayPriority
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AutoPlayPriorityRow(
    viewModel: PlaybackSettingsViewModel = hiltViewModel()
) {
    val installedAddonNames by viewModel.installedAddonNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledPluginNames by viewModel.enabledPluginNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val levels by AutoPlayPriority.levels.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }

    SettingsActionRow(
        title = "Priorità fonti (cascata)",
        subtitle = "Prova prima le fonti di livello 1, poi 2, 3… Stesso livello = stessa priorità",
        value = if (levels.isEmpty()) "Off" else "${levels.size} fonti",
        onClick = { open = true }
    )

    if (open) {
        val sources = (installedAddonNames + enabledPluginNames).distinct()
        AutoPlayPriorityDialog(
            sources = sources,
            levels = levels,
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun AutoPlayPriorityDialog(
    sources: List<String>,
    levels: Map<String, Int>,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(sources.isNotEmpty()) {
        if (sources.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }
    // Ranked first (by level), then the rest in upstream order. Order is frozen while the
    // dialog is open so rows don't jump under the focus.
    val ordered = remember(sources) {
        sources.sortedBy { levels[it] ?: Int.MAX_VALUE }
    }

    fun change(name: String, delta: Int) {
        val current = levels[name] ?: 0
        val next = (current + delta).let { if (it > AutoPlayPriority.MAX_LEVEL) 0 else it.coerceAtLeast(0) }
        AutoPlayPriority.setLevel(name, next)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Priorità fonti",
        subtitle = "◀ ▶ cambia livello · OK = livello successivo · — = nessuna priorità (in coda)",
        width = 600.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (sources.isEmpty()) {
                Text(
                    text = "Nessun addon o plugin attivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = NuvioTheme.spacing.xs)
                ) {
                    items(items = ordered, key = { it }) { name ->
                        val level = levels[name]
                        Card(
                            onClick = { change(name, +1) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (name == ordered.first()) Modifier.focusRequester(focusRequester) else Modifier)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionRight -> { change(name, +1); true }
                                        Key.DirectionLeft -> { change(name, -1); true }
                                        else -> false
                                    }
                                },
                            colors = CardDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    color = NuvioTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (level == null) "◀  —  ▶" else "◀  Livello $level  ▶",
                                    color = if (level == null) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            if (levels.isNotEmpty()) {
                Card(
                    onClick = { AutoPlayPriority.clear() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = "Azzera tutte le priorità",
                        color = NuvioTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}
