package com.nodepiazza.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * First-launch consent screen. Shown until [AppState.acceptPolicy] has been recorded; gates the
 * rest of the app so a user can't reach peer discovery or chat without agreeing to the rules.
 */
@Composable
fun PolicyGate(onAccept: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text(
            "nodepiazza connects you with people who are physically nearby and lets you chat. " +
                "It has no servers and no accounts — your data stays on your device and is " +
                "shared directly with nearby devices over Bluetooth.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))

        Text("You agree that:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Rule("You are 18 or older.")
        Rule(
            "You will not use nodepiazza to harass, threaten, or abuse others, or to send hate " +
                "speech, spam, or sexually explicit content to people who haven't asked for it.",
        )
        Rule(
            "Child sexual abuse or exploitation material is never tolerated and will be reported " +
                "to the authorities.",
        )
        Rule(
            "You can block any user instantly, and report a user to the developer for review, " +
                "from the menu in any chat.",
        )
        Spacer(Modifier.height(20.dp))

        Text(
            "By continuing you confirm you have read and accept the Privacy Policy and the " +
                "Acceptable Use Policy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I agree")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Rule(text: String) {
    Column {
        Text(
            "•  $text",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }
}
