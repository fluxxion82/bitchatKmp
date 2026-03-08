package com.bitchat.design.icons.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Outlined.Warning: ImageVector
    get() {
        if (_warning != null) return _warning!!
        _warning = ImageVector.Builder(
            name = "Outlined.Warning",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 5.99f)
                lineTo(19.53f, 19f)
                horizontalLineTo(4.47f)
                lineTo(12f, 5.99f)
                moveTo(12f, 2f)
                lineTo(1f, 21f)
                horizontalLineToRelative(22f)
                lineTo(12f, 2f)
                close()
                moveTo(13f, 16f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                close()
                moveTo(13f, 10f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-4f)
                close()
            }
        }.build()
        return _warning!!
    }

private var _warning: ImageVector? = null
