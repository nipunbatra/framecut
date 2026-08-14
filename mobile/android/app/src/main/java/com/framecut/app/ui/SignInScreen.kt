package com.framecut.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framecut.app.AuthPhase
import com.framecut.app.AuthState
import com.framecut.app.Config

@Composable
fun SignInScreen(
    state: AuthState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("FrameCut", style = MaterialTheme.typography.displaySmall)
        Text(
            "Trim Google Drive videos on this device and save the result straight back to Drive.",
            style = MaterialTheme.typography.bodyLarge,
        )

        when (state.phase) {
            AuthPhase.UNCONFIGURED -> UnconfiguredCard()

            AuthPhase.RESTORING -> {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text("Restoring your session…", style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                Button(onClick = onSignIn, enabled = !state.signingIn) {
                    Text(if (state.signingIn) "Opening browser…" else "Sign in with Google")
                }
                Text(
                    "You are asked to sign in once. FrameCut keeps a refresh token in its own " +
                        "private storage so it can renew access silently afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnconfiguredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OAuth client is not configured", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set OAUTH_CLIENT_ID in Config.kt to a Google OAuth client of type " +
                    "\"Android\", then rebuild. The manifest redirect scheme is derived from " +
                    "it automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Current value: ${Config.OAUTH_CLIENT_ID}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
