package com.bitchat.domain.location.model

/**
 * There is no location fix and there will not be one until something changes.
 *
 * This exists because the desktop location service used to answer a failed lookup with a hardcoded
 * San Francisco coordinate, which put the user in a geohash channel for a city they were not in and
 * said nothing about it. A wrong answer that looks like a right one is worse than no answer, so the
 * failure is now explicit and typed.
 *
 * Typed rather than a bare exception so the layers above can tell an expected absence from a real
 * fault: an expected absence should reach the user as "location unavailable", not as a stack trace
 * every five seconds.
 *
 * [reason] is for the user, not the log.
 */
class LocationUnavailableException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception(reason.message, cause) {

    enum class Reason(val message: String) {
        /** No platform location source at all, e.g. Linux desktop with no native bridge. */
        NO_SOURCE("Location is not available on this device"),

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
