package com.bitchat.design.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_mining_pow
import bitchatkmp.presentation.design.generated.resources.cd_pow_enabled
import bitchatkmp.presentation.design.generated.resources.cd_proof_of_work
import bitchatkmp.presentation.design.generated.resources.pow_label_format
import bitchatkmp.presentation.design.generated.resources.pow_mining_ellipsis
import bitchatkmp.presentation.design.generated.resources.pow_time_estimate
import com.bitchat.design.mapper.toMiningTimeEstimate
import org.jetbrains.compose.resources.stringResource

@Composable
fun PoWStatusIndicator(
    powEnabled: Boolean,
    powDifficulty: Int,
    isMining: Boolean,
    modifier: Modifier = Modifier,
    style: PoWIndicatorStyle = PoWIndicatorStyle.COMPACT
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    if (!powEnabled) return

    when (style) {
        PoWIndicatorStyle.COMPACT -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMining) {
                    val rotation by rememberInfiniteTransition(label = "pow-rotation").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "pow-icon-rotation"
                    )

                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = stringResource(Res.string.cd_mining_pow),
                        tint = Color(0xFFFF9500), // Orange for mining
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = stringResource(Res.string.cd_pow_enabled),
                        tint = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D), // Green when ready
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        PoWIndicatorStyle.DETAILED -> {
            Surface(
                modifier = modifier,
                color = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = stringResource(Res.string.cd_proof_of_work),
                        tint = if (isMining) Color(0xFFFF9500) else {
                            if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                        },
                        modifier = Modifier.size(14.dp)
                    )

                    Text(
                        text = if (isMining) {
                            stringResource(Res.string.pow_mining_ellipsis)
                        } else {
                            stringResource(Res.string.pow_label_format, powDifficulty)
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isMining) Color(0xFFFF9500) else {
                            colorScheme.onSurface.copy(alpha = 0.7f)
                        }
                    )

                    if (!isMining && powDifficulty > 0) {
                        Text(
                            text = stringResource(Res.string.pow_time_estimate, powDifficulty.toMiningTimeEstimate()),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

enum class PoWIndicatorStyle {
    COMPACT,
    DETAILED,
}
