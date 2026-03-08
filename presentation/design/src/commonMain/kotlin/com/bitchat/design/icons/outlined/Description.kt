package com.bitchat.design.icons.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Outlined.Description: ImageVector
    get() {
        if (_description != null) return _description!!
        _description = ImageVector.Builder(
            name = "Outlined.Description",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 16f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(2f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 12f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(2f)
                horizontalLineTo(8f)
                close()
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(16f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                horizontalLineTo(18f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(8f)
                lineToRelative(-6f, -6f)
                close()
                moveTo(18f, 20f)
                horizontalLineTo(6f)
                verticalLineTo(4f)
                horizontalLineToRelative(7f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(11f)
                close()
            }
        }.build()
        return _description!!
    }

private var _description: ImageVector? = null
