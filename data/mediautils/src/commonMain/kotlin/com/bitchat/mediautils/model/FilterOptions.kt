package com.bitchat.mediautils.model

sealed interface FilterOptions {
    data object Default : FilterOptions

    data object GrayScale : FilterOptions

    data object Sepia : FilterOptions

    data object Invert : FilterOptions
}
