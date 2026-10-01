package com.bitchat.viewvo.settings

import com.bitchat.domain.initialization.models.AppInformation

/** The version as Settings shows it; "unknown" rather than a blank where a build gave none. */
fun AppInformation.toVersionLabel(): String = version.name.trim().ifEmpty { UNKNOWN_VERSION }

/** The build's description of itself, or null when it has none (a blank one is no identity). */
fun AppInformation.toBuildIdentity(): String? = version.additionalInfo.trim().ifEmpty { null }

internal const val UNKNOWN_VERSION = "unknown"
