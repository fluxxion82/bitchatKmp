package com.bitchat.domain.initialization.models

data class AppInformation(
    val version: Version,
    val versionCode: Int,
    val id: String,
    val debug: Boolean,
)

data class Version(
    val name: String,
    val build: String,
    val additionalInfo: String
)
