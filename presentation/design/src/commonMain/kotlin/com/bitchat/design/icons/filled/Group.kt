package com.bitchat.design.icons.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Filled.Group: ImageVector
    get() {
        if (_group != null) return _group!!
        _group = ImageVector.Builder(
            name = "Filled.Group",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 11f)
                curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                reflectiveCurveTo(17.66f, 5f, 16f, 5f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                close()
                moveTo(8f, 11f)
                curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                reflectiveCurveTo(9.66f, 5f, 8f, 5f)
                curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                close()
                moveTo(8f, 13f)
                curveToRelative(-2.33f, 0f, -7f, 1.17f, -7f, 3.5f)
                verticalLineTo(19f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(-2.5f)
                curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f)
                close()
                moveTo(16f, 13f)
                curveToRelative(-0.29f, 0f, -0.62f, 0.02f, -0.97f, 0.05f)
                curveToRelative(1.16f, 0.84f, 1.97f, 1.97f, 1.97f, 3.45f)
                verticalLineTo(19f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(-2.5f)
                curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f)
                close()
            }
        }.build()
        return _group!!
    }

private var _group: ImageVector? = null
