package com.bitchat.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_debug_settings
import bitchatkmp.presentation.design.generated.resources.about_footer
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsFooter(
    onShowDebug: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onShowDebug != null) {
            TextButton(onClick = onShowDebug) {
                Text(
                    text = stringResource(Res.string.about_debug_settings),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.primary
                )
            }
        }
        Text(
            text = stringResource(Res.string.about_footer),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}
