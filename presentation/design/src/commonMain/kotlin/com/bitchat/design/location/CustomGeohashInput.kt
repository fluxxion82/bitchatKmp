package com.bitchat.design.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_open_map
import bitchatkmp.presentation.design.generated.resources.cd_teleport
import bitchatkmp.presentation.design.generated.resources.geohash_placeholder
import bitchatkmp.presentation.design.generated.resources.hash_symbol
import bitchatkmp.presentation.design.generated.resources.invalid_geohash
import bitchatkmp.presentation.design.generated.resources.teleport
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomGeohashInput(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onTeleport: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val teleportColor = Color(0xFF248A3D)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.hash_symbol),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.geohash_placeholder),
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            IconButton(onClick = onOpenMap) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = stringResource(Res.string.cd_open_map),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                Button(
                    onClick = onTeleport,
                    enabled = value.length >= 2,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = teleportColor.copy(alpha = 0.12f),
                        contentColor = teleportColor
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.teleport),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Filled.PinDrop,
                            contentDescription = stringResource(Res.string.cd_teleport),
                            modifier = Modifier.size(14.dp),
                            tint = teleportColor
                        )
                    }
                }
            }
        }

        if (error != null) {
            Text(
                text = stringResource(Res.string.invalid_geohash),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
