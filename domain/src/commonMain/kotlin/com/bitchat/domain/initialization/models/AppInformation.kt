package com.bitchat.domain.initialization.models

data class AppInformation(
    val version: Version,
    val versionCode: Int,
    val id: String,
    val debug: Boolean,
)

/**
 * @property additionalInfo The build's own description of itself, shown in Settings so a running
 *   binary can be told from another: the embedded apps put their `--version` line here
 *   (`bitchat-tui 1.0.0 (65d65087cd41, main, clean, debug, built ...)`). Empty when the build has
 *   no such description; it is not a free-form field, so a placeholder must not be written to it.
 */
data class Version(
    val name: String,
    val build: String,
    val additionalInfo: String
)
