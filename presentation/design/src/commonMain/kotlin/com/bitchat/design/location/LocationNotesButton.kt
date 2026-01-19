package com.bitchat.design.location

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_location_notes
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.PermissionState
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationNotesButton(
    permissionState: PermissionState,
    selectedLocationChannel: Channel,
    locationServicesEnabled: Boolean,
    hasNotes: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val locationPermissionGranted = permissionState == PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled

    if (selectedLocationChannel is Channel.Mesh && locationEnabled) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = stringResource(Res.string.cd_location_notes),
                modifier = Modifier.size(16.dp),
                tint = if (hasNotes) colorScheme.primary else Color.Gray
            )
        }
    }
}
