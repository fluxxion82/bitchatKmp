package com.bitchat.lora.bitchat.transmit

import com.bitchat.lora.bitchat.protocol.LoRaFrame
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.airtimeMicros
import com.bitchat.lora.radio.scaledAirtimeLimitMicros

/**
 * Which frame may be handed to the radio next, and how much time on air each kind of traffic has
 * left. Pure: no clock, no coroutines, not thread-safe ([LoRaTransmitter] guards it). Every time is
 * monotonic milliseconds handed in by the caller.
 *
 * Three ledgers, each a limit on time on air in any 60 seconds, none lending time or places to
 * another: what this device's user sends, what other people's packets make it send, and heartbeats.
 * A stranger can spend the second and nothing else; inside it, packets that did not authenticate
 * share a smaller limit and every validated peer has its own.
 *
 * Nothing is admitted unless, for each of those limits, the charges that still count plus the
 * reservations plus what is asked stay within it. A frame is reserved when it is admitted and
 * charged when it has been handed to the radio, so nothing admitted ever waits for time to come
 * free, and a charge counts until 60 seconds after its transmission ENDED: counted from the start,
 * the end of a long frame and whatever was admitted a minute after it began would be on the air
 * inside one minute. (After a change to a configuration with smaller limits what was spent before
 * can exceed them; nothing is admitted until it has aged.)
 */
internal class TransmitQueue {
    class Request(
        val frame: ByteArray,
        val kind: TransmitKind,
        val cause: Cause?,
        val notBeforeMillis: Long,
        val expiresAtMillis: Long,
    )

    /** Time and places taken from LOCAL_ORIGIN ahead of the frames that will use them. */
    class Hold internal constructor(
        internal var microsLeft: Long,
        internal var placesLeft: Int,
        internal val expiresAtMillis: Long,
    )

    class Item internal constructor(
        val frame: ByteArray,
        val kind: TransmitKind,
        val cause: Cause?,
        val notBeforeMillis: Long,
        val expiresAtMillis: Long,
        val airtimeMicros: Long,
        internal val sequence: Long,
    )

    /**
     * [send] is now in flight and must be answered with [transmitted] or [notTransmitted]. Without one,
     * [waitUntilMillis] is when something queued or held next changes, or null when nothing is.
     * [expired] were dropped unsent.
     */
    class Next(val send: Item?, val waitUntilMillis: Long?, val expired: List<Item>)

    private class Charge(val endMillis: Long, val micros: Long, val cause: Cause?)

    private lateinit var config: LoRaConfig
    private val charges = Ledger.entries.associateWith { mutableListOf<Charge>() }
    private val queued = mutableListOf<Item>()
    private val holds = mutableListOf<Hold>()
    private var inFlight: Item? = null
    private var sequence = 0L
    private var lastLedger = Ledger.entries.last()

    /** The limits and every frame's time on air follow [config]. The charges stay: a restart is not a fresh budget. */
    fun configure(config: LoRaConfig) {
        check(queued.isEmpty() && inFlight == null && holds.isEmpty()) { "Cannot reconfigure pending transmissions" }
        this.config = config
    }

    /**
     * Admits all of [requests] or none of them (the fragments of one message), each holding its time
     * on air and one place until it is sent, expires or is cleared. Null when a place or any limit
     * that applies would be exceeded. Out of [hold] the frames take that hold's time and places
     * instead, and the ledger's totals do not change.
     */
    fun offer(requests: List<Request>, nowMillis: Long, hold: Hold? = null): List<Item>? {
        requests.forEach { request ->
            require((request.kind.ledger == Ledger.REMOTE_SOLICITED) == (request.cause != null)) {
                "A cause belongs to a REMOTE_SOLICITED transmission and to no other"
            }
        }
        pruneCharges(nowMillis)
        expireHolds(nowMillis)
        if (requests.any { it.frame.size !in 1..LoRaFrame.MAX_FRAME_SIZE }) return null
        val airtimes = requests.map { config.airtimeMicros(it.frame.size) }

        if (hold != null) {
            if (hold !in holds || requests.any { it.kind.ledger != Ledger.LOCAL_ORIGIN }) return null
            if (airtimes.sum() > hold.microsLeft || requests.size > hold.placesLeft) return null
            hold.microsLeft -= airtimes.sum()
            hold.placesLeft -= requests.size
            // Nothing more can come out of it: gone, with whatever time it had left.
            if (hold.placesLeft == 0) holds.remove(hold)
        } else if (!fits(requests, airtimes, nowMillis)) {
            return null
        }

        return requests.mapIndexed { index, request ->
            Item(
                request.frame, request.kind, request.cause, request.notBeforeMillis, request.expiresAtMillis,
                airtimes[index], sequence++
            )
        }.also { queued += it }
    }

