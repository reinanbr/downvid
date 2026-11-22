package com.downvid.downvid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.downvid.downvid.core.Settings

const val DISCLAIMER_TEXT =
    "Use DownVid only with content that is your own, public, or that you are authorized to download, " +
        "respecting each platform's terms of service and copyright. You are responsible " +
        "for how you use the downloaded files."

/** First-use notice; also opened from Settings. */
@Composable
fun DisclaimerDialog(onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { DownVidLogo(56.dp) },
        title = { Text("Before you start") },
        text = { Text(DISCLAIMER_TEXT) },
        confirmButton = {
            Button(
                onClick = {
                    Settings.update { it.copy(disclaimerAccepted = true) }
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("I understand") }
        },
    )
}
