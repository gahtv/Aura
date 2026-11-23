package com.hans.aura.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.hans.aura.model.Track
import com.hans.aura.data.MusicSource
import com.hans.aura.data.ProviderLyricMatch
import com.hans.aura.data.ProviderLyricsResult
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun ProviderLyricsDialog(
    source: MusicSource,
    track: Track,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var search by remember { mutableStateOf<ProviderLyricsResult<List<ProviderLyricMatch>>?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var previewOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(source, track.id) { search = source.searchProviderLyrics(track.id) }

    val results = (search as? ProviderLyricsResult.Success)?.value.orEmpty()
    val preview = if (previewOpen) results.getOrNull(selectedIndex) else null
    val back = {
        if (preview == null) onDismiss() else {
            previewOpen = false
            saveError = null
        }
    }
    Dialog(
        onDismissRequest = { if (!saving) back() },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        val backFocus = remember { FocusRequester() }
        val matchFocus = remember { FocusRequester() }
        LaunchedEffect(search, preview, saving) {
            if (!saving) {
                if (preview == null && results.isNotEmpty()) {
                    withFrameNanos { }
                    if (runCatching { matchFocus.requestFocus() }.isSuccess) {
                        return@LaunchedEffect
                    }
                }
                backFocus.requestFocus()
            }
        }

        Column(
            Modifier
                .widthIn(max = AuraSizes.dialogWidth)
                .fillMaxWidth()
                .background(Surface, AuraShapes.dialog)
                .border(AuraSizes.hairline, Border, AuraShapes.dialog)
                .padding(AuraSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.md)
        ) {
            Text(
                if (preview == null) "Find lyrics" else "Preview lyrics",
                fontSize = AuraTypography.title, color = TextPrimary
            )
            Text(
                track.name, fontSize = AuraTypography.bodySmall, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            if (preview != null) {
                Text(preview.label(), fontSize = AuraTypography.bodySmall, color = TextSecondary)
                Text(
                    preview.preview.joinToString("\n"),
                    fontSize = AuraTypography.body, color = TextPrimary,
                    maxLines = 4, overflow = TextOverflow.Ellipsis
                )
                Text(
                    saveError ?: if (saving) "Saving lyrics…" else "Saving changes lyrics for this track on the server.",
                    fontSize = AuraTypography.bodySmall, color = TextSecondary
                )
            } else if (results.isEmpty()) {
                Text(
                    when (search) {
                        null -> "Searching server lyric providers…"
                        ProviderLyricsResult.Forbidden -> "Your account doesn't have permission to manage lyrics."
                        ProviderLyricsResult.Unavailable -> "Lyric search isn't available for this track on this server."
                        ProviderLyricsResult.Failed -> "Couldn't search for lyrics. Close and try again."
                        is ProviderLyricsResult.Success -> "No matches from server lyric providers."
                    },
                    fontSize = AuraTypography.bodySmall, color = TextSecondary
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 216.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
                ) {
                    itemsIndexed(results) { index, match ->
                        Button(
                            onClick = {
                                selectedIndex = index
                                previewOpen = true
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.fillMaxWidth().then(
                                if (index == selectedIndex) Modifier.focusRequester(matchFocus) else Modifier
                            )
                        ) {
                            Column {
                                Text(match.label(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    match.preview.first(), fontSize = AuraTypography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md)) {
                Button(
                    onClick = back,
                    enabled = !saving,
                    modifier = Modifier.focusRequester(backFocus)
                ) { Text(if (preview == null) "Close" else "Back") }

                if (preview != null) {
                    Button(
                        enabled = !saving,
                        onClick = {
                            if (!saving) {
                                saving = true
                                saveError = null
                                scope.launch {
                                    when (source.saveProviderLyrics(track.id, preview.id)) {
                                        is ProviderLyricsResult.Success -> onSaved()
                                        ProviderLyricsResult.Forbidden ->
                                            saveError = "Your account doesn't have permission to manage lyrics."
                                        ProviderLyricsResult.Unavailable ->
                                            saveError = "This match is no longer available. Search again."
                                        ProviderLyricsResult.Failed ->
                                            saveError = "Couldn't save lyrics. Try again."
                                    }
                                    saving = false
                                }
                            }
                        }
                    ) { Text("Save to server") }
                }
            }
        }
    }
}

private fun ProviderLyricMatch.label(): String =
    "$providerName · ${if (isSynced) "Synced" else "Plain text"}"
