package com.bitchat.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.bitchat.design.mapper.toMiningTimeEstimate
import com.bitchat.design.mapper.toPowDifficultyDescription

@Composable
fun PowDifficultySlider(
    powDifficulty: Int,
    onPowDifficultyChanged: (Int) -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Difficulty",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "$powDifficulty bits • ${powDifficulty.toMiningTimeEstimate()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Slider(
                    value = powDifficulty.toFloat(),
                    onValueChange = { onPowDifficultyChanged(it.toInt()) },
                    valueRange = 0f..32f,
                    steps = 31,
                    colors = SliderDefaults.colors(
                        thumbColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                        activeTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                    )
                )

                Text(
                    text = powDifficulty.toPowDifficultyDescription(),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}
