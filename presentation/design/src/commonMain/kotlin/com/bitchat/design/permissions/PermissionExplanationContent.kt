package com.bitchat.design.permissions

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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.about_tagline
import bitchatkmp.presentation.design.generated.resources.app_name
import bitchatkmp.presentation.design.generated.resources.cd_privacy_protected
import bitchatkmp.presentation.design.generated.resources.cd_warning
import bitchatkmp.presentation.design.generated.resources.grant_permissions
import bitchatkmp.presentation.design.generated.resources.location_tracking_warning
import bitchatkmp.presentation.design.generated.resources.perm_location_desc
import bitchatkmp.presentation.design.generated.resources.perm_nearby_devices_desc
import bitchatkmp.presentation.design.generated.resources.perm_notifications_desc
import bitchatkmp.presentation.design.generated.resources.perm_type_camera
import bitchatkmp.presentation.design.generated.resources.perm_type_microphone
import bitchatkmp.presentation.design.generated.resources.perm_type_nearby_devices
import bitchatkmp.presentation.design.generated.resources.perm_type_notifications
import bitchatkmp.presentation.design.generated.resources.perm_type_precise_location
import bitchatkmp.presentation.design.generated.resources.permissions_header
import bitchatkmp.presentation.design.generated.resources.privacy_bullets
import bitchatkmp.presentation.design.generated.resources.privacy_protected
import com.bitchat.viewvo.permissions.PermissionType
import org.jetbrains.compose.resources.stringResource

@Composable
fun PermissionExplanationContent(
    modifier: Modifier,
    permissionTypes: Set<PermissionType>,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 88.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
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
                }

                Text(
                    text = stringResource(Res.string.about_tagline),
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
                            imageVector = Icons.Filled.Security,
                            contentDescription = stringResource(Res.string.cd_privacy_protected),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(Res.string.privacy_protected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.privacy_bullets),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(Res.string.permissions_header),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            permissionTypes.forEach { category ->
                PermissionCategoryCard(
                    type = category,
                    colorScheme = colorScheme
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(Res.string.grant_permissions),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionCategoryCard(
    type: PermissionType,
    colorScheme: ColorScheme
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = type.toPermissionIcon(),
            contentDescription = type.name,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = type.toPermissionText(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = type.toDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onBackground.copy(alpha = 0.8f)
            )

            if (type == PermissionType.PRECISE_LOCATION) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(Res.string.cd_warning),
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(Res.string.location_tracking_warning),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF9800)
                        )
                    )
                }
            }
        }
    }
}

private fun PermissionType.toPermissionIcon(): ImageVector {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> Icons.Filled.Bluetooth
        PermissionType.PRECISE_LOCATION -> Icons.Filled.LocationOn
        PermissionType.MICROPHONE -> Icons.Filled.Mic
        PermissionType.NOTIFICATIONS -> Icons.Filled.Notifications
        PermissionType.CAMERA -> Icons.Filled.Camera
    }
}

@Composable
private fun PermissionType.toPermissionText(): String {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> stringResource(Res.string.perm_type_nearby_devices)
        PermissionType.PRECISE_LOCATION -> stringResource(Res.string.perm_type_precise_location)
        PermissionType.MICROPHONE -> stringResource(Res.string.perm_type_microphone)
        PermissionType.NOTIFICATIONS -> stringResource(Res.string.perm_type_notifications)
        PermissionType.CAMERA -> stringResource(Res.string.perm_type_camera)
    }
}

@Composable
private fun PermissionType.toDescription(): String {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> stringResource(Res.string.perm_nearby_devices_desc)
        PermissionType.PRECISE_LOCATION -> stringResource(Res.string.perm_location_desc)
        PermissionType.NOTIFICATIONS -> stringResource(Res.string.perm_notifications_desc)
        PermissionType.MICROPHONE -> ""
        PermissionType.CAMERA -> ""
    }
}
