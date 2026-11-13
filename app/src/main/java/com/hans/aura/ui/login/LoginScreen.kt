package com.hans.aura.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(app: AuraApp, onSignedIn: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 120.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Aura",
            fontSize = 64.sp,
            letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your Jellyfin library, direct to your receiver",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        TextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            placeholder = { Text("http://server:8096") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.width(480.dp)
        )
        Spacer(Modifier.height(16.dp))
        TextField(
            value = username,
            onValueChange = { username = it },
            placeholder = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.width(480.dp)
        )
        Spacer(Modifier.height(16.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!busy && serverUrl.isNotBlank() && username.isNotBlank()) {
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
                }
            ),
            modifier = Modifier.width(480.dp)
        )
        Spacer(Modifier.height(24.dp))

        error?.let {
            Text(text = it, color = Color(0xFFE06C5A), fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (busy) return@Button
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
            },
            enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.width(480.dp)
        ) {
            Text(if (busy) "Signing in..." else "Sign in")
        }
    }
}
