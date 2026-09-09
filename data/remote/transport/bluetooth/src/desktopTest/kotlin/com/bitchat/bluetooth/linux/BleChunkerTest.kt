package com.bitchat.bluetooth.linux

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chunking protocol the mesh already speaks on the wire.
 *
 * Nothing here is invented: every expectation is read off the two peers that are already shipping.
 *
 *  - Android (`AndroidGattClientService`/`AndroidGattServerService`) caps a WHOLE chunk at 500
 *    bytes. A first chunk is `[0xFC][4-byte big-endian total][payload]`, so its payload can only be
 *    495; a continuation or final chunk is `[type][payload]`, so its payload can be 499. Anything
 *    that fits in 500 bytes goes out unframed, with no type byte at all.
 *  - The Orange Pi (`BlueZGattServerService`) caps the PAYLOAD at 499 for every chunk including the
 *    first, so its first chunk is 504 bytes on the wire -- four bytes wider than Android's cap. It
 *    also refuses a START shorter than 6 bytes.
 *
 * So the encoder has to follow Android, and the decoder has to be looser than the encoder or the
 * Pi's own traffic would be unreadable. Where the two peers merely log and carry on -- a final
 * chunk whose total does not match the length the START declared -- we drop the frame instead: a
 * half-delivered packet decoded as if it were whole is worse than a packet that never arrived.
 */
class BleChunkerTest {

    // Addresses the device journal recorded for real peers; only their distinctness matters here.
    private val phone = "5C:00:46:51:7B:0E"
    private val pi = "63:F5:53:74:B0:6F"
    private val third = "74:6D:62:4B:4E:57"

    // The header arithmetic both peers' encoders share.
    private val startHeader = 5      // [type][4-byte total length]
    private val laterHeader = 1      // [type]

    // At the default cap of 500 a whole chunk must fit, so the payloads are what is left over.
    private val firstPayloadAt500 = BleChunker.DEFAULT_MAX_CHUNK_SIZE - startHeader   // 495
    private val laterPayloadAt500 = BleChunker.DEFAULT_MAX_CHUNK_SIZE - laterHeader   // 499

    // ------------------------------------------------------------------------------------------
    // The constants on the wire
    // ------------------------------------------------------------------------------------------

    @Test
    fun theMarkersAreTheBytesBothPeersAlreadyPutOnTheWire() {
        // Android writes these as 0xFC/0xFD/0xFE, the Pi as -4/-3/-2. They are the same bytes, and
        // they are deliberately high so they cannot collide with a packet type (0x01 ANNOUNCE, ...).
        assertEquals(0xFC.toByte(), BleChunker.CHUNK_START, "a peer reading 0xFC expects a START")
        assertEquals(0xFD.toByte(), BleChunker.CHUNK_CONTINUE, "a peer reading 0xFD expects a CONTINUE")
        assertEquals(0xFE.toByte(), BleChunker.CHUNK_END, "a peer reading 0xFE expects an END")
        assertEquals((-4).toByte(), BleChunker.CHUNK_START, "the Pi spells the same marker -4")
        assertEquals((-3).toByte(), BleChunker.CHUNK_CONTINUE, "the Pi spells the same marker -3")
        assertEquals((-2).toByte(), BleChunker.CHUNK_END, "the Pi spells the same marker -2")

        assertEquals(500, BleChunker.DEFAULT_MAX_CHUNK_SIZE, "Android's CHUNK_SIZE is 500")
        assertEquals(30_000L, BleChunker.BUFFER_TIMEOUT_MS, "a half-frame must not be held forever")
    }

    // ------------------------------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------------------------------

    @Test
    fun aBufferThatFitsInOneChunkGoesOutWithNoFramingAtAll() {
        val chunker = BleChunker()

        // Android's rule is `data.size <= CHUNK_SIZE`, so 500 bytes is still one bare write. A
        // receiver that saw a type byte here would hand the frame up with five bytes of rubbish
        // stripped off the front.
        val data = payload(BleChunker.DEFAULT_MAX_CHUNK_SIZE)
        val chunks = chunker.chunk(data)

        assertEquals(1, chunks.size, "500 bytes is the largest buffer that needs no framing")
        assertContentEquals(data, chunks[0], "an unframed buffer must go out byte for byte")
    }

