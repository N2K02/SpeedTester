package com.speedtester.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Separate live and completed phase readings; never mistake a missing rate for zero. */
internal object SpeedRatePresentation {
    fun shown(testing: Boolean, completedPhase: Double?, liveMbps: Float, previous: Double?): Double? {
        val candidate = if (testing) completedPhase ?: liveMbps.takeIf { it > 0f }?.toDouble() else previous
        return candidate?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun format(mbps: Double?): String =
        if (mbps == null || !mbps.isFinite() || mbps < 0.0) "—"
        else String.format(Locale.US, "%.1f", mbps)
}

private val downloadTint = Color(0xFF29DCEB)
private val uploadTint = Color(0xFFAF82F7)

@Composable
private fun RateCard(
    label: String, value: Double?, icon: ImageVector, tint: Color, modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.25f)),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.07f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = tint, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(SpeedRatePresentation.format(value),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                Text("Mbps", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
internal fun SpeedRateCards(download: Double?, upload: Double?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RateCard("Download", download, Icons.Default.ArrowDownward, downloadTint, Modifier.weight(1f))
        RateCard("Upload", upload, Icons.Default.ArrowUpward, uploadTint, Modifier.weight(1f))
    }
}
