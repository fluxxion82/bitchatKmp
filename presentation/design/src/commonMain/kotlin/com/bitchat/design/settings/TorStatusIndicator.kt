package com.bitchat.design.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TorStatusIndicator(
    running: Boolean,
    bootstrapPercent: Int,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    val statusColor = when {
        running && bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
        running -> Color(0xFFFF9500)  // Orange
        else -> Color(0xFFFF3B30)  // Red
    }

    Surface(
        color = statusColor,
        shape = CircleShape,
        modifier = modifier.size(8.dp)
    ) {}
}
