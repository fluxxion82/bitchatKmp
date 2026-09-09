package com.bitchat.bluetooth.linux

import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

/**
 * Splits outbound frames into BLE-sized chunks and reassembles inbound ones, per device.
 *
 * A GATT characteristic write carries at most `MTU - 3` bytes, and the mesh routinely sends
 * frames larger than that (Noise handshakes, fragmented messages, announcements with a long
 * nickname). Every bitchat peer therefore layers a one-byte chunking marker over the
 * characteristic value. This class is the desktop/JVM implementation of that layer; the
 * `androidMain` and `linuxMain` transports each carry their own copy inline, and this one has to
 * interoperate with both of them byte for byte.
 *
 * ## Wire format
 *
 * ```
 * first chunk    [0xFC][total length, 4 bytes big-endian][payload]
 * continuation   [0xFD][payload]
 * final chunk    [0xFE][payload]
 * unchunked      any other leading byte -- the whole buffer IS the frame
 * ```
 *
 * The unchunked case is not a fourth marker but the *absence* of one: a small frame is written
 * raw, and the receiver recognises it because a `BitchatPacket` never begins with 0xFC..0xFE
 * (byte 0 is the protocol version, 1 or 2). That is why there is no length prefix on the common
 * path and why we must never "helpfully" wrap a small frame -- an older peer that only understood
 * raw writes would still parse it, and wrapping would break that.
 *
 * ## Lenient reader, conservative writer
 *
 * The two existing peers do not agree on how big a chunk may be, so the encoder here follows the
 * more conservative of the two while the decoder accepts either. See [chunk] and [receive].
 *
 * ## Thread safety
 *
 * [receive] is called from dbus-java's method-call dispatch threads -- one per inbound D-Bus
 * message -- while [sweep] and [stats] are read from a coroutine. Everything mutable lives behind
 * a single [ReentrantLock]. The critical section is a `memcpy` of at most a few hundred bytes and
 * a map lookup, so holding one lock for all addresses costs nothing measurable and is far easier
 * to reason about than per-entry striping. Crucially it never *blocks*: no coroutine primitive,
 * no `runBlocking`, nothing that could park a D-Bus dispatch thread on work that needs the bus.
 */
class BleChunker(private val maxChunkSize: Int = DEFAULT_MAX_CHUNK_SIZE) {

    init {
        // A first chunk spends 5 bytes on its header, so anything below 6 either produces a
        // header-only START -- which `linuxMain` rejects outright, see the note in `chunk` -- or
        // makes no forward progress at all and spins forever. Fail at construction rather than
        // discovering it at the first oversized frame.
        require(maxChunkSize >= MIN_MAX_CHUNK_SIZE) {
            "maxChunkSize=$maxChunkSize is too small to make progress; need at least " +
                "$MIN_MAX_CHUNK_SIZE (5 header bytes plus at least one payload byte)"
        }
    }

    private val logger = LoggerFactory.getLogger(BleChunker::class.java)

    private val lock = ReentrantLock()

    /** In-flight reassembly state, keyed by device address. Guarded by [lock]. */
    private val buffers = HashMap<String, Reassembly>()

    // All counters guarded by [lock], so a `stats` read is a coherent snapshot rather than a set
    // of independently-drifting atomics.
    private var framesReassembled = 0L
    private var framesDropped = 0L
    private var droppedLengthMismatch = 0L
    private var droppedOrphanContinuation = 0L
    private var droppedRestartInFlight = 0L
    private var droppedAgedOut = 0L

    /**
     * Counters for the transport's health log.
     *
     * [Stats.framesDropped] is the umbrella total: it counts every inbound frame we refused,
     * which is the sum of the four named buckets *plus* malformed START headers (too short, or
     * declaring an impossible length), which have no bucket of their own. A frame abandoned via
     * [forget] is not counted -- that is an orderly disconnect, not a failure.
     */
    val stats: Stats
        get() = lock.withLock {
            Stats(
                framesReassembled = framesReassembled,
                framesDropped = framesDropped,
                droppedLengthMismatch = droppedLengthMismatch,
                droppedOrphanContinuation = droppedOrphanContinuation,
                droppedRestartInFlight = droppedRestartInFlight,
                droppedAgedOut = droppedAgedOut,
            )
        }