    private fun fits(requests: List<Request>, airtimes: List<Long>, nowMillis: Long): Boolean {
        val asked = requests.zip(airtimes)
        for ((ledger, entries) in asked.groupBy { it.first.kind.ledger }) {
            val micros = entries.sumOf { it.second }
            if (countedMicros(ledger, nowMillis) + reservedMicros(ledger) + micros > limitMicros(ledger)) return false
            if (reservedPlaces(ledger) + entries.size > placeLimit(ledger)) return false
        }
        for ((cause, entries) in asked.filter { it.first.cause != null }.groupBy { it.first.cause!! }) {
            val micros = entries.sumOf { it.second }
            if (countedMicros(cause, nowMillis) + reservedMicros(cause) + micros > causeLimitMicros(cause)) return false
            if (pending().count { it.cause == cause } + entries.size > PLACES_PER_CAUSE) return false
        }
        return true
    }

    /**
     * Takes [micros] of time and [places] out of LOCAL_ORIGIN as one indivisible reservation, for
     * frames that are not known yet (the later message of a handshake the user started must not be
     * stranded behind its first). Null, changing nothing, when the ledger has not that much. A hold
     * takes at least one place and is gone when its last place is used, so there are never more
     * holds than places.
     */
    fun hold(micros: Long, places: Int, nowMillis: Long, expiresAtMillis: Long): Hold? {
        require(micros > 0 && places > 0) { "A hold takes time and at least one place" }
        pruneCharges(nowMillis)
        expireHolds(nowMillis)
        val ledger = Ledger.LOCAL_ORIGIN
        // Compared alone first: added to what is already taken, a huge request would wrap around.
        if (micros > limitMicros(ledger) || places > placeLimit(ledger)) return null
        if (countedMicros(ledger, nowMillis) + reservedMicros(ledger) + micros > limitMicros(ledger)) return null
        if (reservedPlaces(ledger) + places > placeLimit(ledger)) return null
        return Hold(micros, places, expiresAtMillis).also { holds += it }
    }

    /** Gives back what is left of [hold]. Frames already queued out of it keep what they took. */
    fun release(hold: Hold) {
        holds.remove(hold)
    }

    /**
     * Drops what has expired, then takes the next frame: the ledgers in turn, starting after the one
     * served last, and within a ledger, of the frames whose delay has passed, the lowest priority
     * number; among answers of equal priority the one whose cause was served longest ago; then the
     * one admitted first. A frame still in its delay holds nothing up.
     *
     * Frames expire here and nowhere else, so that every one of them is reported: until then a frame
     * past its time keeps its reservation.
     */
    fun next(nowMillis: Long): Next {
        check(inFlight == null) { "A transmission is already in flight" }
        pruneCharges(nowMillis)
        expireHolds(nowMillis)
        val expired = queued.filter { it.expiresAtMillis <= nowMillis }
        queued.removeAll(expired)
        for (ledger in ledgersAfter(lastLedger)) {
            val item = queued
                .filter { it.kind.ledger == ledger && it.notBeforeMillis <= nowMillis }
                .minWithOrNull(order)
                ?: continue
            queued.remove(item)
            inFlight = item
            lastLedger = ledger
            return Next(item, null, expired)
        }
        val wait = (queued.map { minOf(it.notBeforeMillis, it.expiresAtMillis) } + holds.map { it.expiresAtMillis })
            .minOrNull()
        return Next(null, wait, expired)
    }

    /** The frame in flight was handed to the radio and is off the air at [endMillis]: charged from now on. */
    fun transmitted(item: Item, endMillis: Long) {
        check(inFlight === item) { "Transmission is not in flight" }
        inFlight = null
        charges.getValue(item.kind.ledger) += Charge(endMillis, item.airtimeMicros, item.cause)
    }

