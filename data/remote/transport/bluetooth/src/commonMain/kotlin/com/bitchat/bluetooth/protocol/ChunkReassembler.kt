package com.bitchat.bluetooth.protocol

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeSource

private val monotonicOrigin = TimeSource.Monotonic.markNow()
private fun monotonicMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

/**
 * Puts chunked BLE frames back together, per link, for every GATT transport.
 *
 * A characteristic write carries a few hundred bytes and the mesh sends frames larger than that, so each
 * transport layers a one-byte marker over the characteristic value:
 *
 * ```
 * first chunk    [0xFC][total length, 4 bytes big-endian][payload]
 * continuation   [0xFD][payload]
 * final chunk    [0xFE][payload]
 * unchunked      any other leading byte -- the whole buffer IS the frame
 * ```
 *
 * The receiving half of that used to be written out in each transport, and most of the copies limited
 * nothing: a connected peer, which nothing has authenticated at this layer, could send continuations for
 * ever. This is the one implementation, and what it can be made to hold is bounded whatever arrives.
 *
 * ## The bound
 *
 * - A frame declares its length in its first chunk. A length outside `1..`[maxFrameBytes], or a first
 *   chunk that already carries more than it declares, starts nothing.
 * - Every chunk is checked BEFORE it is copied: one that would take a frame past the length it declared
 *   drops the frame. A final chunk delivers the frame only when the length is exactly the declared one.
 * - A frame is kept in blocks that are added as bytes arrive, never ahead of them by more than a block
 *   and never past the declared length. Nothing is copied to grow, so growing needs no memory but the
 *   new block. All the blocks of all frames together take at most [maxBufferedBytes], at every moment.
 *   (A completed frame is put into one array for the caller: one frame's worth more, for that moment.)
 * - The total holds [MAX_BUFFERED_FRAMES] frames of the largest size at once, on their way and complete;
 *   the frames this app sends are a tenth of that size at most. When the total is reached, the frame
 *   whose blocks take the most gives way, and a frame that asks for room while its blocks would take as
 *   much as any other's is the one refused. What is compared is what is charged, the blocks, not the
 *   bytes received. So a frame is only ever pushed out by frames that each take less than it and that,
 *   together with it, fill the total: a few links with large frames cannot push out small ones, links
 *   that left large buffers behind go first, and a late arrival does not displace its equal. (A block
 *   is at most 1 KiB or as large as what its frame already held, so past its first block a link has
 *   delivered at least half of what it takes.)
 * - A frame that got no bytes for [idleTimeoutMillis] is dropped the next time anything arrives, so
 *   nothing is kept for long for a link that went away without the platform saying so (the desktop
 *   macOS bridge and CoreBluetooth's peripheral role report no disconnect). A chunk with no payload
 *   does not count: it cannot keep a frame alive.
 * - At most [maxInFlight] frames are in flight. That number bounds the table, not the links: it is far
 *   more than a device has links, and more than connections made one after another can leave behind
 *   inside the idle timeout. Past it the frame idle longest goes.
 *
 * The key is the GATT link's address: a real connection, not a field a packet can claim. A transport
 * has one of these for its server role and one for its client role, so a process holds at most twice
 * [maxBufferedBytes] of partial frames.
 *
 * ## What a drop does
 *
 * The frame is gone, [stats] counts it and [log] gets a line (at most one a second, so a peer cannot
 * write the log full either). The link is left up: this class sits below anything that can disconnect,
 * later chunks of a dropped frame find nothing in flight and cost a map lookup each, and an honest peer
 * whose chunk was cut short on the way must not lose its connection for it.
 *
 * Safe to call from any thread; nothing in here suspends or waits on anything but its own lock.
 */
