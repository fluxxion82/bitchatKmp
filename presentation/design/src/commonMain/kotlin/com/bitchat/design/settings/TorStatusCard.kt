package com.bitchat.design.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_last
import bitchatkmp.presentation.design.generated.resources.about_tor_status
import org.jetbrains.compose.resources.stringResource

@Composable
fun TorStatusCard(
    torRunning: Boolean,
    torBootstrapPercent: Int,
    torLastLogLine: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusColor = when {
                        torRunning && torBootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                        torRunning -> Color(0xFFFF9500)
                        else -> Color(0xFFFF3B30)
                    }
                    Surface(
                        color = statusColor,
                        shape = CircleShape,
                        modifier = Modifier.size(10.dp)
                    ) {}
                    val statusLabel = if (torRunning) "Running" else "Disconnected"
                    Text(
                        text = stringResource(
                            Res.string.about_tor_status,
                            statusLabel,
                            torBootstrapPercent
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                }
                if (torLastLogLine.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            Res.string.about_last,
                            torLastLogLine
                        ),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 4
                    )
                }
            }
        }
    }
}
