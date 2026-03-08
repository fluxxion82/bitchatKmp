package com.bitchat.design.icons.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons

val Icons.Filled.BatteryStd: ImageVector
    get() {
        if (_batteryStd != null) return _batteryStd!!
        _batteryStd = ImageVector.Builder(
            name = "Filled.BatteryStd",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 4f)
                horizontalLineToRelative(-3f)
                verticalLineTo(2f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(2f)
                horizontalLineTo(7f)
                verticalLineToRelative(18f)
                horizontalLineToRelative(10f)
                verticalLineTo(4f)
                close()
            }
        }.build()
        return _batteryStd!!
    }

private var _batteryStd: ImageVector? = null
