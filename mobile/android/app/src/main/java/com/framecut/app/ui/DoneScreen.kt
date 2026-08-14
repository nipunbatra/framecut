package com.framecut.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.framecut.app.DoneState

@Composable
fun DoneScreen(
    state: DoneState,
    onTrimAnother: () -> Unit,
    onOpenLinkFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(state.headline, style = MaterialTheme.typography.headlineSmall)
        Text(state.detail, style = MaterialTheme.typography.bodyMedium)
        if (state.webViewLink != null) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.webViewLink)))
                }.onFailure { onOpenLinkFailed() }
            }) { Text("Open in Drive") }
        }
        Button(onClick = onTrimAnother) { Text("Trim another video") }
    }
}
