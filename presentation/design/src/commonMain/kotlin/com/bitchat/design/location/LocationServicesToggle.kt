package com.bitchat.design.location

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.disable_location_services
import bitchatkmp.presentation.design.generated.resources.enable_location_services
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationServicesToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) Color.Red.copy(alpha = 0.12f) else Color(0xFF00C851).copy(alpha = 0.12f),
            contentColor = if (enabled) Color.Red else Color(0xFF00C851)
        )
    ) {
        Text(
            text = if (enabled) {
                stringResource(Res.string.disable_location_services)
            } else {
                stringResource(Res.string.enable_location_services)
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
