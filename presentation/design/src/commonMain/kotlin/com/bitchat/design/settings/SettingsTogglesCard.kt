package com.bitchat.design.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_background_desc
import bitchatkmp.presentation.design.generated.resources.about_background_title
import bitchatkmp.presentation.design.generated.resources.about_pow
import bitchatkmp.presentation.design.generated.resources.about_pow_tip
import bitchatkmp.presentation.design.generated.resources.about_tor_route
import com.bitchat.design.icons.Icons
import com.bitchat.design.icons.filled.Bluetooth
import com.bitchat.design.icons.filled.Security
import com.bitchat.design.icons.filled.Speed
import com.bitchat.domain.tor.model.TorAvailability
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsTogglesCard(
    showBackgroundModeSetting: Boolean,
    backgroundModeEnabled: Boolean,
    onBackgroundModeToggled: (Boolean) -> Unit,
    proofOfWorkEnabled: Boolean,
    onProofOfWorkToggled: (Boolean) -> Unit,
    torNetworkEnabled: Boolean,
    onTorNetworkToggled: (Boolean) -> Unit,
    torAvailability: TorAvailability,
    torRunning: Boolean,
    torBootstrapPercent: Int,
    torErrorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onBackground.copy(alpha = 0.5f),
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                if (showBackgroundModeSetting) {
                    SettingsToggleRow(
                        icon = Icons.Filled.Bluetooth,
                        title = stringResource(Res.string.about_background_title),
                        subtitle = stringResource(Res.string.about_background_desc),
                        checked = backgroundModeEnabled,
                        onCheckedChange = onBackgroundModeToggled
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )
                }

                SettingsToggleRow(
                    icon = Icons.Filled.Speed,
                    title = stringResource(Res.string.about_pow),
                    subtitle = stringResource(Res.string.about_pow_tip),
                    checked = proofOfWorkEnabled,
                    onCheckedChange = onProofOfWorkToggled
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f)
                )

                SettingsToggleRow(
                    icon = Icons.Filled.Security,
                    title = "Tor Network",
                    subtitle = stringResource(Res.string.about_tor_route),
                    checked = torNetworkEnabled,
                    onCheckedChange = onTorNetworkToggled,
                    // Turning it off must stay possible even where Tor cannot run: a host with no
                    // native library previously showed this switch on and disabled, with nothing
                    // in the app able to turn it back off.
                    enabled = torAvailability.isAvailable || torNetworkEnabled,
                    statusIndicator = if (torNetworkEnabled) {
                        {
                            val statusColor = when {
                                torRunning && torBootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                torRunning -> Color(0xFFFF9500)
                                else -> Color(0xFFFF3B30)
                            }
                            Surface(
                                color = statusColor,
                                shape = CircleShape,
                                modifier = Modifier.size(8.dp)
                            ) {}
                        }
                    } else null
                )
            }
        }

        val unavailable = torUnavailableMessage(torAvailability, torErrorMessage)
        if (unavailable != null) {
            // Prefer the concrete reason (which library is missing, where it was looked for and how
            // to build it) over the generic string - the generic one leaves the user with nothing
            // to act on.
            Text(
                text = unavailable.detail ?: stringResource(unavailable.fallback),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
        }
    }
}
