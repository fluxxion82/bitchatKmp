package com.bitchat.design.settings

import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.tor_not_available_in_this_build
import bitchatkmp.presentation.design.generated.resources.tor_not_supported_on_this_platform
import com.bitchat.domain.tor.model.TorAvailability
import org.jetbrains.compose.resources.StringResource

/**
 * What to show under a Tor switch that cannot be used.
 *
 * [detail] wins over [fallback] when it is there: for a missing native library the manager knows
 * which file it looked for and how to build it, which beats any generic string.
 */
data class TorUnavailableMessage(
    val detail: String?,
    val fallback: StringResource,
)

/**
 * The explanation for a disabled Tor switch, or null while Tor can actually protect traffic.
 *
 * The two reasons must not share a message. "Not available in this build" sends an iOS or embedded
 * user hunting for a native library that is present and working; the truth there is that the HTTP
 * engine ignores the SOCKS proxy, so Tor would bootstrap and protect nothing. There is deliberately
 * no [TorUnavailableMessage.detail] for that case: nothing is ever started, so any Tor error still
 * sitting in the status is stale and must not displace the real explanation.
 */
fun torUnavailableMessage(
    availability: TorAvailability,
    torErrorMessage: String?,
): TorUnavailableMessage? = when (availability) {
    TorAvailability.AVAILABLE -> null

    TorAvailability.NO_PROXY_SUPPORT -> TorUnavailableMessage(
        detail = null,
        fallback = Res.string.tor_not_supported_on_this_platform,
    )

    TorAvailability.NATIVE_LIBRARY_MISSING -> TorUnavailableMessage(
        detail = torErrorMessage,
        fallback = Res.string.tor_not_available_in_this_build,
    )
}
