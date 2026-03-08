package com.bitchat.design.icons.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Filled.Radio: ImageVector
    get() {
        if (_radio != null) return _radio!!
        _radio = ImageVector.Builder(
            name = "Filled.Radio",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Cell tower / radio antenna icon
            path(fill = SolidColor(Color.Black)) {
                // Tower base
                moveTo(12f, 10f)
                lineTo(9.5f, 22f)
                horizontalLineTo(14.5f)
                lineTo(12f, 10f)
                close()
                // Tower top
                moveTo(12f, 2f)
                lineTo(11f, 10f)
                horizontalLineTo(13f)
                lineTo(12f, 2f)
                close()
            }
            // Left radio wave (inner)
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.1f, 5.8f)
                curveTo(8.6f, 6.4f, 9f, 7.1f, 9.2f, 7.9f)
                curveTo(9.4f, 8.7f, 9.4f, 9.5f, 9.1f, 10.2f)
                lineTo(7.5f, 9.5f)
                curveTo(7.7f, 9.1f, 7.7f, 8.6f, 7.6f, 8.1f)
                curveTo(7.5f, 7.6f, 7.2f, 7.2f, 6.9f, 6.9f)
                lineTo(8.1f, 5.8f)
                close()
            }
            // Right radio wave (inner)
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.9f, 5.8f)
                curveTo(15.4f, 6.4f, 15f, 7.1f, 14.8f, 7.9f)
                curveTo(14.6f, 8.7f, 14.6f, 9.5f, 14.9f, 10.2f)
                lineTo(16.5f, 9.5f)
                curveTo(16.3f, 9.1f, 16.3f, 8.6f, 16.4f, 8.1f)
                curveTo(16.5f, 7.6f, 16.8f, 7.2f, 17.1f, 6.9f)
                lineTo(15.9f, 5.8f)
                close()
            }
            // Left radio wave (outer)
            path(fill = SolidColor(Color.Black)) {
                moveTo(5.6f, 3.4f)
                curveTo(6.7f, 4.5f, 7.5f, 5.9f, 7.9f, 7.4f)
                curveTo(8.3f, 8.9f, 8.3f, 10.5f, 7.8f, 12f)
                lineTo(6.1f, 11.3f)
                curveTo(6.5f, 10.2f, 6.5f, 9f, 6.2f, 7.9f)
                curveTo(5.9f, 6.8f, 5.3f, 5.8f, 4.5f, 5f)
                lineTo(5.6f, 3.4f)
                close()
            }
            // Right radio wave (outer)
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.4f, 3.4f)
                curveTo(17.3f, 4.5f, 16.5f, 5.9f, 16.1f, 7.4f)
                curveTo(15.7f, 8.9f, 15.7f, 10.5f, 16.2f, 12f)
                lineTo(17.9f, 11.3f)
                curveTo(17.5f, 10.2f, 17.5f, 9f, 17.8f, 7.9f)
                curveTo(18.1f, 6.8f, 18.7f, 5.8f, 19.5f, 5f)
                lineTo(18.4f, 3.4f)
                close()
            }
        }.build()
        return _radio!!
    }

private var _radio: ImageVector? = null
