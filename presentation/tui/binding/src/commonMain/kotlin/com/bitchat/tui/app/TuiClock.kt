package com.bitchat.tui.app

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The device zone, read only when a chat timestamp is first needed. */
private val localZone by lazy { TimeZone.currentSystemDefault() }

/** A message time as `HH:mm` in the device's zone. Kept as a stable function reference for chat caches. */
internal fun localClockTime(instant: Instant): String = localClockTime(instant, localZone)

/** Formats [instant] in [zone]; separated so JVM tests do not depend on the machine's local zone. */
internal fun localClockTime(instant: Instant, zone: TimeZone): String {
    val time = instant.toLocalDateTime(zone)
    return time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')
}
