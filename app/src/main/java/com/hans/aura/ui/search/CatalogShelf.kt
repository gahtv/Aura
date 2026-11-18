package com.hans.aura.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.hans.aura.data.AuthStore
import com.hans.aura.data.CatalogHit
import com.hans.aura.ui.common.AtmosChip
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * "From the catalog": albums the index can import but the library does not hold yet.
 *
 * Vertical list, one focus stop per row (the whole row is the clickable — there
 * is no focusable child inside it), so D-pad up/down walks rows and OK imports.
 * Only rendered when [com.hans.aura.data.MusicSource.supportsCatalogImport] is
 * true; the server-backed build never composes this.
 */
@Composable
fun CatalogShelf(
    hits: List<CatalogHit>,
    serverUrl: String,
    imports: Map<String, CatalogImport>,
    onImport: (CatalogHit) -> Unit
) {
    if (hits.isEmpty()) return
    Text(
        "From the catalog", fontSize = 20.sp, color = TextPrimary,
        modifier = Modifier.padding(horizontal = 56.dp, vertical = 6.dp)
    )
    Text(
        "Albums the library doesn't have yet \u2014 OK imports one.",
        fontSize = 13.sp, color = TextSecondary,
        modifier = Modifier.padding(horizontal = 56.dp)
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        hits.forEach { hit ->
            CatalogHitRow(
                hit = hit,
                serverUrl = serverUrl,
                job = imports[hit.id],
                onImport = onImport
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CatalogHitRow(
    hit: CatalogHit,
    serverUrl: String,
    job: CatalogImport?,
    onImport: (CatalogHit) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val busy = job?.running == true
    // Imported (or mid-import) rows keep their state visible but stop reacting to
    // OK; a failed one is retryable. Whether an Atmos version exists is the index's call —
    // search metadata is unreliable at album level, so the row never pre-judges it.
    val actionable = !busy && job?.failed != true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceHigh else SurfaceHigh.copy(alpha = 0.35f))
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier.border(1.dp, Border, shape))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = actionable) { onImport(hit) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Border)
        ) {
            val art = hit.artworkUrl ?: AuthStore.imageUrl(serverUrl, hit.id, null, maxWidth = 240)
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = hit.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Text(
                    hit.title.take(1).uppercase(),
                    color = TextSecondary,
                    fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.title,
                    color = if (actionable || focused) TextPrimary else TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hit.hasAtmos) {
                    Spacer(Modifier.width(8.dp))
                    AtmosChip()
                }
            }
            Text(
                hit.subtitle.ifBlank { "Unknown artist" },
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = job?.label ?: "Import",
            color = when {
                job?.failed == true -> TextSecondary
                busy -> TextSecondary
                focused -> Accent
                else -> TextPrimary
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
