package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.protocol.ChunkReassembler
import org.slf4j.LoggerFactory

/**
 * Splits outbound frames into BLE-sized chunks and reassembles inbound ones, per device.
 *
 * A GATT characteristic write carries at most `MTU - 3` bytes, and the mesh routinely sends
 * frames larger than that (Noise handshakes, fragmented messages, announcements with a long
 * nickname). Every bitchat peer therefore layers a one-byte chunking marker over the
 * characteristic value. This class is the desktop/JVM side of that layer. Splitting is done here
 * ([chunk]); the `androidMain` and `linuxMain` transports each carry their own splitter inline, and
 * this one has to interoperate with both of them byte for byte. Putting frames back together is the
 * one shared [ChunkReassembler] that every transport uses, with its limits on what a peer can make
 * it hold; [receive], [forget], [sweep] and [stats] are that.
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
 * the reassembler's one lock. The critical section is a `memcpy` of at most a few hundred bytes and
 * a map lookup, and crucially it never *blocks*: no coroutine primitive, no `runBlocking`, nothing
 * that could park a D-Bus dispatch thread on work that needs the bus.
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

    /** The receiving half. Stamped with the wall clock, which is the clock [sweep] is handed. */
    private val reassembler = ChunkReassembler(
        idleTimeoutMillis = BUFFER_TIMEOUT_MS,
        now = System::currentTimeMillis,
        log = { logger.warn(it) },
    )

    /**
     * Counters for the transport's health log.
     *
     * `framesDropped` is the umbrella total: it counts every inbound frame or chunk we refused,
     * which is the sum of the four named buckets *plus* what has no bucket of its own (a malformed
     * START header, a frame that had to make room for another). A frame abandoned via [forget] is
     * not counted -- that is an orderly disconnect, not a failure.
     */
    val stats: ChunkReassembler.Stats
        get() = reassembler.stats

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
     * ### Length policy: strict
     *
     * A frame is delivered only when what arrived is exactly the length its START declared, and a
     * chunk that would take it past that length drops it at once ([ChunkReassembler] has the rules
     * and the limits). This costs nothing: a frame whose length is wrong is a frame with bytes missing
     * or duplicated, and `BinaryProtocol.decode` derives its own expected size from the header and
     * returns null on any shortfall. Dropping here converts a silent `decode` failure several layers
     * away into one WARN line that names the device and both lengths.
     */
    fun receive(deviceAddress: String, chunk: ByteArray): ByteArray? = reassembler.receive(deviceAddress, chunk)

    /** Discards any in-flight buffer for [deviceAddress]. Call on disconnect. Not a drop. */
    fun forget(deviceAddress: String) = reassembler.forget(deviceAddress)

    /**
     * Drops buffers that have been idle for at least [BUFFER_TIMEOUT_MS]; returns how many.
     *
     * Without this a peer that vanishes mid-frame -- a phone walking out of range, an Android
     * private address rotating between the START and the END -- leaves its partial frame pinned
     * until the next chunk from anyone arrives (the reassembler sweeps then, too).
     *
     * [nowMillis] is a parameter rather than a `System.currentTimeMillis()` call so the timeout is
     * testable without sleeping or stubbing a clock.
     */
    fun sweep(nowMillis: Long): Int = reassembler.sweep(nowMillis)

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

    }
}
