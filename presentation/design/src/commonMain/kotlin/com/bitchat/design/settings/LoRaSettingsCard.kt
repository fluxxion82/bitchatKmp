package com.bitchat.design.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.design.icons.Icons
import com.bitchat.design.icons.filled.Radio
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower

@Composable
fun LoRaSettingsCard(
    loraEnabled: Boolean,
    loraRegion: LoRaRegion,
    loraTxPower: LoRaTxPower,
    loraShowPeers: Boolean,
    loraProtocol: LoRaProtocolType,
    onLoRaEnabledToggled: (Boolean) -> Unit,
    onLoRaRegionSelected: (LoRaRegion) -> Unit,
    onLoRaTxPowerSelected: (LoRaTxPower) -> Unit,
    onLoRaShowPeersToggled: (Boolean) -> Unit,
    onLoRaProtocolSelected: (LoRaProtocolType) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "LORA RADIO",
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
                // Enable/Disable LoRa
                SettingsToggleRow(
                    icon = Icons.Filled.Radio,
                    title = "LoRa Transport",
                    subtitle = "Enable long-range radio communication",
                    checked = loraEnabled,
                    onCheckedChange = onLoRaEnabledToggled
                )

                if (loraEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )

                    // Region selector
                    LoRaDropdownRow(
                        title = "Region",
                        subtitle = "Radio frequency band for your location",
                        currentValue = loraRegion.toDisplayName(),
                        options = LoRaRegion.entries.map { it to it.toDisplayName() },
                        onOptionSelected = { onLoRaRegionSelected(it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )

                    // TX Power selector
                    LoRaDropdownRow(
                        title = "TX Power",
                        subtitle = "Transmit power level",
                        currentValue = loraTxPower.toDisplayName(),
                        options = LoRaTxPower.entries.map { it to it.toDisplayName() },
                        onOptionSelected = { onLoRaTxPowerSelected(it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )

                    // Show peers toggle
                    SettingsToggleRow(
                        icon = Icons.Filled.Radio,
                        title = "Show LoRa Peers",
                        subtitle = "Display LoRa peer count in chat header",
                        checked = loraShowPeers,
                        onCheckedChange = onLoRaShowPeersToggled
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )

                    // Protocol selector
                    LoRaDropdownRow(
                        title = "Protocol",
                        subtitle = "LoRa messaging protocol",
                        currentValue = loraProtocol.displayName,
                        options = LoRaProtocolType.entries.map { it to it.displayName },
                        onOptionSelected = { onLoRaProtocolSelected(it) }
                    )
                }
            }
        }

        Text(
            text = "LoRa enables mesh messaging without internet or Bluetooth",
            fontSize = 12.sp,
            color = colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
    }
}

@Composable
private fun <T> LoRaDropdownRow(
    title: String,
    subtitle: String,
    currentValue: String,
    options: List<Pair<T, String>>,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(36.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Surface(
            color = colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = currentValue,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (option, displayName) ->
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun LoRaRegion.toDisplayName(): String = when (this) {
    LoRaRegion.US_915 -> "US 915 MHz"
    LoRaRegion.EU_868 -> "EU 868 MHz"
    LoRaRegion.AU_915 -> "AU 915 MHz"
    LoRaRegion.AS_923 -> "AS 923 MHz"
}

private fun LoRaTxPower.toDisplayName(): String = when (this) {
    LoRaTxPower.LOW -> "Low (10 dBm)"
    LoRaTxPower.MEDIUM -> "Medium (17 dBm)"
    LoRaTxPower.HIGH -> "High (20 dBm)"
}
