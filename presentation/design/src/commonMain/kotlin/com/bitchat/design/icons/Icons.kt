/*
 * Copyright 2024 The Bitchat Authors.
 * Copied from material-icons-extended to avoid pulling in the entire library.
 */
package com.bitchat.design.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Material Design icons used in Bitchat.
 * Only the icons actually used are included to reduce binary size.
 */
object Icons {
    /**
     * Filled icons are the default icon style.
     */
    object Filled

    /**
     * Outlined icons are outlined in style.
     */
    object Outlined

    /**
     * Default points to Filled style.
     */
    val Default = Filled
}

/**
 * Utility function for creating material icons.
 */
fun materialIcon(
    name: String,
    autoMirror: Boolean = false,
    block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> androidx.compose.ui.graphics.vector.ImageVector.Builder
): ImageVector = androidx.compose.ui.graphics.vector.ImageVector.Builder(
    name = name,
    defaultWidth = 24.0.dp,
    defaultHeight = 24.0.dp,
    viewportWidth = 24.0f,
    viewportHeight = 24.0f,
    autoMirror = autoMirror
).block().build()

private val Float.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this)

private val Double.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())
