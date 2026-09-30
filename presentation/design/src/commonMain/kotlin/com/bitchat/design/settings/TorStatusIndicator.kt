package com.bitchat.design.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.domain.tor.model.TorState

@Composable
fun TorStatusIndicator(
    running: Boolean,
    bootstrapPercent: Int,
    state: TorState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    val statusColor = torStatusIndicatorColor(running, bootstrapPercent, state, isDark)

    Surface(
        color = statusColor,
        shape = CircleShape,
        modifier = modifier.size(8.dp)
    ) {}
}

internal fun torStatusIndicatorColor(
    running: Boolean,
    bootstrapPercent: Int,
    state: TorState,
    isDark: Boolean,
): Color = when {
    state == TorState.ERROR -> Color(0xFFFF3B30)
    running && bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
    running -> Color(0xFFFF9500)
    else -> Color(0xFFFF3B30)
}