    /** The frame in flight never reached the radio: its time is free again at once. */
    fun notTransmitted(item: Item) {
        check(inFlight === item) { "Transmission is not in flight" }
        inFlight = null
    }

    /** Drops and returns everything queued, and every hold. The charges stay. */
    fun clear(): List<Item> {
        val dropped = queued.toList()
        queued.clear()
        holds.clear()
        return dropped
    }

    fun limitMicros(ledger: Ledger): Long = config.scaledAirtimeLimitMicros(
        when (ledger) {
            Ledger.LOCAL_ORIGIN -> LOCAL_ORIGIN_MS
            Ledger.REMOTE_SOLICITED -> REMOTE_SOLICITED_MS
            Ledger.BACKGROUND -> BACKGROUND_MS
        }
    )

    fun causeLimitMicros(cause: Cause): Long = config.scaledAirtimeLimitMicros(
        when (cause) {
            Cause.Unauthenticated -> UNAUTHENTICATED_MS
            is Cause.Validated -> PER_VALIDATED_PEER_MS
        }
    )

    fun countedMicros(ledger: Ledger, nowMillis: Long): Long {
        pruneCharges(nowMillis)
        return charges.getValue(ledger).sumOf { it.micros }
    }

    fun countedMicros(cause: Cause, nowMillis: Long): Long {
        pruneCharges(nowMillis)
        return charges.getValue(Ledger.REMOTE_SOLICITED).filter { it.cause == cause }.sumOf { it.micros }
    }

    fun reservedMicros(ledger: Ledger): Long =
        pending().filter { it.kind.ledger == ledger }.sumOf { it.airtimeMicros } +
            if (ledger == Ledger.LOCAL_ORIGIN) holds.sumOf { it.microsLeft } else 0L

    fun reservedMicros(cause: Cause): Long = pending().filter { it.cause == cause }.sumOf { it.airtimeMicros }

    fun queuedPlaces(ledger: Ledger): Int = queued.count { it.kind.ledger == ledger }

    fun holdCount(): Int = holds.size

    private fun pending(): List<Item> = queued + listOfNotNull(inFlight)

    private fun reservedPlaces(ledger: Ledger): Int =
        pending().count { it.kind.ledger == ledger } +
            if (ledger == Ledger.LOCAL_ORIGIN) holds.sumOf { it.placesLeft } else 0

    private fun placeLimit(ledger: Ledger): Int = when (ledger) {
        Ledger.LOCAL_ORIGIN -> 8
        Ledger.REMOTE_SOLICITED -> 6
        Ledger.BACKGROUND -> 2
    }

    private fun expireHolds(nowMillis: Long) {
        holds.removeAll { it.expiresAtMillis <= nowMillis }
    }

    private fun pruneCharges(nowMillis: Long) {
        charges.values.forEach { list -> list.removeAll { it.endMillis <= nowMillis - WINDOW_MILLIS } }
    }

    private val order: Comparator<Item> = compareBy<Item>(
        { it.kind.priority },
        { if (it.cause != null) lastServed(it.cause) else 0L },
        { it.sequence },
    )

    private fun lastServed(cause: Cause): Long =
        charges.getValue(Ledger.REMOTE_SOLICITED).filter { it.cause == cause }.maxOfOrNull { it.endMillis }
            ?: Long.MIN_VALUE

    private fun ledgersAfter(last: Ledger): List<Ledger> {
        val ledgers = Ledger.entries
        return List(ledgers.size) { ledgers[(last.ordinal + 1 + it) % ledgers.size] }
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L

        // Milliseconds of time on air per 60 s at SF9 and 125 kHz; scaled to the configuration in use.
        const val LOCAL_ORIGIN_MS = 4_000L
        const val REMOTE_SOLICITED_MS = 1_200L
        const val BACKGROUND_MS = 300L
        /** Inside REMOTE_SOLICITED, for everything caused by packets that did not authenticate. */
        const val UNAUTHENTICATED_MS = 750L
        /** Inside REMOTE_SOLICITED, for what one validated peer's packets cause. */
        const val PER_VALIDATED_PEER_MS = 600L

        /** Places one cause can take, waiting or in flight: one validated peer, or everything unauthenticated together. */
        const val PLACES_PER_CAUSE = 2
    }
}
