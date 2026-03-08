package com.bitchat.design.icons.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Outlined.NoEncryption: ImageVector
    get() {
        if (_noEncryption != null) return _noEncryption!!
        _noEncryption = ImageVector.Builder(
            name = "Outlined.NoEncryption",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.9f, 6f)
                curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                reflectiveCurveToRelative(3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-4.66f)
                lineToRelative(2f, 2f)
                horizontalLineTo(18f)
                verticalLineToRelative(5.56f)
                lineToRelative(2f, 2f)
                verticalLineTo(10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-1f)
                verticalLineTo(6f)
                curveToRelative(0f, -2.76f, -2.24f, -5f, -5f, -5f)
                curveToRelative(-2.32f, 0f, -4.26f, 1.59f, -4.82f, 3.74f)
                lineTo(8.9f, 6.46f)
                verticalLineTo(6f)
                close()
                moveTo(4.41f, 4.81f)
                lineTo(2.81f, 6.41f)
                lineTo(6f, 9.6f)
                verticalLineTo(10f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(8f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(0.19f, 0f, 0.36f, -0.03f, 0.54f, -0.08f)
                lineToRelative(1.48f, 1.48f)
                lineToRelative(1.6f, -1.6f)
                lineTo(4.41f, 4.81f)
                close()
                moveTo(6f, 12f)
                horizontalLineToRelative(1.6f)
                lineToRelative(8f, 8f)
                horizontalLineTo(6f)
                verticalLineToRelative(-8f)
                close()
            }
        }.build()
        return _noEncryption!!
    }

private var _noEncryption: ImageVector? = null