    @Test
    fun oneByteOverTheCapIsSplitInTwo() {
        val chunker = BleChunker()

        // 501 is the first size that cannot be written bare. The first chunk can only carry
        // 500 - 5 = 495 payload bytes, which leaves 6 for the final chunk.
        val data = payload(BleChunker.DEFAULT_MAX_CHUNK_SIZE + 1)
        val chunks = chunker.chunk(data)

        assertEquals(2, chunks.size, "501 bytes needs a START and an END")

        assertEquals(BleChunker.CHUNK_START, chunks[0][0], "the first chunk announces the transfer")
        assertEquals(500, chunks[0].size, "5 header bytes + 495 payload is exactly the 500-byte cap")
        assertContentEquals(
            data.copyOfRange(0, firstPayloadAt500),
            chunks[0].copyOfRange(startHeader, chunks[0].size),
            "the first chunk carries the first 495 bytes and nothing more"
        )

        assertEquals(BleChunker.CHUNK_END, chunks[1][0], "the last chunk must be an END, not a CONTINUE")
        assertEquals(1 + 6, chunks[1].size, "6 bytes are left over after the first 495")
        assertContentEquals(
            data.copyOfRange(firstPayloadAt500, data.size),
            chunks[1].copyOfRange(laterHeader, chunks[1].size),
            "the tail of the buffer must survive the split"
        )
    }

    @Test
    fun theFirstChunkDeclaresTheTotalLengthBigEndian() {
        val chunker = BleChunker()

        // 66051 is 0x00010203, so every one of the four length bytes is different: a little-endian
        // writer, or one that only filled the low two bytes, cannot pass this.
        val data = payload(0x00010203)
        val start = chunker.chunk(data)[0]

        assertEquals(BleChunker.CHUNK_START, start[0])
        assertEquals(0x00.toByte(), start[1], "byte 1 is the most significant byte of the length")
        assertEquals(0x01.toByte(), start[2])
        assertEquals(0x02.toByte(), start[3])
        assertEquals(0x03.toByte(), start[4], "byte 4 is the least significant byte of the length")
        assertEquals(data.size, declaredLength(start), "both peers read these four bytes back as the total")
    }

