package com.bitchat.domain.location.model

/**
 * There is no real location fix and there will not be one until something changes.
 *
 * Typed so the layers above can tell an expected absence from a fault and say why: the channel
 * list then shows [DEFAULT_PLACE] under a notice, never as the device's position.
 *
 * [reason] is for the user, not the log.
 */
class LocationUnavailableException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception(reason.message, cause) {

    enum class Reason(val message: String) {
        /** No platform location source, e.g. a board with no GPS or a desktop with no native bridge. */
        NO_SOURCE("Location has not been set"),

        /** A source exists but the lookup failed, e.g. the network is down. */
        LOOKUP_FAILED("Could not determine your location"),

        /** The OS refused. Distinct from having no source at all. */
        PERMISSION_DENIED("Location permission was denied"),

        /**
         * Deliberately not looked up, because the only available source would have disclosed the
         * user's address. Distinct from NO_SOURCE: the source exists, policy declined to use it.
         */
        SUPPRESSED_BY_POLICY("Location lookup is off while Tor is on")
    }
}
