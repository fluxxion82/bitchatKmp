package com.bitchat.design.icons.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Filled.BrokenImage: ImageVector
    get() {
        if (_brokenImage != null) return _brokenImage!!
        _brokenImage = ImageVector.Builder(
            name = "Filled.BrokenImage",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 5f)
                verticalLineToRelative(6.59f)
                lineToRelative(-3f, -3.01f)
                lineToRelative(-4f, 4.01f)
                lineToRelative(-4f, -4f)
                lineToRelative(-4f, 4f)
                lineToRelative(-3f, -3.01f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                close()
                moveTo(18f, 11.42f)
                lineToRelative(3f, 3.01f)
                verticalLineTo(19f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                horizontalLineTo(5f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineToRelative(-6.58f)
                lineToRelative(3f, 2.99f)
                lineToRelative(4f, -3.99f)
                lineToRelative(4f, 4f)
                lineToRelative(4f, -4f)
                close()
            }
        }.build()
        return _brokenImage!!
    }

private var _brokenImage: ImageVector? = null
