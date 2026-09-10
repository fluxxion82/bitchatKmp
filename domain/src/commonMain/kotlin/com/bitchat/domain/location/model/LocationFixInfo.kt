package com.bitchat.domain.location.model

/**
 * Where a location fix came from and when, so a stale one can be labelled rather than presented as
 * current.
 *
 * Without this, a coordinate persisted before a flight reappears as the user's present
 * neighbourhood: the channel list is built down to BLOCK precision from whatever fix it is handed,
 * with nothing on screen to say the fix is hours old and a thousand kilometres away.
 */
data class LocationFixInfo(
    val source: Source,
    /** How long ago the fix was observed. Computed where a clock already exists, so consumers need none. */
    val ageMillis: Long
) {
    enum class Source {
        /** The operating system's own location service. Precise. */
        DEVICE,

        /** Derived from the public IP address. City-level at best, often tens of km out. */
        IP_ADDRESS
    }
}
