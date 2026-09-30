package com.bitchat.design.settings

import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.tor_not_available_in_this_build
import bitchatkmp.presentation.design.generated.resources.tor_not_supported_on_this_platform
import bitchatkmp.presentation.design.generated.resources.tor_on_but_unusable_on_this_platform
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
 * The reasons must not share a message. "Not available in this build" sends an iOS or embedded
 * user hunting for a native library that is present and working; the truth there is that the HTTP
 * engine ignores the SOCKS proxy, so Tor would bootstrap and protect nothing. There is deliberately
 * no [TorUnavailableMessage.detail] for those cases: nothing is ever started, so any Tor error
 * still sitting in the status is stale and must not displace the real explanation.
 *
 * [torBlocksNostr] separates the two halves of the no-proxy case. With the switch off, this is a
 * fact about the build and nothing is being withheld. With it on, the user is in a dead end they
 * did not choose, and the text has to say so and name the way out, or the screen reads as a
 * contradiction against the "Tor is on" notice in every chat.
 */
fun torUnavailableMessage(
    availability: TorAvailability,
    torErrorMessage: String?,
    torBlocksNostr: Boolean = false,
): TorUnavailableMessage? = when (availability) {
    TorAvailability.AVAILABLE -> null

    TorAvailability.NO_PROXY_SUPPORT -> TorUnavailableMessage(
        detail = null,
        fallback = if (torBlocksNostr) {
            Res.string.tor_on_but_unusable_on_this_platform
        } else {
            Res.string.tor_not_supported_on_this_platform
        },
    )

    TorAvailability.NATIVE_LIBRARY_MISSING -> TorUnavailableMessage(
        detail = torErrorMessage,
        fallback = Res.string.tor_not_available_in_this_build,
    )
}
