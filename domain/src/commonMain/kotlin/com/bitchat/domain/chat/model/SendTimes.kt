package com.bitchat.domain.chat.model

/**
 * The time to write on the next of a row of messages, given the time written on the [last] one.
 * Receivers sort a conversation by the time on each message, the mesh also tells a repeated packet
 * from a new one by it, and a text sent as several messages hands them over all at once: so each
 * gets a later time than the one before, [now] when that is later and otherwise one more than
 * [last].
 *
 * That lets the time run ahead of the clock, and a receiver refuses a message dated too far ahead.
 * Once [last] is [maxAhead] past [now] (the clock was set back, or more was sent at once than
 * anyone sends) the clock is followed again from where it is.
 */
fun nextSendTime(last: Long, now: Long, maxAhead: Long): Long =
    if (last < now || last >= now + maxAhead) now else last + 1
