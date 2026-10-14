package com.bitchat.domain.initialization.models

data class AppInformation(
    val version: Version,
    val versionCode: Int,
    val id: String,
    val debug: Boolean,
)
