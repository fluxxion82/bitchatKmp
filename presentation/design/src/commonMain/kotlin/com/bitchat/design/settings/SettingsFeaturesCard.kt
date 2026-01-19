package com.bitchat.design.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_e2e_desc
import bitchatkmp.presentation.design.generated.resources.about_e2e_title
import bitchatkmp.presentation.design.generated.resources.about_offline_mesh_desc
import bitchatkmp.presentation.design.generated.resources.about_offline_mesh_title
import bitchatkmp.presentation.design.generated.resources.about_online_geohash_desc
import bitchatkmp.presentation.design.generated.resources.about_online_geohash_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsFeaturesCard(
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
//        Text(
//            text = stringResource(Res.string.about_appearance).uppercase(),
//            style = MaterialTheme.typography.labelSmall,
//            color = colorScheme.onBackground.copy(alpha = 0.5f),
//            letterSpacing = 0.5.sp,
//            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
//        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                FeatureRow(
                    icon = Icons.Filled.Bluetooth,
                    title = stringResource(Res.string.about_offline_mesh_title),
                    subtitle = stringResource(Res.string.about_offline_mesh_desc)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f)
                )
                FeatureRow(
                    icon = Icons.Default.Public,
                    title = stringResource(Res.string.about_online_geohash_title),
                    subtitle = stringResource(Res.string.about_online_geohash_desc)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f)
                )
                FeatureRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(Res.string.about_e2e_title),
                    subtitle = stringResource(Res.string.about_e2e_desc)
                )
            }
        }
    }
}
