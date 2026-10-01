package com.bitchat.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_tagline
import bitchatkmp.presentation.design.generated.resources.app_name
import bitchatkmp.presentation.design.generated.resources.version_prefix
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsHeader(
    appVersion: String,
    modifier: Modifier = Modifier,
    buildIdentity: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = 1.sp
            ),
            color = colorScheme.onBackground
        )
        Text(
            text = stringResource(Res.string.version_prefix, appVersion),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onBackground.copy(alpha = 0.5f)
        )
        // Which binary this is (commit, branch, clean or dirty, build time), for telling two
        // devices running the same app apart. Shown as the build wrote it.
        if (buildIdentity != null) {
            Text(
                text = buildIdentity,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
        Text(
            text = stringResource(Res.string.about_tagline),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
