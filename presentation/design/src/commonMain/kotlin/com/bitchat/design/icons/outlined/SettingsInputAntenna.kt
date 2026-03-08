package com.bitchat.design.icons.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Outlined.SettingsInputAntenna: ImageVector
    get() {
        if (_settingsInputAntenna != null) return _settingsInputAntenna!!
        _settingsInputAntenna = ImageVector.Builder(
            name = "Outlined.SettingsInputAntenna",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 5f)
                curveToRelative(-3.87f, 0f, -7f, 3.13f, -7f, 7f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -2.76f, 2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -3.87f, -3.13f, -7f, -7f, -7f)
                close()
                moveTo(13f, 14.29f)
                curveToRelative(0.88f, -0.39f, 1.5f, -1.26f, 1.5f, -2.29f)
                curveToRelative(0f, -1.38f, -1.12f, -2.5f, -2.5f, -2.5f)
                reflectiveCurveTo(9.5f, 10.62f, 9.5f, 12f)
                curveToRelative(0f, 1.02f, 0.62f, 1.9f, 1.5f, 2.29f)
                verticalLineToRelative(3.3f)
                lineTo(7.59f, 21f)
                lineTo(9f, 22.41f)
                lineToRelative(3f, -3f)
                lineToRelative(3f, 3f)
                lineTo(16.41f, 21f)
                lineToRelative(-3.41f, -3.41f)
                verticalLineToRelative(-3.3f)
                close()
                moveTo(12f, 1f)
                curveTo(5.93f, 1f, 1f, 5.93f, 1f, 12f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -4.97f, 4.03f, -9f, 9f, -9f)
                reflectiveCurveToRelative(9f, 4.03f, 9f, 9f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -6.07f, -4.93f, -11f, -11f, -11f)
                close()
            }
        }.build()
        return _settingsInputAntenna!!
    }

private var _settingsInputAntenna: ImageVector? = null
