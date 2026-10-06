package com.bitchat.bluetooth.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkReassemblerTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun reassemblesAndroidAndBlueZChunkLayoutsByteForByte() {
        val data = payload(1_200)
        assertContentEquals(data, feed(ChunkReassembler(maxFrameBytes = 2_000), androidChunks(data)))
        assertContentEquals(data, feed(ChunkReassembler(maxFrameBytes = 2_000), blueZChunks(data)))
    }

    @Test
    fun unchunkedFrameDoesNotDisturbAnInFlightFrame() {
        val reassembler = ChunkReassembler(maxFrameBytes = 100)
        assertNull(reassembler.receive(address, start(3, byteArrayOf(1))))
        assertContentEquals(byteArrayOf(9), reassembler.receive(address, byteArrayOf(9)))
        assertContentEquals(byteArrayOf(1, 2, 3), reassembler.receive(address, end(byteArrayOf(2, 3))))
    }

    @Test
    fun invalidStartKeepsAnEarlierFrameInFlight() {
        for (declared in listOf(0, -1, 11)) {
            val reassembler = ChunkReassembler(maxFrameBytes = 10)
            reassembler.receive(address, start(3, byteArrayOf(1)))
            assertNull(reassembler.receive(address, start(declared, byteArrayOf())))
            assertContentEquals(byteArrayOf(1, 2, 3), reassembler.receive(address, end(byteArrayOf(2, 3))))
        }
    }

    @Test
    fun headerOnlyStartCanCompleteLater() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        assertNull(reassembler.receive(address, start(2, byteArrayOf())))
        assertContentEquals(byteArrayOf(1, 2), reassembler.receive(address, end(byteArrayOf(1, 2))))
    }

    @Test
    fun overDeliveryDropsImmediatelyAndNextFrameCanComplete() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        reassembler.receive(address, start(2, byteArrayOf(1)))
        assertNull(reassembler.receive(address, continueChunk(byteArrayOf(2, 3))))
        assertNull(reassembler.receive(address, end(byteArrayOf())))
        reassembler.receive(address, start(1, byteArrayOf()))
        assertContentEquals(byteArrayOf(4), reassembler.receive(address, end(byteArrayOf(4))))
    }

    @Test
    fun endlessContinuesCannotPoisonTheNextFrame() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        reassembler.receive(address, start(2, byteArrayOf(1)))
        repeat(100) { assertNull(reassembler.receive(address, continueChunk(byteArrayOf(2, 3)))) }
        reassembler.receive(address, start(1, byteArrayOf()))
        assertContentEquals(byteArrayOf(4), reassembler.receive(address, end(byteArrayOf(4))))
    }

    @Test
    fun shortEndAndOrphanChunksAreDropped() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        assertNull(reassembler.receive(address, continueChunk(byteArrayOf(1))))
        assertNull(reassembler.receive(address, end(byteArrayOf(1))))
        reassembler.receive(address, start(3, byteArrayOf(1)))
        assertNull(reassembler.receive(address, end(byteArrayOf(2))))
    }

    @Test
    fun restartInterleavingAndForgetKeepFramesSeparate() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        reassembler.receive(address, start(3, byteArrayOf(1)))
        reassembler.receive(address, start(2, byteArrayOf(4)))
        assertContentEquals(byteArrayOf(4, 5), reassembler.receive(address, end(byteArrayOf(5))))
        reassembler.receive("other", start(2, byteArrayOf(7)))
        reassembler.forget("other")
        assertNull(reassembler.receive("other", end(byteArrayOf(8))))
    }

    @Test
    fun idleFramesAgeOutAndThirtyThirdAddressEvictsOldest() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 10, idleTimeoutMillis = 10, now = { now })
        reassembler.receive(address, start(2, byteArrayOf(1)))
        now = 10
        reassembler.receive("new", start(1, byteArrayOf(2)))
        assertNull(reassembler.receive(address, end(byteArrayOf(2))))

        // A small table, and a byte budget large enough that only the count decides here.
        val bounded = ChunkReassembler(maxFrameBytes = 10, maxInFlight = 32, maxBufferedBytes = 1_000, now = { now })
        repeat(32) { index ->
            now += 1
            bounded.receive("$index", start(2, byteArrayOf(index.toByte())))
        }
        assertEquals(32, bounded.inFlight)
        now += 1
        bounded.receive("33", start(1, byteArrayOf(33)))
        assertEquals(32, bounded.inFlight, "the frame idle longest made room")
        assertNull(bounded.receive("0", end(byteArrayOf(0))), "the first link's frame was the one idle longest")
        assertContentEquals(byteArrayOf(1, 1), bounded.receive("1", end(byteArrayOf(1))), "the others are untouched")
        assertContentEquals(byteArrayOf(33), bounded.receive("33", end(byteArrayOf())))
    }

    @Test
    fun aFirstChunkCarryingMoreThanItDeclaresStartsNothingAndKeepsTheFrameInFlight() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10)
        reassembler.receive(address, start(3, byteArrayOf(1)))

        assertNull(reassembler.receive(address, start(2, byteArrayOf(7, 8, 9))))

        assertContentEquals(byteArrayOf(1, 2, 3), reassembler.receive(address, end(byteArrayOf(2, 3))))
    }

    @Test
    fun aChunkThatWouldPassTheDeclaredLengthIsNotCopiedAndDropsTheFrame() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10_000)
        reassembler.receive(address, start(2_000, payload(1_000)))
        val before = reassembler.buffered

        assertNull(reassembler.receive(address, continueChunk(payload(1_001))))

        assertTrue(before <= 2_000, "the buffer never passes the declared length, was $before")
        assertEquals(0, reassembler.inFlight)
        assertEquals(0L, reassembler.buffered)
    }

    @Test
    fun partialFramesTogetherStayWithinTheByteBudget() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 100, maxBufferedBytes = 250, now = { now })
        for (link in listOf("a", "b", "c")) {
            now += 1
            reassembler.receive(link, start(100, payload(90)))
            assertTrue(reassembler.buffered <= 250, "buffered ${reassembler.buffered} after $link")
        }

        // The third holds no less than the two before it, so it is the one that does not get in.
        assertEquals(2, reassembler.inFlight)
        assertNull(reassembler.receive("c", end(payload(10))), "the late arrival did not displace its equals")
        assertEquals(100, reassembler.receive("a", end(payload(10)))?.size)
        assertEquals(100, reassembler.receive("b", end(payload(10)))?.size)
        assertEquals(0L, reassembler.buffered)
    }

    @Test
    fun theFrameTakingTheMostGivesWayToASmallerOneHoweverFreshItIs() {
        var now = 0L
        // Room for two and a half full frames.
        val reassembler = ChunkReassembler(maxFrameBytes = 8_192, maxBufferedBytes = 20_480, now = { now })
        // A small frame that has been waiting a while, as an honest transfer on a slow link does.
        reassembler.receive("small", start(1_500, payload(1_000)))
        // Two links that take as much as a frame can, and are fresher than the small one.
        for (link in listOf("large-1", "large-2")) {
            repeat(5) { chunk ->
                now += 1
                reassembler.receive(link, if (chunk == 0) start(8_192, payload(1_024)) else continueChunk(payload(1_024)))
            }
        }
        assertEquals(1_024L + 8_192 + 8_192, reassembler.buffered)

        // A third link grows until there is no room left; it would take half of what the large ones do.
        now += 1
        reassembler.receive("third", start(8_192, payload(1_024)))
        now += 1
        reassembler.receive("third", continueChunk(payload(1_024)))
        assertEquals(4, reassembler.inFlight)
        now += 1
        reassembler.receive("third", continueChunk(payload(1_024)))

        // The small frame is by far the one idle longest, and it is not the one that went.
        assertEquals(3, reassembler.inFlight)
        assertNull(reassembler.receive("large-1", end(payload(1))), "a link taking the most gave way")
        assertEquals(1_500, reassembler.receive("small", end(payload(500)))?.size)
        repeat(4) {
            now += 1
            assertNull(reassembler.receive("third", continueChunk(payload(1_024))))
        }
        assertEquals(8_192, reassembler.receive("third", end(payload(1_024)))?.size)
    }

    @Test
    fun whatIsComparedIsTheBlocksAFrameTakesNotTheBytesItReceived() {
        // Blocks can be half empty. Many links that each delivered less than the honest frame but take
        // more than it must not make the honest one "the largest".
        var now = 0L
        val reassembler = ChunkReassembler(now = { now })
        fun deliver(link: String, declared: Int, bytes: Int) {
            var sent = minOf(495, bytes)
            now += 1
            reassembler.receive(link, start(declared, payload(sent)))
            while (sent < bytes) {
                val count = minOf(499, bytes - sent)
                now += 1
                reassembler.receive(link, continueChunk(payload(count)))
                sent += count
            }
        }
        // 90 000 of 100 000 bytes: its blocks take 100 000.
        deliver("honest", declared = 100_000, bytes = 90_000)
        // Links with one byte past 64 KiB each: 65 537 bytes received, blocks of 128 KiB. As many as fit
        // beside the honest frame and the 16 KiB the last link starts with.
        val limit = ChunkReassembler.MAX_BUFFERED_FRAMES.toLong() * MAX_MESH_FRAME_BYTES
        val others = ((limit - 100_000 - 16_384) / 131_072).toInt()
        repeat(others) { deliver("other-$it", declared = MAX_MESH_FRAME_BYTES, bytes = 65_537) }
        assertEquals(0L, reassembler.stats.framesDropped)
        // One more grows until the total is reached: its next block does not fit.
        deliver("last", declared = MAX_MESH_FRAME_BYTES, bytes = 16_385)

        assertEquals(1L, reassembler.stats.framesDropped, "one frame had to go")
        now += 1
        val frame = reassembler.receive("honest", end(payload(10_000)))
        assertEquals(100_000, frame?.size, "it was one that took more, not the honest frame that received more")
    }

    @Test
    fun aFrameThatWouldHoldTheMostIsRefusedAndPushesNothingOut() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 8_192, maxBufferedBytes = 8_192, now = { now })
        // Six small frames fill most of the room.
        val small = (1..6).map { "small-$it" }
        for (link in small) {
            now += 1
            reassembler.receive(link, start(1_200, payload(1_000)))
        }
        // One link wants as much as a frame can be. It gets what room there is, and no more.
        now += 1
        reassembler.receive("large", start(8_192, payload(1_024)))
        now += 1
        reassembler.receive("large", continueChunk(payload(1_024)))
        assertEquals(8_192L, reassembler.buffered)
        assertEquals(7, reassembler.inFlight)
        now += 1
        assertNull(reassembler.receive("large", continueChunk(payload(1_024))))

        assertEquals(6, reassembler.inFlight, "the large frame is the one that went")
        for (link in small) assertEquals(1_200, reassembler.receive(link, end(payload(200)))?.size, link)
        assertNull(reassembler.receive("large", end(payload(1))))
    }

    @Test
    fun asManyFullSizeFramesAsTheTotalNamesGrowSideBySideAndNoneIsDropped() {
        // Growing a frame takes no more than its next block, so whatever fits when the frames are
        // complete fits on the way there: links sending the largest frame at once do not cost each other
        // theirs, however their chunks interleave.
        var now = 0L
        val frameBytes = 128 * 1024
        val reassembler = ChunkReassembler(maxFrameBytes = frameBytes, now = { now })
        val links = (1..ChunkReassembler.MAX_BUFFERED_FRAMES).map { "link-$it" }
        val data = links.associateWith { link -> ByteArray(frameBytes) { (it + link.hashCode()).toByte() } }
        val chunks = data.mapValues { androidChunks(it.value) }
        val frames = mutableMapOf<String, ByteArray?>()

        // The first link gets its whole frame but the last chunk and then waits, as the frame idle longest.
        val count = chunks.getValue(links.first()).size
        for (index in 0 until count - 1) {
            now += 1
            reassembler.receive(links.first(), chunks.getValue(links.first())[index])
        }
        for (index in 0 until count) {
            for (link in links.drop(1)) {
                now += 1
                frames[link] = reassembler.receive(link, chunks.getValue(link)[index])
            }
            val limit = ChunkReassembler.MAX_BUFFERED_FRAMES.toLong() * frameBytes
            assertTrue(reassembler.buffered <= limit, "buffered ${reassembler.buffered}")
        }
        now += 1
        frames[links.first()] = reassembler.receive(links.first(), chunks.getValue(links.first()).last())

        for (link in links) assertContentEquals(data.getValue(link), frames[link], link)
        assertEquals(0L, reassembler.stats.framesDropped)
    }

    @Test
    fun oneFrameMoreThanTheTotalHoldsIsRefusedAndTheOthersComplete() {
        var now = 0L
        val frameBytes = 64 * 1024
        val reassembler = ChunkReassembler(maxFrameBytes = frameBytes, now = { now })
        val links = (0..ChunkReassembler.MAX_BUFFERED_FRAMES).map { "link-$it" }
        val chunks = androidChunks(ByteArray(frameBytes) { it.toByte() })
        val done = mutableMapOf<String, ByteArray?>()

        for (chunk in chunks) {
            for (link in links) {
                now += 1
                done[link] = reassembler.receive(link, chunk)
            }
        }

        // One of them asked for a block when the rest already took as much as it would: that one was
        // refused, pushed nobody out, and the room it gave back let the others finish.
        assertEquals(ChunkReassembler.MAX_BUFFERED_FRAMES, done.values.count { it?.size == frameBytes })
        assertEquals(1, done.values.count { it == null })
        assertEquals(0, reassembler.inFlight)
    }

    @Test
    fun linksThatLeftLargeBuffersBehindDoNotPushOutASmallerFrame() {
        // No disconnect is reported on some platforms, so what a link left behind stays until it has
        // been idle long enough. One link after another leaves half a frame; the honest frame holds less
        // than any of them and has been idle longer than all of them.
        var now = 0L
        val reassembler = ChunkReassembler(now = { now })
        reassembler.receive("honest", start(100_000, payload(495)))
        reassembler.receive("honest", continueChunk(payload(499)))

        val half = MAX_MESH_FRAME_BYTES / 2 + 1
        val limit = ChunkReassembler.MAX_BUFFERED_FRAMES.toLong() * MAX_MESH_FRAME_BYTES
        repeat(3 * ChunkReassembler.MAX_BUFFERED_FRAMES) { link ->
            var sent = 0
            now += 1
            reassembler.receive("gone-$link", start(MAX_MESH_FRAME_BYTES, payload(495)))
            sent += 495
            while (sent < half) {
                now += 1
                reassembler.receive("gone-$link", continueChunk(payload(499)))
                sent += 499
            }
            assertTrue(reassembler.buffered <= limit, "buffered ${reassembler.buffered}")
        }

        // That many halves do not fit; the ones that went are among those that held the most.
        assertTrue(reassembler.stats.framesDropped > 0)
        var frame: ByteArray? = null
        val rest = ByteArray(100_000 - 994) { 7 }
        var offset = 0
        while (offset < rest.size) {
            val end = minOf(offset + 499, rest.size)
            now += 1
            frame = reassembler.receive("honest", if (end == rest.size) end(rest.copyOfRange(offset, end)) else continueChunk(rest.copyOfRange(offset, end)))
            offset = end
        }
        assertEquals(100_000, frame?.size, "the honest frame was never the one holding the most")
    }

    @Test
    fun anEmptyContinuationDoesNotKeepAFrameAlive() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 10, now = { now })
        reassembler.receive(address, start(5, byteArrayOf(1)))

        // Free to send, for ever: it must not hold a buffer in place.
        repeat(10) {
            now += ChunkReassembler.IDLE_TIMEOUT_MILLIS / 10
            reassembler.receive(address, continueChunk(byteArrayOf()))
        }

        assertEquals(0, reassembler.inFlight)
        assertEquals(1L, reassembler.stats.droppedAgedOut)
    }

    @Test
    fun aStalledFrameIsSweptWhenAnyChunkArrives() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 10, now = { now })
        reassembler.receive("gone", start(5, byteArrayOf(1)))
        now = ChunkReassembler.IDLE_TIMEOUT_MILLIS - 1
        reassembler.receive("other", continueChunk(byteArrayOf(9)))
        assertEquals(1, reassembler.inFlight, "not yet idle long enough")

        now = ChunkReassembler.IDLE_TIMEOUT_MILLIS
        // Not a first chunk, and from another link: any chunk sweeps.
        reassembler.receive("other", continueChunk(byteArrayOf(9)))

        assertEquals(0, reassembler.inFlight)
        assertEquals(0L, reassembler.buffered)
    }

    @Test
    fun buffersAreGivenBackOnEveryWayOut() {
        val reassembler = ChunkReassembler(maxFrameBytes = 10_000)
        reassembler.receive("done", start(1_500, payload(1_000)))
        reassembler.receive("done", end(payload(500)))
        reassembler.receive("forgotten", start(1_500, payload(1_000)))
        reassembler.forget("forgotten")
        reassembler.receive("short", start(1_500, payload(1_000)))
        reassembler.receive("short", end(payload(1)))
        reassembler.receive("restarted", start(1_500, payload(1_000)))
        reassembler.receive("restarted", start(1, byteArrayOf()))
        reassembler.receive("restarted", end(byteArrayOf(1)))
        assertEquals(0, reassembler.inFlight)
        assertEquals(0L, reassembler.buffered)

        reassembler.receive("cleared", start(1_500, payload(1_000)))
        reassembler.clear()
        assertEquals(0L, reassembler.buffered)
    }

    @Test
    fun aFullSizeFrameInAndroidSizedChunksReassembles() {
        val data = ByteArray(MAX_MESH_FRAME_BYTES) { (it * 31 % 251).toByte() }
        val reassembler = ChunkReassembler()
        var most = 0L
        var frame: ByteArray? = null
        for (chunk in androidChunks(data)) {
            frame = reassembler.receive(address, chunk)
            most = maxOf(most, reassembler.buffered)
        }

        assertContentEquals(data, frame)
        assertTrue(most <= MAX_MESH_FRAME_BYTES, "a frame's buffer never passes its declared length, was $most")
        assertEquals(0L, reassembler.buffered)
    }

    @Test
    fun exactlyTheRealFrameLimitIsAcceptedAndOversizedUnchunkedIsDropped() {
        val data = ByteArray(MAX_MESH_FRAME_BYTES) { 1 }
        val reassembler = ChunkReassembler()
        assertNull(reassembler.receive(address, start(data.size, data.copyOfRange(0, 1))))
        assertContentEquals(data, reassembler.receive(address, end(data.copyOfRange(1, data.size))))
        assertNull(reassembler.receive(address, ByteArray(MAX_MESH_FRAME_BYTES + 1) { 1 }))
        assertNull(reassembler.receive(address, start(MAX_MESH_FRAME_BYTES + 1, byteArrayOf(1))))
        assertEquals(0, reassembler.inFlight)
    }

    @Test
    fun dropsAreLoggedAtMostOnceASecondWithACountOfTheRest() {
        var now = 0L
        val lines = mutableListOf<String>()
        val reassembler = ChunkReassembler(maxFrameBytes = 10, now = { now }, log = { lines += it })

        repeat(100) { reassembler.receive(address, continueChunk(byteArrayOf(1))) }
        assertEquals(1, lines.size, "a peer must not be able to write the log full")
        assertTrue(lines.single().startsWith("Dropping chunked data from $address: a continuation chunk arrived with no frame in flight"), lines.single())

        now = 1_000
        reassembler.receive(address, end(byteArrayOf(1)))
        assertEquals(2, lines.size)
        assertTrue(lines.last().endsWith("(99 more drops not logged)"), lines.last())
        assertEquals(101L, reassembler.stats.framesDropped, "every drop is counted, logged or not")
    }

    @Test
    fun anUnchunkedBufferSweepsStalledFramesToo() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 10, now = { now })
        reassembler.receive("gone", start(5, byteArrayOf(1)))

        now = ChunkReassembler.IDLE_TIMEOUT_MILLIS
        assertContentEquals(byteArrayOf(1, 2), reassembler.receive("other", byteArrayOf(1, 2)))

        assertEquals(0, reassembler.inFlight)
        assertEquals(1L, reassembler.stats.droppedAgedOut)
    }

    @Test
    fun connectionsMadeOneAfterAnotherDoNotPushOutAFrameThatIsStillArriving() {
        var now = 0L
        val reassembler = ChunkReassembler(now = { now })
        reassembler.receive("honest", start(1_000, payload(400)))

        // Far more links than a device has at once, each leaving a first chunk behind and going away
        // without a disconnect being reported, while the honest frame pauses for a few seconds.
        repeat(500) { link ->
            now += 10
            reassembler.receive("churn-$link", start(100, byteArrayOf()))
        }

        assertEquals(1_000, reassembler.receive("honest", end(payload(600)))?.size)
        assertEquals(0L, reassembler.stats.framesDropped)
    }

    @Test
    fun theCountersSayWhyFramesWereDropped() {
        var now = 0L
        val reassembler = ChunkReassembler(maxFrameBytes = 10, now = { now })
        reassembler.receive("ok", start(2, byteArrayOf(1)))
        reassembler.receive("ok", end(byteArrayOf(2)))
        reassembler.receive("short", start(3, byteArrayOf(1)))
        reassembler.receive("short", end(byteArrayOf(2)))
        reassembler.receive("long", start(2, byteArrayOf(1)))
        reassembler.receive("long", continueChunk(byteArrayOf(2, 3)))
        reassembler.receive("orphan", continueChunk(byteArrayOf(1)))
        reassembler.receive("restart", start(3, byteArrayOf(1)))
        reassembler.receive("restart", start(3, byteArrayOf(1)))
        reassembler.forget("restart")
        reassembler.receive("bad", start(11, byteArrayOf()))
        reassembler.receive("stalled", start(3, byteArrayOf(1)))
        assertEquals(1, reassembler.sweep(now + ChunkReassembler.IDLE_TIMEOUT_MILLIS))

        assertEquals(
            ChunkReassembler.Stats(
                framesReassembled = 1,
                framesDropped = 6,
                droppedLengthMismatch = 2,
                droppedOrphanContinuation = 1,
                droppedRestartInFlight = 1,
                droppedAgedOut = 1,
            ),
            reassembler.stats,
        )
    }

    @Test
    fun linksOnManyThreadsDoNotMixTheirFramesOrLoseCountOfTheBuffers() {
        val reassembler = ChunkReassembler()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val begin = CountDownLatch(1)
        val wrong = AtomicInteger()
        try {
            repeat(threads) { thread ->
                pool.execute {
                    begin.await()
                    val link = "link-$thread"
                    repeat(200) { round ->
                        val data = ByteArray(3_000 + thread) { (thread * 31 + round + it).toByte() }
                        var frame: ByteArray? = null
                        for (chunk in androidChunks(data)) frame = reassembler.receive(link, chunk)
                        if (frame == null || !frame.contentEquals(data)) wrong.incrementAndGet()
                        if (round % 50 == 0) {
                            reassembler.receive(link, start(100, payload(10)))
                            reassembler.forget(link)
                        }
                    }
                }
            }
            begin.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the links did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(0, wrong.get(), "every frame arrives as it was sent, on its own link")
        assertEquals(0, reassembler.inFlight)
        assertEquals(0L, reassembler.buffered)
        assertEquals(threads * 200L, reassembler.stats.framesReassembled)
    }

    private fun feed(reassembler: ChunkReassembler, chunks: List<ByteArray>): ByteArray? =
        chunks.fold<ByteArray, ByteArray?>(null) { _, chunk -> reassembler.receive(address, chunk) }

    private fun androidChunks(data: ByteArray): List<ByteArray> = chunk(data, 495, 499)
    private fun blueZChunks(data: ByteArray): List<ByteArray> = chunk(data, 499, 499)
    private fun chunk(data: ByteArray, first: Int, later: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        val initial = data.copyOfRange(0, first)
        chunks += start(data.size, initial)
        offset += initial.size
        while (offset < data.size) {
            val end = minOf(offset + later, data.size)
            val part = data.copyOfRange(offset, end)
            chunks += if (end == data.size) end(part) else continueChunk(part)
            offset = end
        }
        return chunks
    }

    private fun start(length: Int, payload: ByteArray) = byteArrayOf(
        ChunkReassembler.CHUNK_START,
        (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
    ) + payload
    private fun continueChunk(payload: ByteArray) = byteArrayOf(ChunkReassembler.CHUNK_CONTINUE) + payload
    private fun end(payload: ByteArray) = byteArrayOf(ChunkReassembler.CHUNK_END) + payload
    private fun payload(size: Int) = ByteArray(size) { (it % 127).toByte() }
}
