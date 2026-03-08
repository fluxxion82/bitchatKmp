package com.bitchat.design.icons.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Filled.Power: ImageVector
    get() {
        if (_power != null) return _power!!
        _power = ImageVector.Builder(
            name = "Filled.Power",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.01f, 7f)
                lineTo(16f, 3f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-4f)
                verticalLineTo(3f)
                horizontalLineTo(8f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-0.01f)
                curveTo(7f, 6.99f, 6f, 7.99f, 6f, 8.99f)
                verticalLineToRelative(5.49f)
                lineTo(9.5f, 18f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-3f)
                lineToRelative(3.5f, -3.51f)
                verticalLineToRelative(-5.5f)
                curveToRelative(0f, -1f, -1f, -2f, -1.99f, -1.99f)
                close()
            }
        }.build()
        return _power!!
    }

private var _power: ImageVector? = null
