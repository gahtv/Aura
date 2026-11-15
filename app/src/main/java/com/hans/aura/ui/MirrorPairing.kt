package com.hans.aura.ui

import androidx.compose.runtime.Composable

/**
 * Pairing UI entry points the app already composes.
 *
 * The prompt itself lives in `com.hans.aura.remote.MirrorPairingDialog`; these
 * shims keep the existing package/signature so AppRoot and the signed-out login
 * branch compile unchanged.
 */
@Composable
fun MirrorPairingHost() = com.hans.aura.remote.MirrorPairingHost()

@Composable
fun MirrorPairingOverlay(
    deviceName: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) = com.hans.aura.remote.MirrorPairingOverlay(deviceName, onAllow, onDeny)
