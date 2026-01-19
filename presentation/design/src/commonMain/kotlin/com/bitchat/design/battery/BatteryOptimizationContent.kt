package com.bitchat.design.battery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.app_name
import bitchatkmp.presentation.design.generated.resources.battery_benefits_short
import bitchatkmp.presentation.design.generated.resources.battery_optimization_detected_title
import bitchatkmp.presentation.design.generated.resources.battery_optimization_disabled_title
import bitchatkmp.presentation.design.generated.resources.battery_optimization_enabled_title
import bitchatkmp.presentation.design.generated.resources.battery_optimization_explanation_short
import bitchatkmp.presentation.design.generated.resources.battery_optimization_not_required
import bitchatkmp.presentation.design.generated.resources.battery_optimization_not_supported_message
import bitchatkmp.presentation.design.generated.resources.battery_optimization_skip
import bitchatkmp.presentation.design.generated.resources.battery_optimization_success_message
import bitchatkmp.presentation.design.generated.resources.benefits_of_disabling
import bitchatkmp.presentation.design.generated.resources.cd_battery_optimization
import bitchatkmp.presentation.design.generated.resources.cd_benefits
import bitchatkmp.presentation.design.generated.resources.cd_checking_battery_optimization
import bitchatkmp.presentation.design.generated.resources.cd_not_supported_battery_optimization
import bitchatkmp.presentation.design.generated.resources.check_again
import bitchatkmp.presentation.design.generated.resources.continue_btn
import bitchatkmp.presentation.design.generated.resources.disable_battery_optimization
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import org.jetbrains.compose.resources.stringResource

@Composable
fun BatteryOptimizationContent(
    modifier: Modifier,
    status: BatteryOptimizationStatus,
    onDisableBatteryOptimization: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isLoading: Boolean = false
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            BatteryOptimizationStatus.ENABLED -> {
                BatteryOptimizationEnabledContent(
                    onDisableBatteryOptimization = onDisableBatteryOptimization,
                    onRetry = onRetry,
                    onSkip = onSkip,
                    colorScheme = MaterialTheme.colorScheme,
                    isLoading = isLoading
                )
            }

            BatteryOptimizationStatus.DISABLED -> {
                BatteryOptimizationCheckingContent(
                    colorScheme = MaterialTheme.colorScheme
                )
            }

            BatteryOptimizationStatus.NOT_SUPPORTED -> {
                BatteryOptimizationNotSupportedContent(
                    onRetry = onRetry,
                    colorScheme = MaterialTheme.colorScheme
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationEnabledContent(
    onDisableBatteryOptimization: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    colorScheme: ColorScheme,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    ),
                    color = colorScheme.onBackground
                )

                Text(
                    text = stringResource(Res.string.battery_optimization_detected_title),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Power,
                            contentDescription = stringResource(Res.string.cd_battery_optimization),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(Res.string.battery_optimization_enabled_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.battery_optimization_explanation_short),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = stringResource(Res.string.cd_benefits),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(Res.string.benefits_of_disabling),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.battery_benefits_short),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onDisableBatteryOptimization,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(Res.string.disable_battery_optimization),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(
                        text = stringResource(Res.string.check_again),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                TextButton(
                    onClick = {
                        onSkip()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(
                        text = stringResource(Res.string.battery_optimization_skip),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCheckingContent(
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = colorScheme.onBackground
            )

            Text(
                text = stringResource(Res.string.battery_optimization_disabled_title),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        val infiniteTransition = rememberInfiniteTransition(label = "rotation")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        Icon(
            imageVector = Icons.Filled.BatteryStd,
            contentDescription = stringResource(Res.string.cd_checking_battery_optimization),
            modifier = Modifier
                .size(64.dp)
                .rotate(rotation),
            tint = colorScheme.primary
        )

        Text(
            text = stringResource(Res.string.battery_optimization_success_message),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onBackground.copy(alpha = 0.8f)
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BatteryOptimizationNotSupportedContent(
    onRetry: () -> Unit,
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = colorScheme.onBackground
            )

            Text(
                text = stringResource(Res.string.battery_optimization_not_required),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = stringResource(Res.string.cd_not_supported_battery_optimization),
            modifier = Modifier.size(64.dp),
            tint = colorScheme.primary
        )

        Text(
            text = stringResource(Res.string.battery_optimization_not_supported_message),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onBackground.copy(alpha = 0.8f)
            ),
            textAlign = TextAlign.Center
        )

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary
            )
        ) {
            Text(
                text = stringResource(Res.string.continue_btn),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