    /**
     * Splits [data] into chunks ready to be written to a characteristic, in order.
     *
     * The size arithmetic follows `androidMain`, deliberately, because Android phones are the
     * dominant peer and the conservative side of the disagreement:
     *
     *  - Android caps the *whole chunk* at 500 bytes, so its payloads are 495 (first) and 499
     *    (rest) -- `AndroidGattClientService.writeChunked`.
     *  - `linuxMain` caps the *payload* at 499 for every chunk including the first, so its first
     *    chunk is 504 bytes on the wire -- `BlueZGattServerService.notifyChunked`.
     *
     * 504 bytes only fits if the negotiated MTU is at least 507. It usually is on the hardware we
     * have seen, which is why nobody noticed, but it is not guaranteed, and a chunk that exceeds
     * `MTU - 3` is silently truncated by the controller rather than rejected -- the receiver then
     * gets a frame that fails its length check with no clue why. Encoding to the Android sizes
     * costs five bytes per frame and cannot produce that failure against either peer.
     *
     * Frames of [maxChunkSize] bytes or fewer are returned as a single unchunked buffer -- the
     * caller's array, unwrapped and unmarked.
     *
     * Note that an oversized frame always yields at least two chunks: the first carries only
     * `maxChunkSize - 5` bytes, which is strictly less than the frame, so a START is never also
     * the END. There is no combined START+END marker in this protocol and neither peer would know
     * what to do with one. It also means the START always carries at least one payload byte, which
     * matters because `linuxMain` drops any START shorter than 6 bytes outright
     * (`BlueZGattServerService.handleIncomingData`) -- a header-only START would vanish silently
     * and strand the whole frame.
     */
    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.size <= maxChunkSize) return listOf(data)

        val firstPayloadSize = maxChunkSize - START_HEADER_SIZE
        val restPayloadSize = maxChunkSize - CONTINUATION_HEADER_SIZE

        val totalSize = data.size
        val estimatedChunks =
            1 + (totalSize - firstPayloadSize + restPayloadSize - 1) / restPayloadSize
        val chunks = ArrayList<ByteArray>(estimatedChunks)

        var offset = 0
        var isFirst = true
        while (offset < totalSize) {
            val payloadSize =
                minOf(totalSize - offset, if (isFirst) firstPayloadSize else restPayloadSize)
            val isLast = offset + payloadSize >= totalSize

            val chunk = if (isFirst) {
                ByteArray(START_HEADER_SIZE + payloadSize).also { out ->
                    out[0] = CHUNK_START
                    // Big-endian, matching both peers. `ushr` rather than `shr` so a frame larger
                    // than 2^31 could not smear the sign bit across the header -- unreachable in
                    // practice, but the intent should be readable.
                    out[1] = ((totalSize ushr 24) and 0xFF).toByte()
                    out[2] = ((totalSize ushr 16) and 0xFF).toByte()
                    out[3] = ((totalSize ushr 8) and 0xFF).toByte()
                    out[4] = (totalSize and 0xFF).toByte()
                    data.copyInto(out, START_HEADER_SIZE, offset, offset + payloadSize)
                }
            } else {
                ByteArray(CONTINUATION_HEADER_SIZE + payloadSize).also { out ->
                    out[0] = if (isLast) CHUNK_END else CHUNK_CONTINUE
                    data.copyInto(out, CONTINUATION_HEADER_SIZE, offset, offset + payloadSize)
                }
            }

            chunks.add(chunk)
            offset += payloadSize
            isFirst = false
        }

        if (logger.isDebugEnabled) {
            logger.debug("Chunked {} bytes into {} chunks", totalSize, chunks.size)
        }
        return chunks
    }

    /**
     * Feeds one inbound chunk from [deviceAddress]; returns the complete frame when one finishes,
     * otherwise null.
     *
     * **Incoming chunk sizes are never validated against [maxChunkSize].** [maxChunkSize] governs
     * what *we* emit and nothing else. The Orange Pi build sends a 504-byte START (see [chunk]),
     * and a future peer negotiating a larger MTU may legitimately send more; rejecting on size
     * would make us the only node on the mesh that cannot talk to it. The only structural
     * requirement is that a START carry its 5-byte header.
     *
     * ### Length-mismatch policy: strict drop
     *
     * On [CHUNK_END], if what we accumulated is not exactly the length the START declared, the
     * frame is discarded and null is returned. Both existing peers are lenient here, and both
     * look accidentally so rather than deliberately:
     *
     *  - `androidMain` never compares the accumulated size against the declared one at all
     *    (`AndroidGattClientService.handleIncomingNotification`, the CHUNK_END branch) -- the
     *    declared length is parsed, stored, and then only ever used for a log line.
     *  - `linuxMain` does compare, logs "Size mismatch", and then delivers the corrupt frame to
     *    the delegate anyway (`BlueZGattServerService.handleIncomingData`) -- the `if/else` guards
     *    only which message is logged, and the delivery sits outside it.
     *
     * We are the strict receiver and the lenient sender: we emit exactly what we promise, and we
     * refuse what does not match. This costs nothing, because a frame whose length is wrong is a
     * frame with bytes missing or duplicated, and `BinaryProtocol.decode` derives its own expected
     * size from the header and returns null on any shortfall. Dropping here does not lose a frame
     * that would otherwise have been understood -- it converts a silent `decode` failure several
     * layers away into one WARN line that names the device and both lengths.
     */
    fun receive(deviceAddress: String, chunk: ByteArray): ByteArray? {
        // No leading byte, so no marker and no frame. Not counted: nothing was dropped, there was
        // never anything there. Both peers return early on this too.
        if (chunk.isEmpty()) {
            logger.debug("Ignoring empty chunk from {}", deviceAddress)
            return null
        }

        return when (chunk[0]) {
            CHUNK_START -> lock.withLock { onStart(deviceAddress, chunk) }
            CHUNK_CONTINUE -> lock.withLock { onContinuation(deviceAddress, chunk, last = false) }
            CHUNK_END -> lock.withLock { onContinuation(deviceAddress, chunk, last = true) }
            // Unchunked: the buffer is the frame. Returned as-is, without copying, and without
            // disturbing any reassembly already in flight for this address -- a small frame
            // interleaved with a large one is odd but not evidence that the large one is bad, and
            // both peers leave the buffer alone here.
            else -> chunk
        }
    }

    /** Discards any in-flight buffer for [deviceAddress]. Call on disconnect. Not a drop. */
    fun forget(deviceAddress: String) {
        val discarded = lock.withLock { buffers.remove(deviceAddress) }
        if (discarded != null) {
            logger.debug(
                "Forgot {} bytes of in-flight reassembly for {}",
                discarded.received(),
                deviceAddress,
            )
        }
    }

    /**
     * Drops buffers that have been idle for at least [BUFFER_TIMEOUT_MS]; returns how many.
     *
     * Without this a peer that vanishes mid-frame -- a phone walking out of range, an Android
     * private address rotating between the START and the END -- leaves its partial frame pinned
     * forever, and its next connection under a *new* address starts a second one. The map only
     * grows.
     *
     * [nowMillis] is a parameter rather than a `System.currentTimeMillis()` call so the timeout is
     * testable without sleeping or stubbing a clock.
     */
    fun sweep(nowMillis: Long): Int {
        val expired = lock.withLock {
            val iterator = buffers.entries.iterator()
            val removed = ArrayList<Pair<String, Reassembly>>()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMillis - entry.value.lastActivityMillis >= BUFFER_TIMEOUT_MS) {
                    iterator.remove()
                    removed.add(entry.key to entry.value)
                }
            }
            if (removed.isNotEmpty()) {
                framesDropped += removed.size
                droppedAgedOut += removed.size
            }
            removed
        }

        for ((address, buffer) in expired) {
            logger.warn(
                "Dropping stale reassembly for {}: {} of {} declared bytes, idle {} ms",
                address,
                buffer.received(),
                buffer.declaredLength,
                nowMillis - buffer.lastActivityMillis,
            )
        }
        return expired.size
    }

    // -- internals, all called with [lock] held ---------------------------------------------

    private fun onStart(deviceAddress: String, chunk: ByteArray): ByteArray? {
        if (chunk.size < START_HEADER_SIZE) {
            framesDropped++
            logger.warn(
                "Dropping malformed START from {}: {} bytes, need at least {}",
                deviceAddress,
                chunk.size,
                START_HEADER_SIZE,
            )
            return null
        }

        val declaredLength = ((chunk[1].toInt() and 0xFF) shl 24) or
            ((chunk[2].toInt() and 0xFF) shl 16) or
            ((chunk[3].toInt() and 0xFF) shl 8) or
            (chunk[4].toInt() and 0xFF)

        if (declaredLength <= 0 || declaredLength > MAX_DECLARED_FRAME_BYTES) {
            framesDropped++
            logger.warn(
                "Dropping START from {} declaring an implausible length of {} bytes",
                deviceAddress,
                declaredLength,
            )
            return null
        }

        // The old buffer is only abandoned once the new header has been validated. A garbled
        // START is far more likely to be corruption on the link than a genuine restart, and there
        // is no reason to let it destroy a transfer that may still complete.
        val previous = buffers.remove(deviceAddress)
        if (previous != null) {
            framesDropped++
            droppedRestartInFlight++
            logger.warn(
                "New START from {} while {} of {} bytes were still in flight; abandoning the old frame",
                deviceAddress,
                previous.received(),
                previous.declaredLength,
            )
        }

        val reassembly = Reassembly(declaredLength, System.currentTimeMillis())
        reassembly.append(chunk, START_HEADER_SIZE)
        buffers[deviceAddress] = reassembly

        if (logger.isDebugEnabled) {
            logger.debug(
                "START from {}: expecting {} bytes, {} in this chunk",
                deviceAddress,
                declaredLength,
                reassembly.received(),
            )
        }
        return null
    }

    private fun onContinuation(deviceAddress: String, chunk: ByteArray, last: Boolean): ByteArray? {
        val reassembly = buffers[deviceAddress]
        if (reassembly == null) {
            framesDropped++
            droppedOrphanContinuation++
            logger.warn(
                "Dropping {} chunk from {} with no frame in flight",
                if (last) "END" else "CONTINUE",
                deviceAddress,
            )
            return null
        }

        reassembly.append(chunk, CONTINUATION_HEADER_SIZE)
        reassembly.lastActivityMillis = System.currentTimeMillis()

        if (!last) {
            // A runaway sender that never sends an END would otherwise grow this buffer without
            // bound; the declared length is capped, so anything past the same ceiling is not a
            // frame we would have accepted even if it did terminate correctly. Charged to the
            // length-mismatch bucket because that is what it is -- more bytes than were promised.
            if (reassembly.received() > MAX_DECLARED_FRAME_BYTES) {
                buffers.remove(deviceAddress)
                framesDropped++
                droppedLengthMismatch++
                logger.warn(
                    "Dropping runaway reassembly from {}: {} bytes with no END, ceiling is {}",
                    deviceAddress,
                    reassembly.received(),
                    MAX_DECLARED_FRAME_BYTES,
                )
                return null
            }
            // Per-chunk logging is on the hot path: a 5 KB frame is eleven of these. Guarded so
            // the argument boxing does not happen when TRACE is off.
            if (logger.isTraceEnabled) {
                logger.trace(
                    "CONTINUE from {}: {} of {} bytes",
                    deviceAddress,
                    reassembly.received(),
                    reassembly.declaredLength,
                )
            }
            return null
        }

        buffers.remove(deviceAddress)
        val frame = reassembly.toByteArray()

        if (frame.size != reassembly.declaredLength) {
            framesDropped++
            droppedLengthMismatch++
            logger.warn(
                "Dropping frame from {}: START declared {} bytes, reassembled {}",
                deviceAddress,
                reassembly.declaredLength,
                frame.size,
            )
            return null
        }

        framesReassembled++
        if (logger.isDebugEnabled) {
            logger.debug("Reassembled {} bytes from {}", frame.size, deviceAddress)
        }
        return frame
    }

    /**
     * One partially-received frame.
     *
     * The accumulator deliberately does *not* pre-size itself to [declaredLength]: that number
     * comes off the wire and would let a peer make us allocate on demand. It grows against the
     * bytes actually delivered instead, and the ceiling in [MAX_DECLARED_FRAME_BYTES] bounds it.
     */
    private class Reassembly(val declaredLength: Int, var lastActivityMillis: Long) {
        private val accumulator = ByteArrayOutputStream()

        fun append(chunk: ByteArray, from: Int) {
            if (chunk.size > from) accumulator.write(chunk, from, chunk.size - from)
        }

        fun received(): Int = accumulator.size()

        fun toByteArray(): ByteArray = accumulator.toByteArray()
    }

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

        /** Matches `AndroidGattClientService.CHUNK_SIZE`; see [chunk] for why we follow Android. */
        const val DEFAULT_MAX_CHUNK_SIZE = 500

        const val BUFFER_TIMEOUT_MS = 30_000L

        /** `[marker][4-byte big-endian total length]`. */
        private const val START_HEADER_SIZE = 5

        /** `[marker]`. */
        private const val CONTINUATION_HEADER_SIZE = 1

        /** Five header bytes plus at least one payload byte. See the `init` block. */
        private const val MIN_MAX_CHUNK_SIZE = START_HEADER_SIZE + 1

        /**
         * Largest total length we will believe from a START header: 512 KiB.
         *
         * The bound is not arbitrary and is not about `BinaryProtocol` -- a v2 packet carries a
         * 4-byte payload length and could in principle describe far more. It comes from what the
         * link can actually deliver inside our own [BUFFER_TIMEOUT_MS]. Both peers pace chunks
         * 25 ms apart (`CHUNK_DELAY_MS` in each), so 30 seconds buys roughly 1200 chunks of about
         * 499 payload bytes -- call it 600 KB, and that assumes not one retransmit. A START
         * declaring more than half a megabyte is describing a transfer that this very class would
         * age out before it could finish, so believing it only buys us a buffer that is guaranteed
         * to be swept. Rejecting at the header instead means we never accumulate it at all.
         */
        private const val MAX_DECLARED_FRAME_BYTES = 512 * 1024
    }
}
