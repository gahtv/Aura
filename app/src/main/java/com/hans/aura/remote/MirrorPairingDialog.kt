package com.hans.aura.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * On-TV consent prompt for a device that wants to control Aura.
 *
 * Modelled on the USB-debugging dialog: it names the requesting device, defaults
 * focus to Allow, and BACK means deny. Only Allow mints a token.
 */

/**
 * Draws the consent prompt whenever a device is asking. Must be composed on
 * EVERY branch of AppRoot — including the signed-out login screen, which
 * otherwise returns before the overlay exists.
 */
@Composable
fun MirrorPairingHost() {
    val request by MirrorServer.pairing.collectAsState()
    request?.let { asking ->
        MirrorPairingOverlay(
            deviceName = asking.name,
            onAllow = { MirrorServer.answerPairing(true) },
            onDeny = { MirrorServer.answerPairing(false) }
        )
    }
}

@Composable
fun MirrorPairingOverlay(
    deviceName: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    BackHandler { onDeny() }

    val allowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The focus requester is not attached on the first frame.
        delay(120)
        runCatching { allowFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF171512))
                .border(1.dp, Color(0xFF2E2921), RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text(
                "Allow remote control?",
                color = Color(0xFFF2ECE1),
                fontSize = 26.sp
            )
            Text(
                deviceName,
                color = Color(0xFFE8A44C),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "wants to control playback, the queue and navigation on this device.",
                color = Color(0xFF9D948A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp)
            )

            Row(
                Modifier.padding(top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PairingButton("Deny", focusRequester = null, onClick = onDeny)
                PairingButton("Allow", focusRequester = allowFocus, onClick = onAllow)
            }
        }
    }
}

@Composable
private fun PairingButton(
    label: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        Modifier
            .clip(shape)
            .background(if (focused) Color(0xFF3E362D) else Color(0xFF221D18))
            .then(
                if (focused) Modifier.border(3.dp, Color(0xFFE8A44C), shape)
                else Modifier.border(1.dp, Color(0xFF2E2921), shape)
            )
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 14.dp)
    ) {
        Text(label, color = Color(0xFFF2ECE1), fontSize = 17.sp)
    }
}
