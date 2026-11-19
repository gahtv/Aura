@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.hans.aura.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The open Quick Connect panel: the code to read out, and what to poll for. */
private data class QuickConnectPanel(
    val code: String,
    val serverUrl: String,
    /** Null in the debug/demo panel, which never touches the network. */
    val secret: String?,
    val demo: Boolean
)

@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = AuraShapes.of(AuraShapes.lg)

    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(
            width = AuraSizes.focusBorder,
            color = if (focused) AuraColors.CursorRing else Color.Transparent,
            shape = shape
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = AuraTypography.body),
        placeholder = {
            Text(placeholder, color = colors.onSurfaceVariant, fontSize = AuraTypography.body)
        },
        singleLine = true,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AuraColors.FocusedFill,
            unfocusedContainerColor = AuraColors.PillBackgroundRaised,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            cursorColor = colors.primary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}

@Composable
fun LoginScreen(
    app: AuraApp,
    onSignedIn: () -> Unit,
    initialServerUrl: String? = null,
    /** Debug deep link: render the Quick Connect panel with this fake code. */
    debugDemoCode: String? = null,
    /** Debug deep link: start a real Quick Connect against the filled URL. */
    debugStartQuickConnect: Boolean = false
) {
    // Prefilled after "Sign in again" (the server address survives the
    // sign-out); empty after "Change server" / a fresh install.
    var serverUrl by remember { mutableStateOf(initialServerUrl.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var qcPanel by remember { mutableStateOf<QuickConnectPanel?>(null) }
    var qcJob by remember { mutableStateOf<Job?>(null) }
    val cancelFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // With the URL already provided, send the cursor straight to the username
    // field — otherwise the user has to tab past a field they don't need.
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initialServerUrl.isNullOrBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        repeat(8) {
            if (runCatching { usernameFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }

    fun cancelQuickConnect() {
        qcJob?.cancel()
        qcJob = null
        if (qcPanel != null) android.util.Log.i("AURA_QC", "cancelled by user")
        qcPanel = null
    }

    /**
     * Quick Connect: ask the server for a code, show it, and poll until another
     * Jellyfin client authorises it. This is the only way to sign in on a TV
     * without typing a password with a D-pad.
     */
    fun startQuickConnect() {
        if (busy) return
        val server = serverUrl.trim()
        if (server.isBlank()) {
            error = "Enter the server address first."
            return
        }
        busy = true
        error = null
        qcJob = scope.launch {
            val api = app.jellyfin.apiForServer(server)
            val enabled = runCatching { api.quickConnectEnabled() }.getOrElse { e ->
                busy = false
                error = "Could not reach the server. Check the URL: ${e.message}"
                android.util.Log.i("AURA_QC", "enabled check failed: ${e.message}")
                return@launch
            }
            if (!enabled) {
                busy = false
                error = "Quick Connect is off on this server (Dashboard > General > Quick Connect)."
                android.util.Log.i("AURA_QC", "disabled on $server")
                return@launch
            }
            val res = runCatching { api.quickConnectInitiate() }.getOrElse { e ->
                busy = false
                error = "Quick Connect could not start: ${e.message}"
                android.util.Log.i("AURA_QC", "initiate failed: ${e.message}")
                return@launch
            }
            val code = res.code
            if (code.isNullOrBlank() || res.secret.isBlank()) {
                busy = false
                error = "The server returned no Quick Connect code."
                android.util.Log.i("AURA_QC", "initiate returned no code/secret")
                return@launch
            }
            busy = false
            qcPanel = QuickConnectPanel(code = code, serverUrl = server, secret = res.secret, demo = false)
            android.util.Log.i("AURA_QC", "pending code=$code server=$server")

            // Poll for up to ~4 minutes; the user has to pick up another device.
            repeat(120) {
                delay(2000)
                val state = runCatching { api.quickConnectConnect(res.secret) }.getOrNull()
                if (state?.authenticated == true) {
                    android.util.Log.i("AURA_QC", "authorised — exchanging the secret for a token")
                    runCatching { app.jellyfin.signInWithQuickConnect(server, res.secret) }
                        .onSuccess { s ->
                            android.util.Log.i(
                                "AURA_QC",
                                "signed in via quick connect user=${s.userName} host=${com.hans.aura.data.AuthStore.host(s.serverUrl)}"
                            )
                            qcPanel = null
                            onSignedIn()
                        }
                        .onFailure { e ->
                            android.util.Log.i("AURA_QC", "token exchange failed: ${e.message}")
                            qcPanel = null
                            error = "Quick Connect could not finish: ${e.message}"
                        }
                    return@launch
                }
            }
            android.util.Log.i("AURA_QC", "timed out waiting for authorisation")
            qcPanel = null
            error = "Quick Connect timed out. Try again."
        }
    }

    // Debug deep link (aura_debug_screen quickconnect): panel with a fake code,
    // no network, so the layout can be checked headlessly.
    LaunchedEffect(debugDemoCode) {
        if (debugDemoCode != null) {
            qcPanel = QuickConnectPanel(
                code = debugDemoCode,
                serverUrl = serverUrl.ifBlank { "http://server:8096" },
                secret = null,
                demo = true
            )
            android.util.Log.i("AURA_QC", "demo panel code=$debugDemoCode")
        }
    }

    // Debug deep link: --es aura_debug_screen quickconnect --es aura_debug_auth
    // qcstart runs the real initiate/poll flow against the prefilled URL, so the
    // end-to-end path is testable without driving the D-pad through the form
    // (a focused TextField eats arrow keys).
    LaunchedEffect(debugStartQuickConnect) {
        if (debugStartQuickConnect) {
            android.util.Log.i("AURA_QC", "debug start for server=${serverUrl.trim()}")
            delay(400)
            startQuickConnect()
        }
    }

    fun submitPassword() {
        if (busy || serverUrl.isBlank() || username.isBlank()) return
        busy = true
        error = null
        scope.launch {
            runCatching { app.jellyfin.signIn(serverUrl, username, password) }
                .onSuccess { onSignedIn() }
                .onFailure { e ->
                    error = when {
                        e.message?.contains("401") == true -> "Wrong username or password."
                        else -> "Could not reach the server. Check the URL: ${e.message}"
                    }
                }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = AuraSizes.loginHorizontalInset),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Aura",
                style = MaterialTheme.typography.displayLarge,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(AuraSpacing.xs))
            Text(
                text = "Your Jellyfin library, direct to your receiver",
                fontSize = AuraTypography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AuraSpacing.xxl))

            LoginTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = "http://server:8096",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.width(AuraSizes.loginFormWidth)
            )
            Spacer(Modifier.height(AuraSpacing.md))
            LoginTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Username",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.width(AuraSizes.loginFormWidth).focusRequester(usernameFocus)
            )
            Spacer(Modifier.height(AuraSpacing.md))
            LoginTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                modifier = Modifier.width(AuraSizes.loginFormWidth)
            )
            Spacer(Modifier.height(AuraSpacing.lg))

            error?.let {
                Text(text = it, color = AuraColors.ErrorText, fontSize = AuraTypography.bodySmall)
                Spacer(Modifier.height(AuraSpacing.sm))
            }

            Button(
                onClick = { submitPassword() },
                enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.width(AuraSizes.loginFormWidth)
            ) {
                Text(if (busy) "Signing in..." else "Sign in")
            }
            Spacer(Modifier.height(AuraSpacing.sm))
            // Sign in from a phone/computer: no password typed on the TV remote.
            Button(
                onClick = { startQuickConnect() },
                enabled = !busy && serverUrl.isNotBlank(),
                modifier = Modifier.width(AuraSizes.loginFormWidth)
            ) {
                Text("Sign in with a code")
            }
        }

        // Quick Connect panel — screen root, over the form, holding focus.
        qcPanel?.let { panel ->
            BackHandler { cancelQuickConnect() }
            LaunchedEffect(panel) {
                delay(100)
                repeat(6) {
                    if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(100)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AuraColors.DialogScrimSoft),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .width(AuraSizes.loginPanelWidth)
                        .clip(RoundedCornerShape(AuraShapes.lg))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            AuraSizes.hairline,
                            com.hans.aura.ui.theme.Border,
                            RoundedCornerShape(AuraShapes.lg)
                        )
                        // Trap the cursor inside the panel; DOWN must not fall
                        // back into the login form behind the scrim.
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .padding(AuraSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Quick Connect", fontSize = AuraTypography.headline, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(AuraSpacing.sm))
                    Text(
                        "On your phone or computer, open Jellyfin, then Settings \u2192 " +
                            "Quick Connect, and enter this code:",
                        fontSize = AuraTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AuraSpacing.lg))
                    Text(
                        text = panel.code,
                        fontSize = AuraTypography.hero,
                        letterSpacing = 12.sp,
                        color = com.hans.aura.ui.theme.Accent
                    )
                    Spacer(Modifier.height(AuraSpacing.md))
                    Text(
                        panel.serverUrl,
                        fontSize = AuraTypography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AuraSpacing.xs))
                    Text(
                        if (panel.demo) "Demo panel (debug build)" else "Waiting for approval\u2026",
                        fontSize = AuraTypography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AuraSpacing.lg))
                    Button(
                        onClick = { cancelQuickConnect() },
                        modifier = Modifier.focusRequester(cancelFocus)
                    ) { Text("Cancel") }
                }
            }
        }
    }
}
