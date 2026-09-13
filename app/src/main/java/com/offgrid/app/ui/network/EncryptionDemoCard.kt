package com.offgrid.app.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun EncryptionDemoCard(
    transportStatus: String,
    modifier: Modifier = Modifier,
) {
    val active = transportStatus.contains("encrypted", ignoreCase = true) ||
        transportStatus.contains("relay", ignoreCase = true)

    val stage = when {
        transportStatus.contains("Relay hop", ignoreCase = true) -> "DECRYPTED → RE-ENCRYPTED"
        transportStatus.contains("encrypted", ignoreCase = true) -> "ENCRYPTED FOR NEXT HOP"
        else -> "READY FOR ENCRYPTION"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(9.dp),
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {}
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        "ENCRYPTION TRACE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TraceValue("CIPHER", "AES-256-GCM")
                TraceValue("MODE", "HOP-BY-HOP")
                TraceValue("PAYLOAD", if (active) "ENCRYPTED" else "READY")
            }

            Text(
                when {
                    transportStatus.contains("Relay hop", ignoreCase = true) ->
                        "Relay decrypted the incoming hop, then encrypted the same payload with the next hop key."
                    transportStatus.contains("encrypted", ignoreCase = true) ->
                        "Payload encrypted with a fresh IV before it enters the next physical link."
                    else ->
                        "Each physical link uses its own derived AES-256-GCM hop key."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraceValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