class ChunkReassembler(
    private val maxFrameBytes: Int = MAX_MESH_FRAME_BYTES,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val maxBufferedBytes: Long = MAX_BUFFERED_FRAMES * maxFrameBytes.toLong(),
    private val idleTimeoutMillis: Long = IDLE_TIMEOUT_MILLIS,
    private val now: () -> Long = ::monotonicMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()

    /** Frames in flight, by link. Guarded by [lock], as is everything below. */
    private val frames = mutableMapOf<String, Reassembly>()

    /** The sum of the blocks of [frames]: what is charged against [maxBufferedBytes]. */
    private var bufferedBytes = 0L

    private var reassembled = 0L
    private var dropped = 0L
    private var droppedLengthMismatch = 0L
    private var droppedOrphan = 0L
    private var droppedRestart = 0L
    private var droppedAgedOut = 0L

    private var lastLogMillis: Long? = null
    private var dropsNotLogged = 0

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
        require(maxInFlight > 0) { "maxInFlight must be positive" }
        require(maxBufferedBytes >= maxFrameBytes) { "maxBufferedBytes must hold at least one frame" }
        require(idleTimeoutMillis >= 0) { "idleTimeoutMillis must not be negative" }
    }

    /** The frame [chunk] completes; [chunk] itself when it carries no chunk marker; null otherwise. */
    fun receive(deviceAddress: String, chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        return synchronized(lock) {
            expireIdle(now())
            when (chunk[0]) {
                CHUNK_START -> onStart(deviceAddress, chunk)
                CHUNK_CONTINUE -> onContinuation(deviceAddress, chunk, isEnd = false)
                CHUNK_END -> onContinuation(deviceAddress, chunk, isEnd = true)
                // The buffer is the frame, and a frame in flight for this link is left alone. One GATT
                // value cannot be this long; the check is here so no path into the mesh skips the limit.
                else -> if (chunk.size <= maxFrameBytes) {
                    chunk
                } else {
                    drop(deviceAddress, "an unchunked buffer of ${chunk.size} bytes is over the limit of $maxFrameBytes")
                    null
                }
            }
        }
    }

    /** Discards what is in flight for [deviceAddress]. For a link that is known to be gone; not a drop. */
    fun forget(deviceAddress: String) {
        synchronized(lock) { remove(deviceAddress) }
    }

    /** Discards everything in flight. For a transport shutting down; not a drop. */
    fun clear() {
        synchronized(lock) {
            frames.clear()
            bufferedBytes = 0
        }
    }

    /**
     * Drops the frames whose last chunk is [idleTimeoutMillis] old at [nowMillis]; returns how many.
     * [receive] does this on its own with the current time; this is for a caller with a clock of its own.
     */
    fun sweep(nowMillis: Long): Int = synchronized(lock) { expireIdle(nowMillis) }

    /** Counters since construction, as one coherent snapshot. */
    val stats: Stats
        get() = synchronized(lock) {
            Stats(reassembled, dropped, droppedLengthMismatch, droppedOrphan, droppedRestart, droppedAgedOut)
        }

    /** How many frames are in flight, and the bytes their buffers take: for tests of the bound. */
    internal val inFlight: Int get() = synchronized(lock) { frames.size }
    internal val buffered: Long get() = synchronized(lock) { bufferedBytes }

    private fun onStart(address: String, chunk: ByteArray): ByteArray? {
        if (chunk.size < START_HEADER_BYTES) {
            drop(address, "a first chunk of ${chunk.size} bytes is shorter than its header")
            return null
        }
        val declared = ((chunk[1].toInt() and 0xFF) shl 24) or
            ((chunk[2].toInt() and 0xFF) shl 16) or
            ((chunk[3].toInt() and 0xFF) shl 8) or
            (chunk[4].toInt() and 0xFF)
        val payload = chunk.size - START_HEADER_BYTES
        if (declared !in 1..maxFrameBytes) {
            drop(address, "a first chunk declares $declared bytes, outside 1..$maxFrameBytes")
            return null
        }
        if (payload > declared) {
            droppedLengthMismatch++
            drop(address, "a first chunk carries $payload bytes and declares $declared")
            return null
        }

        // Only a first chunk that passed everything above replaces a frame this link had in flight: a
        // garbled one is more likely damage on the link than a restart, and must not cost a transfer
        // that may still complete.
        remove(address)?.let { previous ->
            droppedRestart++
            drop(address, "a new frame started with ${previous.size} of ${previous.declared} bytes of the last one received")
        }
        while (frames.size >= maxInFlight) {
            if (!dropIdlest(why = "$maxInFlight frames are in flight")) break
        }

        val frame = Reassembly(declared, now())
        if (!makeRoom(frame, payload, address)) {
            drop(address, "the other frames in flight leave no room for a first chunk of $payload bytes")
            return null
        }
        frame.append(chunk, START_HEADER_BYTES)
        frames[address] = frame
        return null
    }

    private fun onContinuation(address: String, chunk: ByteArray, isEnd: Boolean): ByteArray? {
        val kind = if (isEnd) "final" else "continuation"
        val frame = frames[address]
        if (frame == null) {
            droppedOrphan++
            drop(address, "a $kind chunk arrived with no frame in flight")
            return null
        }
        val payload = chunk.size - CONTINUATION_HEADER_BYTES
        if (payload > frame.declared - frame.size) {
            remove(address)
            droppedLengthMismatch++
            drop(address, "a $kind chunk takes the frame to ${frame.size.toLong() + payload} bytes, past the ${frame.declared} declared")
            return null
        }
        if (!makeRoom(frame, payload, address)) {
            remove(address)
            drop(address, "the other frames in flight leave no room for this one to pass ${frame.size} of ${frame.declared} bytes")
            return null
        }
        frame.append(chunk, CONTINUATION_HEADER_BYTES)
        // Only bytes keep a frame alive: an empty chunk is free to send and would hold a buffer for ever.
        if (payload > 0) frame.lastByteMillis = now()
        if (!isEnd) return null

        remove(address)
        if (frame.size != frame.declared) {
            droppedLengthMismatch++
            drop(address, "the frame ended at ${frame.size} of ${frame.declared} declared bytes")
            return null
        }
        reassembled++
        return frame.toByteArray()
    }

    /**
     * Charges the blocks [frame] needs to take [more] bytes; false, and nothing charged, when it is
     * [frame] that has to give way.
     *
     * When the blocks would take the total past [maxBufferedBytes], the frame whose blocks take the most
     * is dropped, [frame] counted with the blocks it is about to have, until they fit. If that is
     * [frame] itself, or [frame] would take as much as the largest other, [frame] is the one refused:
     * what is already here is not pushed out by its equal. Among other frames taking the same, the one
     * idle longest goes. Blocks are compared, as blocks are what is charged: comparing bytes received
     * would let frames with half-empty blocks outlast a fuller, smaller one.
     */
    private fun makeRoom(frame: Reassembly, more: Int, address: String): Boolean {
        val capacity = frame.capacityFor(more)
        val growth = capacity - frame.capacity
        if (growth <= 0) return true
        while (bufferedBytes + growth > maxBufferedBytes) {
            var largest: Map.Entry<String, Reassembly>? = null
            for (entry in frames) {
                if (entry.key == address) continue
                val best = largest
                if (best == null ||
                    entry.value.capacity > best.value.capacity ||
                    (entry.value.capacity == best.value.capacity && entry.value.lastByteMillis < best.value.lastByteMillis)
                ) {
                    largest = entry
                }
            }
            val found = largest ?: return false
            if (capacity >= found.value.capacity) return false
            remove(found.key)
            drop(
                found.key,
                "partial frames take ${bufferedBytes + found.value.capacity} of $maxBufferedBytes bytes and this one took the most " +
                    "(${found.value.capacity} for ${found.value.size} of ${found.value.declared} bytes)",
            )
        }
        bufferedBytes += growth
        return true
    }

    /** Drops the frame idle longest: for a full table, where what is left behind is what should go. */
    private fun dropIdlest(why: String): Boolean {
        var idlest: Map.Entry<String, Reassembly>? = null
        for (entry in frames) {
            if (idlest == null || entry.value.lastByteMillis < idlest.value.lastByteMillis) idlest = entry
        }
        val found = idlest ?: return false
        remove(found.key)
        drop(found.key, "$why and this one was idle longest (${found.value.size} of ${found.value.declared} bytes)")
        return true
    }

    private fun expireIdle(time: Long): Int {
        if (frames.isEmpty()) return 0
        var expired: MutableList<String>? = null
        for ((address, frame) in frames) {
            if (time - frame.lastByteMillis >= idleTimeoutMillis) {
                expired = (expired ?: mutableListOf()).also { it += address }
            }
        }
        for (address in expired ?: return 0) {
            val frame = remove(address) ?: continue
            droppedAgedOut++
            drop(address, "no bytes for ${time - frame.lastByteMillis} ms (${frame.size} of ${frame.declared} bytes)")
        }
        return expired.size
    }

    private fun remove(address: String): Reassembly? =
        frames.remove(address)?.also { bufferedBytes -= it.capacity }

    /** Counts a dropped frame or chunk, and logs it: at most one line a second, with a count of the rest. */
    private fun drop(address: String, what: String) {
        dropped++
        val time = now()
        val last = lastLogMillis
        if (last != null && time - last in 0 until LOG_INTERVAL_MILLIS) {
            dropsNotLogged++
            return
        }
        val skipped = if (dropsNotLogged > 0) " ($dropsNotLogged more drops not logged)" else ""
        lastLogMillis = time
        dropsNotLogged = 0
        log("Dropping chunked data from $address: $what$skipped")
    }

    /**
     * One partly received frame, kept in blocks. A block is added when the bytes that arrived need it:
     * as large as what the frame already holds (so small frames stay small and large ones take few
     * blocks), between [SMALLEST_BLOCK_BYTES] and [LARGEST_BLOCK_BYTES], and never past [declared].
     */
    private class Reassembly(val declared: Int, var lastByteMillis: Long) {
        private val blocks = ArrayList<ByteArray>()

        /** The bytes all blocks can hold: what this frame is charged. */
        var capacity = 0
            private set

        /** The bytes received. */
        var size = 0
            private set

        private fun nextBlockBytes(capacity: Int): Int =
            minOf(declared - capacity, maxOf(SMALLEST_BLOCK_BYTES, minOf(capacity, LARGEST_BLOCK_BYTES)))

        /** What [capacity] becomes once [more] bytes are appended. The caller has checked they fit [declared]. */
        fun capacityFor(more: Int): Int {
            var needed = capacity
            while (needed < size + more) needed += nextBlockBytes(needed)
            return needed
        }

        fun append(chunk: ByteArray, from: Int) {
            var offset = from
            while (offset < chunk.size) {
                if (size == capacity) {
                    val block = ByteArray(nextBlockBytes(capacity))
                    blocks += block
                    capacity += block.size
                }
                val block = blocks.last()
                val used = block.size - (capacity - size)
                val count = minOf(chunk.size - offset, block.size - used)
                chunk.copyInto(block, used, offset, offset + count)
                offset += count
                size += count
            }
        }

        /** The frame as one array. Called once, when [size] has reached [declared]. */
        fun toByteArray(): ByteArray {
            blocks.singleOrNull()?.let { if (it.size == size) return it }
            val frame = ByteArray(size)
            var offset = 0
            for (block in blocks) {
                val count = minOf(block.size, size - offset)
                block.copyInto(frame, offset, 0, count)
                offset += count
            }
            return frame
        }
    }

    /**
     * [framesDropped] counts every frame or chunk refused; the four named counts are the parts of it
     * that have a cause of their own. A frame discarded by [forget] or [clear] is not a drop.
     */
    data class Stats(
        val framesReassembled: Long,
        val framesDropped: Long,
        val droppedLengthMismatch: Long,
        val droppedOrphanContinuation: Long,
        val droppedRestartInFlight: Long,
        val droppedAgedOut: Long,
    )

    companion object {
        const val CHUNK_START: Byte = 0xFC.toByte()
        const val CHUNK_CONTINUE: Byte = 0xFD.toByte()
        const val CHUNK_END: Byte = 0xFE.toByte()

        /**
         * The size of the table, not a number of links. Setting up a link and writing one chunk takes
         * tens of milliseconds at the very least, and an entry that gets no further chunk is gone after
         * [IDLE_TIMEOUT_MILLIS], so connections made one after another cannot leave this many behind.
         */
        const val MAX_IN_FLIGHT: Int = 1024

        /**
         * How many frames of the largest size one reassembler holds at once: that many links can each be
         * sending one and none is refused. A device does not have many more links than this in one role,
         * and no build of this app sends a frame of more than about a tenth of the largest size.
         */
        const val MAX_BUFFERED_FRAMES: Int = 8

        /** Since a frame's last chunk; chunks of a transfer in progress arrive tens of milliseconds apart. */
        const val IDLE_TIMEOUT_MILLIS: Long = 30_000

        private const val START_HEADER_BYTES = 5
        private const val CONTINUATION_HEADER_BYTES = 1
        private const val SMALLEST_BLOCK_BYTES = 1024
        private const val LARGEST_BLOCK_BYTES = 64 * 1024
        private const val LOG_INTERVAL_MILLIS = 1_000L
    }
}
