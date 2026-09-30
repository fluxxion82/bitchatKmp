package com.bitchat.nostr

import com.bitchat.client.TorRouteProvenance
import com.bitchat.nostr.util.ConcurrentMap

/**
 * Whether a relay socket may be reported to the user as having travelled through Tor.
 */
internal fun claimsTorRoute(route: TorRouteProvenance?): Boolean =
    route?.usedTorProxy == true && route.isCurrent()

/** Session evidence owned by relay URL and cleared when its Tor route becomes unusable. */
internal class TorRouteClaims {
    private val routes = ConcurrentMap<String, TorRouteProvenance>()

    fun remember(relayUrl: String, route: TorRouteProvenance) {
        routes[relayUrl] = route
    }

    fun claimsTorRoute(relayUrl: String): Boolean = claimsTorRoute(routes[relayUrl])

    fun removeClaim(relayUrl: String): Boolean = claimsTorRoute(routes.remove(relayUrl))

    fun clear() = routes.clear()

    /** Retires each claim while retaining whether its published line claimed Tor protection. */
    fun removeAll(): List<Pair<String, Boolean>> = routes.takeAll().map { (relayUrl, route) ->
        relayUrl to claimsTorRoute(route)
    }
}
