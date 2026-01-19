package com.bitchat.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_dark
import bitchatkmp.presentation.design.generated.resources.about_light
import bitchatkmp.presentation.design.generated.resources.about_system
import com.bitchat.viewvo.settings.ThemePreference
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsThemeSection(
    selectedTheme: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "THEME",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeChip(
                    label = stringResource(Res.string.about_system),
                    selected = selectedTheme == ThemePreference.SYSTEM,
                    onClick = { onThemeSelected(ThemePreference.SYSTEM) },
                    modifier = Modifier.weight(1f)
                )
                ThemeChip(
                    label = stringResource(Res.string.about_light),
                    selected = selectedTheme == ThemePreference.LIGHT,
                    onClick = { onThemeSelected(ThemePreference.LIGHT) },
                    modifier = Modifier.weight(1f)
                )
                ThemeChip(
                    label = stringResource(Res.string.about_dark),
                    selected = selectedTheme == ThemePreference.DARK,
                    onClick = { onThemeSelected(ThemePreference.DARK) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
