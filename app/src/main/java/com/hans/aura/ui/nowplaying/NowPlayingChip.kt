@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Persistent bottom-left chip on the lyrics view: album art + track title +
 * artist. Drawn by the parent OVER the lyric pane and flush with the screen
 * edges (no start/bottom padding) by design.
 */
@Composable
fun BoxScope.NowPlayingChip(app: AuraApp, player: PlayerConnection) {
    val session = app.jellyfin.auth.session ?: return
    val item by player.current.collectAsState()
    val currentItem = item

    Row(
        Modifier
            .align(Alignment.BottomStart)
            .background(AuraColors.CardScrim, RoundedCornerShape(AuraShapes.lg))
            .padding(AuraSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        currentItem?.let { cur ->
            cur.primaryImageTag?.let { tag ->
                AsyncImage(
                    model = AuthStore.imageUrl(session.serverUrl, cur.id, tag, maxWidth = 200),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(AuraSizes.artworkChip)
                        .clip(RoundedCornerShape(AuraShapes.card))
                        .background(SurfaceHigh)
                )
                Spacer(Modifier.width(AuraSpacing.md))
            }
        }
        Column(Modifier.widthIn(max = AuraSizes.npChipMaxWidth)) {
            // Same shrink-then-marquee treatment as the full Now Playing title;
            // the cap keeps the chip from swallowing the lyric pane.
            AutoFitMarqueeText(
                text = currentItem?.name ?: "",
                widthCap = AuraSizes.npChipMaxWidth,
                sizes = AuraTypography.chipTitleFit,
                color = TextPrimary
            )
            androidx.tv.material3.Text(
                currentItem?.artistNames ?: "",
                fontSize = AuraTypography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