    @Test
    fun theFinalChunkIsMarkedEndEvenWhenItIsCompletelyFull() {
        val chunker = BleChunker()

        // 495 + 499 is the largest buffer that still fits in two chunks; the second one is full to
        // the cap. An encoder that only reaches for END when the last chunk is short would emit a
        // CONTINUE here and the frame would never be delivered.
        val data = payload(firstPayloadAt500 + laterPayloadAt500)
        val chunks = chunker.chunk(data)

        assertEquals(2, chunks.size, "994 bytes is the last size that fits in two chunks")
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size, "5 + 495 and 1 + 499 both land exactly on the cap")
        assertEquals(BleChunker.CHUNK_END, chunks[1][0], "a full last chunk is still the last chunk")
    }

    @Test
    fun oneByteMoreThanTwoChunksHoldNeedsAThird() {
        val chunker = BleChunker()

        // 995 = 495 + 499 + 1. The middle chunk is a CONTINUE and the END carries a single byte.
        val data = payload(firstPayloadAt500 + laterPayloadAt500 + 1)
        val chunks = chunker.chunk(data)

        assertEquals(3, chunks.size, "995 bytes spills into a third chunk")
        assertEquals(
            listOf(BleChunker.CHUNK_START, BleChunker.CHUNK_CONTINUE, BleChunker.CHUNK_END),
            chunks.map { it[0] },
            "only the middle chunk may be a CONTINUE"
        )
        assertEquals(2, chunks[2].size, "the leftover byte travels with a one-byte header")
    }

    @Test
    fun everyEncodingIsWellFormedAcrossTheSizeBoundaries() {
        // The sizes that matter are the ones on either side of "fits in one chunk" and on either
        // side of each payload capacity, computed per cap rather than hard-coded.
        for (cap in capsUnderTest) {
            for (size in interestingSizes(cap)) {
                assertWellFormed(BleChunker(cap).chunk(payload(size)), payload(size), cap)
            }
        }
    }

    @Test
    fun aSmallCapSplitsOnItsOwnArithmetic() {
        // A cap of 10 leaves 5 payload bytes in a START and 9 in anything else. This is the only
        // chunker in the tree whose cap is a parameter, so an implementation that quietly assumed
        // 495/499 would still pass every default-sized test and fail here.
        val chunker = BleChunker(10)

        assertEquals(1, chunker.chunk(payload(10)).size, "10 bytes still fits in one 10-byte chunk")

        val justOver = chunker.chunk(payload(11))
        assertEquals(2, justOver.size, "11 bytes cannot fit, so it splits")
        assertEquals(10, justOver[0].size, "5 header + 5 payload fills the cap")
        assertEquals(1 + 6, justOver[1].size, "6 bytes are left over")

        assertEquals(2, chunker.chunk(payload(5 + 9)).size, "14 bytes is the last two-chunk buffer")
        assertEquals(3, chunker.chunk(payload(5 + 9 + 1)).size, "15 bytes needs a third chunk")
        assertEquals(3, chunker.chunk(payload(5 + 9 + 9)).size, "23 bytes is the last three-chunk buffer")
        assertEquals(4, chunker.chunk(payload(5 + 9 + 9 + 1)).size, "24 bytes needs a fourth")
    }

    @Test
    fun theSmallestUsableCapStillMakesProgress() {
        // Six is as low as the format goes: five header bytes plus one payload byte. Every chunk
        // still has to fit, and the encoder still has to advance by at least a byte at a time.
        val chunker = BleChunker(6)
        val data = payload(7)

        val chunks = chunker.chunk(data)

        assertWellFormed(chunks, data, 6)
        // A START can carry one byte and a CONTINUE five, so seven bytes take three chunks: there
        // is no two-chunk encoding at this cap at all.
        assertEquals(3, chunks.size, "1 + 5 + 1 is the only way seven bytes fit under a 6-byte cap")
    }

    @Test
    fun everyStartWeEmitIsLongEnoughForTheOrangePiToAccept() {
        // BlueZGattServerService rejects a START of fewer than 6 bytes outright. A START with an
        // empty payload is 5 bytes, so any cap or size that produced one would be silently dropped
        // by the Pi -- the frame would stall and time out rather than fail loudly.
        for (cap in capsUnderTest) {
            for (size in interestingSizes(cap)) {
                val chunks = BleChunker(cap).chunk(payload(size))
                val start = chunks.firstOrNull { it[0] == BleChunker.CHUNK_START } ?: continue
                assertTrue(
                    start.size >= 6,
                    "a START of ${start.size} bytes (cap $cap, $size bytes) is dropped by the Orange Pi"
                )
            }
        }
    }

    @Test
    fun anEmptyBufferIsNeverFramed() {
        // A framed empty buffer is unreceivable: both peers return early on an empty notification,
        // and a START with no payload is below the Pi's 6-byte floor. Whether the encoder answers
        // with nothing or with one empty write, it must not put a type byte on the wire.
        val chunks = BleChunker().chunk(ByteArray(0))

        assertTrue(chunks.size <= 1, "an empty buffer cannot need more than one write")
        chunks.forEach { assertEquals(0, it.size, "an empty buffer must not grow a header") }
    }

    // ------------------------------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyBufferSurvivesTheRoundTrip() {
        for (cap in capsUnderTest) {
            for (size in interestingSizes(cap)) {
                val chunker = BleChunker(cap)
                val data = payload(size)
                val frame = feed(chunker, phone, chunker.chunk(data))
                assertNotNull(frame, "a $size-byte buffer at cap $cap never completed")
                assertContentEquals(data, frame, "a $size-byte buffer at cap $cap came back changed")
            }
        }
    }

    @Test
    fun onlyTheFinalChunkCompletesAFrame() {
        val chunker = BleChunker()
        val data = payload(1200)
        val chunks = chunker.chunk(data)
        assertEquals(3, chunks.size, "1200 bytes is 495 + 499 + 206")

        assertNull(chunker.receive(phone, chunks[0]), "a START alone is not a frame")
        assertNull(chunker.receive(phone, chunks[1]), "a CONTINUE alone is not a frame")
        assertContentEquals(data, chunker.receive(phone, chunks[2]), "the END completes it")
        assertEquals(1L, chunker.stats.framesReassembled)
    }

    @Test
    fun aChunkedTransferIsCountedOncePerFrameAndNotOncePerChunk() {
        val chunker = BleChunker()
        repeat(4) { feed(chunker, phone, chunker.chunk(payload(2000, seed = it))) }

        assertEquals(4L, chunker.stats.framesReassembled, "four frames arrived, whatever the chunk count")
        assertEquals(0L, chunker.stats.framesDropped, "nothing about a clean transfer is a failure")
    }

    // ------------------------------------------------------------------------------------------
    // Reading what the other implementations send
    // ------------------------------------------------------------------------------------------

    @Test
    fun theOrangePis504ByteFirstChunkIsAccepted() {
        // BlueZGattServerService caps the payload, not the chunk, so its START is 5 + 499 = 504
        // bytes -- wider than our own 500-byte cap. Refusing it because "it is too big" would make
        // every large packet from the Pi undeliverable.
        val chunker = BleChunker()
        val data = payload(998)
        val chunks = piStream(data)

        assertEquals(504, chunks[0].size, "the Pi's first chunk is four bytes over our own cap")
        assertEquals(500, chunks[1].size, "its later chunks are 1 + 499")

        val frame = feed(chunker, pi, chunks)
        assertContentEquals(data, frame, "the Pi's traffic must reassemble unchanged")
    }

    @Test
    fun aThreeChunkStreamFromTheOrangePiReassemblesInOrder() {
        val chunker = BleChunker()
        val data = payload(1200)

        val chunks = piStream(data)
        assertEquals(3, chunks.size, "1200 bytes at a 499-byte payload is 499 + 499 + 202")
        assertEquals(
            listOf(BleChunker.CHUNK_START, BleChunker.CHUNK_CONTINUE, BleChunker.CHUNK_END),
            chunks.map { it[0] }
        )

        assertContentEquals(data, feed(chunker, pi, chunks), "the payload bytes must keep their order")
    }

    @Test
    fun aChunkerWithASmallCapStillReadsFullSizedTraffic() {
        // The cap governs what we send, never what we accept: a peer that negotiated a smaller MTU
        // than ours still has to read our 500-byte chunks and the Pi's 504-byte ones.
        val data = payload(2000)
        val fromAndroid = BleChunker().chunk(data)
        val fromPi = piStream(data)

        assertContentEquals(data, feed(BleChunker(10), phone, fromAndroid), "a 10-byte cap must still decode 500-byte chunks")
        assertContentEquals(data, feed(BleChunker(10), pi, fromPi), "a 10-byte cap must still decode 504-byte chunks")
    }

    @Test
    fun anOversizedChunkIsAcceptedOnItsOwnTerms() {
        // Nothing in the format bounds a chunk; only the sender's MTU does. A 3000-byte
        // continuation from a peer with a huge MTU is legal and must be taken at face value.
        val chunker = BleChunker()
        val data = payload(3200)

        assertNull(chunker.receive(phone, startChunk(data.size, data.copyOfRange(0, 200))))
        assertNull(chunker.receive(phone, continueChunk(data.copyOfRange(200, 3200 - 1))))
        assertContentEquals(data, chunker.receive(phone, endChunk(data.copyOfRange(3199, 3200))))
    }

    // ------------------------------------------------------------------------------------------
    // Malformed and hostile input
    // ------------------------------------------------------------------------------------------

    @Test
    fun aFrameShorterThanItDeclaredIsDroppedRatherThanDelivered() {
        // Both peers hand a short frame up anyway and only log the mismatch. We do not: the packet
        // parser downstream would read a truncated packet as a malformed one, or worse, as a valid
        // shorter one.
        val chunker = BleChunker()

        assertNull(chunker.receive(phone, startChunk(totalLength = 1000, payload = payload(495))))
        assertNull(
            chunker.receive(phone, endChunk(payload(4))),
            "499 bytes cannot be handed up as the 1000 the START promised"
        )

        assertEquals(1L, chunker.stats.droppedLengthMismatch, "the mismatch must be visible in the counters")
        assertEquals(1L, chunker.stats.framesDropped)
        assertEquals(0L, chunker.stats.framesReassembled, "a dropped frame was never reassembled")
    }

    @Test
    fun aFrameLongerThanItDeclaredIsDroppedToo() {
        // Over-delivery is the same bug seen from the other side, and just as likely to be a lost
        // or duplicated chunk rather than a harmless surplus.
        val chunker = BleChunker()

        assertNull(chunker.receive(phone, startChunk(totalLength = 10, payload = payload(8))))
        assertNull(chunker.receive(phone, endChunk(payload(8))), "16 bytes is not the 10 that were promised")

        assertEquals(1L, chunker.stats.droppedLengthMismatch)
        assertEquals(1L, chunker.stats.framesDropped)
    }

    @Test
    fun aDroppedFrameLeavesNothingBehindForTheNextOne() {
        val chunker = BleChunker()
        chunker.receive(phone, startChunk(totalLength = 1000, payload = payload(495)))
        chunker.receive(phone, endChunk(payload(4)))

        // The buffer has to be gone, not merely marked bad: a stray END must now be an orphan...
        assertNull(chunker.receive(phone, endChunk(payload(4))))
        assertEquals(1L, chunker.stats.droppedOrphanContinuation, "the dropped buffer must not still be in flight")

        // ...and the next frame from the same device must be clean.
        val data = payload(1200)
        assertContentEquals(data, feed(chunker, phone, chunker.chunk(data)), "the device must not be poisoned")
        assertEquals(1L, chunker.stats.framesReassembled)
    }

    @Test
    fun aContinuationWithNothingInFlightIsDroppedAsAnOrphan() {
        // This is what the first chunk being lost looks like from the receiver's side.
        val chunker = BleChunker()

        assertNull(chunker.receive(phone, continueChunk(payload(400))), "a CONTINUE without a START is not a frame")

        assertEquals(1L, chunker.stats.droppedOrphanContinuation)
        assertEquals(1L, chunker.stats.framesDropped)
    }

    @Test
    fun anEndWithNothingInFlightIsDroppedAsAnOrphan() {
        val chunker = BleChunker()

        assertNull(
            chunker.receive(phone, endChunk(payload(400))),
            "an END without a START must not be handed up as a whole frame"
        )

        assertEquals(1L, chunker.stats.droppedOrphanContinuation)
        assertEquals(1L, chunker.stats.framesDropped)
        assertEquals(0L, chunker.stats.framesReassembled)
    }

    @Test
    fun aNewStartAbandonsTheFrameThatWasStillInFlight() {
        // A sender that gave up mid-transfer and started again -- or a chunk lost between the two.
        // Splicing the second frame onto the tail of the first would deliver one corrupt packet
        // instead of losing one.
        val chunker = BleChunker()
        val abandoned = payload(1200, seed = 1)
        val wanted = payload(1200, seed = 2)

        chunker.receive(phone, chunker.chunk(abandoned)[0])
        val frame = feed(chunker, phone, chunker.chunk(wanted))

        assertContentEquals(wanted, frame, "the second frame must arrive whole and alone")
        assertEquals(1L, chunker.stats.droppedRestartInFlight, "the abandoned frame must be counted, not forgotten")
        assertEquals(1L, chunker.stats.framesDropped)
        assertEquals(1L, chunker.stats.framesReassembled)
    }

    @Test
    fun aTruncatedStartHeaderStartsNothing() {
        // Fewer than five bytes cannot even hold the length field. Android checks `size < 5` and
        // the Pi `size < 6`; either way this must not open a buffer, or the following chunks would
        // be appended to a frame with a garbage expected length.
        val chunker = BleChunker()

        assertNull(chunker.receive(phone, byteArrayOf(BleChunker.CHUNK_START, 0, 0, 1)))

        assertNull(chunker.receive(phone, continueChunk(payload(100))))
        assertEquals(
            1L,
            chunker.stats.droppedOrphanContinuation,
            "the CONTINUE proves no buffer was opened by the truncated START"
        )
    }

    @Test
    fun anEmptyChunkIsIgnoredAndLeavesTheFrameInFlight() {
        // Both peers return early on an empty value. It carries no type byte, so it can neither
        // start, extend nor finish anything.
        val chunker = BleChunker()
        val data = payload(1200)
        val chunks = chunker.chunk(data)

        chunker.receive(phone, chunks[0])
        assertNull(chunker.receive(phone, ByteArray(0)), "an empty write is not a frame")

        chunker.receive(phone, chunks[1])
        assertContentEquals(data, chunker.receive(phone, chunks[2]), "the empty write must not have disturbed the buffer")
    }

    @Test
    fun anUnframedBufferIsDeliveredWithoutDisturbingAFrameInFlight() {
        // Both peers deliver a non-marker buffer straight up and leave the reassembly buffer alone.
        // On a link where one side interleaves a small packet between chunks, dropping the
        // in-flight frame here would lose every large packet.
        val chunker = BleChunker()
        val data = payload(1200)
        val chunks = chunker.chunk(data)
        val loose = byteArrayOf(0x01, 0x02, 0x03)

        chunker.receive(phone, chunks[0])
        assertContentEquals(loose, chunker.receive(phone, loose), "a bare packet is its own frame")

        chunker.receive(phone, chunks[1])
        assertContentEquals(data, chunker.receive(phone, chunks[2]), "the chunked frame must still complete")
        assertEquals(0L, chunker.stats.framesDropped, "nothing here is a failure")
    }

    @Test
    fun theBytesEitherSideOfTheMarkersAreOrdinaryData() {
        // 0xFB and 0xFF sit either side of the marker range. Treating them as framing would eat a
        // byte off the front of a legitimate packet.
        val chunker = BleChunker()

        val below = byteArrayOf(0xFB.toByte(), 1, 2, 3)
        val above = byteArrayOf(0xFF.toByte(), 1, 2, 3)

        assertContentEquals(below, chunker.receive(phone, below), "0xFB is not a marker")
        assertContentEquals(above, chunker.receive(phone, above), "0xFF is not a marker")
    }

    @Test
    fun aSingleByteBufferIsItsOwnFrame() {
        val chunker = BleChunker()
        val one = byteArrayOf(0x01)

        assertContentEquals(one, chunker.receive(phone, one), "a one-byte packet still arrives")
    }

    // ------------------------------------------------------------------------------------------
    // One buffer per device
    // ------------------------------------------------------------------------------------------

    @Test
    fun twoDevicesInterleaveWithoutCorruptingEachOther() {
        // Two centrals writing at once is the normal case on the Pi, and the chunks arrive
        // interleaved on one callback. A single shared buffer would splice the two packets.
        val chunker = BleChunker()
        val fromPhone = payload(1200, seed = 1)
        val fromPi = payload(1400, seed = 2)

        val phoneChunks = chunker.chunk(fromPhone)
        val piChunks = chunker.chunk(fromPi)
        assertEquals(3, phoneChunks.size)
        assertEquals(3, piChunks.size)

        assertNull(chunker.receive(phone, phoneChunks[0]))
        assertNull(chunker.receive(pi, piChunks[0]))
        assertNull(chunker.receive(pi, piChunks[1]))
        assertNull(chunker.receive(phone, phoneChunks[1]))
        assertContentEquals(fromPi, chunker.receive(pi, piChunks[2]), "the Pi's frame must not contain the phone's bytes")
        assertContentEquals(fromPhone, chunker.receive(phone, phoneChunks[2]), "and vice versa")

        assertEquals(2L, chunker.stats.framesReassembled)
        assertEquals(0L, chunker.stats.framesDropped)
    }

    @Test
    fun oneDevicesRestartLeavesTheOtherDeviceAlone() {
        val chunker = BleChunker()
        val wanted = payload(1200, seed = 3)
        val chunks = chunker.chunk(wanted)

        chunker.receive(phone, chunks[0])
        // The Pi rotates its address and starts over; that says nothing about the phone's frame.
        chunker.receive(pi, chunker.chunk(payload(1200, seed = 4))[0])
        chunker.receive(pi, chunker.chunk(payload(1200, seed = 5))[0])

        chunker.receive(phone, chunks[1])
        assertContentEquals(wanted, chunker.receive(phone, chunks[2]), "the phone's frame is not the Pi's business")
        assertEquals(1L, chunker.stats.droppedRestartInFlight, "only the Pi's first buffer was abandoned")
    }

    @Test
    fun forgettingADeviceDropsItsBufferWithoutRecordingAFailure() {
        // forget() is what a disconnect calls. Nothing went wrong on the wire, so it must not
        // inflate the drop counters that a health check reads.
        val chunker = BleChunker()
        chunker.receive(phone, chunker.chunk(payload(1200))[0])

        val before = chunker.stats
        chunker.forget(phone)

        assertEquals(before, chunker.stats, "a deliberate disconnect is not a protocol failure")
        assertNull(chunker.receive(phone, endChunk(payload(10))), "the buffer must actually be gone")
        assertEquals(1L, chunker.stats.droppedOrphanContinuation)
    }

    @Test
    fun forgettingOneDeviceLeavesTheOthersInFlight() {
        val chunker = BleChunker()
        val wanted = payload(1200, seed = 6)
        val chunks = chunker.chunk(wanted)

        chunker.receive(pi, chunks[0])
        chunker.receive(phone, chunks[0])
        chunker.forget(pi)
        chunker.receive(phone, chunks[1])

        assertContentEquals(wanted, chunker.receive(phone, chunks[2]), "forgetting one address must not touch another")
    }

    @Test
    fun forgettingAnAddressWeNeverSawIsHarmless() {
        val chunker = BleChunker()

        chunker.forget(third)

        assertEquals(0L, chunker.stats.framesDropped, "there was nothing to drop")
    }

    // ------------------------------------------------------------------------------------------
    // Ageing out
    // ------------------------------------------------------------------------------------------

    @Test
    fun aFrameThatNeverFinishesIsSweptAwayAfterTheTimeout() {
        // A central that goes out of range mid-transfer leaves its buffer behind forever, and the
        // peripheral role never learns the link is gone until BlueZ says so.
        val chunker = BleChunker()
        val chunks = chunker.chunk(payload(1200))
        chunker.receive(phone, chunks[0])
        chunker.receive(pi, chunks[0])

        // The buffers are stamped from the same clock sweep() is handed. Two whole timeouts past
        // now keeps the assertion well clear of the boundary and of scheduler jitter.
        val wellPast = System.currentTimeMillis() + BleChunker.BUFFER_TIMEOUT_MS * 2

        assertEquals(2, chunker.sweep(wellPast), "both stalled buffers are past the timeout")
        assertEquals(2L, chunker.stats.droppedAgedOut)
        assertEquals(2L, chunker.stats.framesDropped)
        assertEquals(0, chunker.sweep(wellPast), "a second sweep has nothing left to drop")

        assertNull(chunker.receive(phone, chunks[2]), "a chunk for a swept frame is an orphan")
        assertEquals(1L, chunker.stats.droppedOrphanContinuation)
    }

    @Test
    fun aFrameStillWithinTheTimeoutIsLeftAlone() {
        val chunker = BleChunker()
        val data = payload(1200)
        val chunks = chunker.chunk(data)
        chunker.receive(phone, chunks[0])

        assertEquals(0, chunker.sweep(System.currentTimeMillis()), "a buffer seconds old is not stale")
        assertEquals(0L, chunker.stats.framesDropped)

        chunker.receive(phone, chunks[1])
        assertContentEquals(data, chunker.receive(phone, chunks[2]), "the sweep must not have touched the buffer")
    }

    @Test
    fun sweepingWithNothingInFlightDropsNothing() {
        val chunker = BleChunker()

        assertEquals(0, chunker.sweep(System.currentTimeMillis() + BleChunker.BUFFER_TIMEOUT_MS * 2))
        assertEquals(0L, chunker.stats.framesDropped)
    }

    // ------------------------------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyDropIsAccountedForUnderExactlyOneReason() {
        // The counters are what a health check reads to tell "the link is noisy" from "the link is
        // fine". Four different failures, one of each, plus one clean frame.
        val chunker = BleChunker()
        val a = phone
        val b = pi
        val c = third
        val d = "AA:BB:CC:DD:EE:FF"

        // 1. an orphan continuation
        chunker.receive(a, continueChunk(payload(100)))

        // 2. a restart that abandons a buffer
        chunker.receive(b, startChunk(totalLength = 1000, payload = payload(400)))
        chunker.receive(b, startChunk(totalLength = 1000, payload = payload(400)))

        // 3. a frame that does not add up
        chunker.receive(c, startChunk(totalLength = 10, payload = payload(4)))
        chunker.receive(c, endChunk(payload(4)))

        // 4. one clean frame in the middle of all of it
        val data = payload(1200)
        assertContentEquals(data, feed(chunker, d, chunker.chunk(data)))

        // 5. and b's second buffer is still in flight, so it ages out
        assertEquals(1, chunker.sweep(System.currentTimeMillis() + BleChunker.BUFFER_TIMEOUT_MS * 2))

        val stats = chunker.stats
        assertEquals(1L, stats.framesReassembled, "exactly one frame was whole")
        assertEquals(1L, stats.droppedOrphanContinuation, "exactly one chunk arrived with nothing in flight")
        assertEquals(1L, stats.droppedRestartInFlight, "exactly one buffer was abandoned by a restart")
        assertEquals(1L, stats.droppedLengthMismatch, "exactly one frame did not match its declared length")
        assertEquals(1L, stats.droppedAgedOut, "exactly one buffer went stale")
        assertEquals(
            4L,
            stats.framesDropped,
            "framesDropped must be the whole story: 1 orphan + 1 restart + 1 mismatch + 1 aged out"
        )
    }

    @Test
    fun aFreshChunkerHasCountedNothing() {
        val stats = BleChunker().stats

        assertEquals(0L, stats.framesReassembled)
        assertEquals(0L, stats.framesDropped)
        assertEquals(0L, stats.droppedLengthMismatch)
        assertEquals(0L, stats.droppedOrphanContinuation)
        assertEquals(0L, stats.droppedRestartInFlight)
        assertEquals(0L, stats.droppedAgedOut)
    }

    @Test
    fun theStatsAreASnapshotAndNotALiveView() {
        val chunker = BleChunker()
        val before = chunker.stats

        chunker.receive(phone, endChunk(payload(10)))

        assertEquals(0L, before.framesDropped, "a snapshot taken earlier must not have moved")
        assertEquals(1L, chunker.stats.framesDropped)
    }

    // ------------------------------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------------------------------

    @Test
    fun devicesFedFromDifferentThreadsEachGetTheirOwnFramesBack() {
        // On the Pi the D-Bus dispatch worker and the application scope both reach this class. The
        // outcome is deterministic even though the interleaving is not: every frame is whole, and
        // no buffer was lost to a torn map update.
        val chunker = BleChunker()
        val devices = 8
        val framesPerDevice = 25
        val start = CountDownLatch(1)
        val failures = ConcurrentHashMap<String, String>()

        val threads = (0 until devices).map { device ->
            thread(name = "feeder-$device") {
                val address = "AA:BB:CC:00:00:%02X".format(device)
                start.await()
                repeat(framesPerDevice) { frameIndex ->
                    val data = payload(1200 + device, seed = device * 1000 + frameIndex)
                    var completed: ByteArray? = null
                    for (chunk in chunker.chunk(data)) {
                        chunker.receive(address, chunk)?.let { completed = it }
                    }
                    val got = completed
                    if (got == null) {
                        failures["$address/$frameIndex"] = "never completed"
                    } else if (!got.contentEquals(data)) {
                        failures["$address/$frameIndex"] = "came back as ${got.size} of ${data.size} bytes"
                    }
                }
            }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertTrue(failures.isEmpty(), "frames were corrupted across threads: $failures")
        assertEquals(
            (devices * framesPerDevice).toLong(),
            chunker.stats.framesReassembled,
            "every frame from every thread must be counted exactly once"
        )
        assertEquals(0L, chunker.stats.framesDropped, "nothing here is a protocol failure")
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The caps worth exercising: the default, an awkward middle, a tiny one and the floor. */
    private val capsUnderTest = listOf(BleChunker.DEFAULT_MAX_CHUNK_SIZE, 64, 10, 6)

    /**
     * The sizes where the arithmetic changes, derived from the cap rather than written down: on
     * either side of "fits in one chunk", and on either side of each whole number of chunks.
     */
    private fun interestingSizes(cap: Int): List<Int> {
        val first = cap - 5
        val later = cap - 1
        return listOf(
            1,
            cap - 1,
            cap,                        // the last unframed buffer
            cap + 1,                    // the first framed one
            first + later,              // the last two-chunk buffer
            first + later + 1,
            first + 2 * later,          // the last three-chunk buffer
            first + 2 * later + 1,
            first + 10 * later,
            4096
        ).distinct().filter { it > 0 }.sorted()
    }

    /**
     * Bytes whose value depends on their position, so a chunk delivered out of order or spliced
     * from the wrong frame shows up as a content mismatch rather than passing on length alone.
     * The first byte is never a marker, so a short buffer can travel unframed.
     */
    private fun payload(size: Int, seed: Int = 0): ByteArray =
        ByteArray(size) { i -> if (i == 0) 0x01 else ((i * 31 + seed) and 0xFF).toByte() }

    private fun startChunk(totalLength: Int, payload: ByteArray): ByteArray =
        ByteArray(5 + payload.size).also {
            it[0] = BleChunker.CHUNK_START
            it[1] = ((totalLength shr 24) and 0xFF).toByte()
            it[2] = ((totalLength shr 16) and 0xFF).toByte()
            it[3] = ((totalLength shr 8) and 0xFF).toByte()
            it[4] = (totalLength and 0xFF).toByte()
            payload.copyInto(it, 5)
        }

    private fun continueChunk(payload: ByteArray): ByteArray =
        byteArrayOf(BleChunker.CHUNK_CONTINUE) + payload

    private fun endChunk(payload: ByteArray): ByteArray =
        byteArrayOf(BleChunker.CHUNK_END) + payload

    private fun declaredLength(start: ByteArray): Int =
        ((start[1].toInt() and 0xFF) shl 24) or
            ((start[2].toInt() and 0xFF) shl 16) or
            ((start[3].toInt() and 0xFF) shl 8) or
            (start[4].toInt() and 0xFF)

    /**
     * What `BlueZGattServerService.notifyChunked` puts on the wire: 499 payload bytes in every
     * chunk, the first one included, so the first chunk is 504 bytes long.
     */
    private fun piStream(data: ByteArray, maxPayload: Int = 499): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(data.size - offset, maxPayload)
            val slice = data.copyOfRange(offset, offset + size)
            chunks += when {
                offset == 0 -> startChunk(data.size, slice)
                offset + size >= data.size -> endChunk(slice)
                else -> continueChunk(slice)
            }
            offset += size
        }
        return chunks
    }

    /** Feeds a whole encoding in, asserting that nothing completes before the last chunk. */
    private fun feed(chunker: BleChunker, address: String, chunks: List<ByteArray>): ByteArray? {
        chunks.dropLast(1).forEachIndexed { index, chunk ->
            assertNull(
                chunker.receive(address, chunk),
                "chunk ${index + 1} of ${chunks.size} completed a frame that is not finished yet"
            )
        }
        return chunker.receive(address, chunks.last())
    }

    /**
     * Everything an encoding has to satisfy for the peers to be able to read it: chunks that fit
     * the cap, one START then CONTINUEs then an END, a truthful length field, and payloads that
     * concatenate back to the original in order.
     */
    private fun assertWellFormed(chunks: List<ByteArray>, data: ByteArray, cap: Int) {
        val where = "${data.size} bytes at cap $cap"
        assertTrue(chunks.isNotEmpty(), "$where produced no chunks")
        chunks.forEach {
            assertTrue(it.size <= cap, "$where produced a ${it.size}-byte chunk, over the $cap-byte cap")
        }

        if (data.size <= cap) {
            assertEquals(1, chunks.size, "$where fits in one write")
            assertContentEquals(data, chunks[0], "$where must go out unframed")
            return
        }

        // One START, one END, and CONTINUEs in between -- and the count the header arithmetic
        // predicts: the first chunk carries cap-5 bytes and every later one cap-1.
        assertEquals(expectedChunkCount(data.size, cap), chunks.size, "$where split into the wrong number of chunks")
        assertEquals(BleChunker.CHUNK_START, chunks.first()[0], "$where must open with a START")
        assertEquals(BleChunker.CHUNK_END, chunks.last()[0], "$where must close with an END")
        chunks.drop(1).dropLast(1).forEach {
            assertEquals(BleChunker.CHUNK_CONTINUE, it[0], "$where has a middle chunk that is not a CONTINUE")
        }

        assertEquals(data.size, declaredLength(chunks.first()), "$where declared the wrong total length")

        val rebuilt = chunks.mapIndexed { index, chunk ->
            chunk.copyOfRange(if (index == 0) 5 else 1, chunk.size)
        }.reduce { acc, bytes -> acc + bytes }
        assertContentEquals(data, rebuilt, "$where does not concatenate back to the original")
    }

    /**
     * The chunk count the format implies: one START carrying cap-5 bytes, then as many cap-1 byte
     * chunks as it takes to finish, rounded up.
     */
    private fun expectedChunkCount(size: Int, cap: Int): Int {
        if (size <= cap) return 1
        val afterFirst = size - (cap - 5)
        val later = cap - 1
        return 1 + (afterFirst + later - 1) / later
    }
}
