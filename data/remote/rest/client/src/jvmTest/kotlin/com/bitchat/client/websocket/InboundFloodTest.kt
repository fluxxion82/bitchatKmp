package com.bitchat.client.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * What a relay that sends faster than its frames are handled can make this client hold, and what
 * that costs the other relays. Loopback relays, the real client, and a listener the test holds
 * inside a frame for as long as it likes: that is the slow consumer.
 *
 * Nothing here is decided by how fast anything runs. Each step waits for a state the client
 * reports ([KtorWebSocketClient.inboundState]) and fails if it never comes.
 *
 * Run once per engine available on the JVM: OkHttp is what the desktop and Android apps use and it
 * hands frames over from its own thread without ever waiting; CIO suspends its reader instead.
 */
abstract class InboundFloodTest {
    /** The engine under the client: what hands the frames over differs from one to the next. */
    protected abstract fun httpClient(): HttpClient

    private val http by lazy { httpClient() }
    private val reports = CopyOnWriteArrayList<String>()
    private val relays = mutableListOf<LoopbackRelay>()

    private val decodedSizes = CopyOnWriteArrayList<Int>()
    private var reportFails = false

    private fun client(limits: InboundLimits) = KtorWebSocketClient(
        routeProvider = directRoutes(http),
        limits = limits,
        // A clock nobody moves: the first drop of a relay is reported, the rest are counted.
        droppedFrames = DroppedFrameLog(interval = 1.minutes, timeSource = TestTimeSource()),
        report = { line ->
            reports += line
            check(!reportFails) { "the log cannot be written" }
        },
        decode = { frame ->
            decodedSizes += frame.data.size
            frame.readText()
        },
    )

    private fun relay() = LoopbackRelay().also { relays += it }

    private val heldListeners = CopyOnWriteArrayList<HeldListener>()

    private fun heldListener(onOpen: (String) -> Unit = {}, onFrame: (String) -> Unit = {}, onDrained: (String) -> Unit = {}) =
        HeldListener(onOpen, onFrame, onDrained).also { heldListeners += it }

    @Test
    fun `a relay has only so many frames in flight and what arrives beyond them is dropped`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val relay = relay()
        val held = heldListener()
        client.connect(relay.url, held)

        relay.send((0 until 100).map { "frame-$it" })
        // The listener is inside the first frame, seven wait behind it, and the other 92 were read and let go.
        awaitUntil("92 frames dropped") { client.inboundState(relay.url)?.framesDropped == 92L }
        assertEquals(8, client.inboundState(relay.url)?.framesInFlight)

        held.letGo()
        awaitUntil("nothing in flight") { client.inboundState(relay.url)?.framesInFlight == 0 }
        assertEquals((0 until 8).map { "frame-$it" }, held.frames(), "what was accepted arrives, in order, and nothing else")
        assertEquals(0L, client.inboundState(relay.url)?.bytesInFlight)

        // The room was given back: the relay is heard again.
        relay.send(listOf("after"))
        awaitUntil("the next frame") { held.frames().lastOrNull() == "after" }
        assertEquals(92L, client.inboundState(relay.url)?.framesDropped)
        assertEquals(1, reports.size, "one line for 92 dropped frames: $reports")
        assertTrue(relay.url in reports.single() && "(1 over its backlog limit, 0 over the frame size limit" in reports.single(), reports.single())
    }

    @Test
    fun `a relay has only so many bytes in flight`() = flood(InboundLimits(maxFramesInFlight = 1_000, maxBytesInFlight = 10_000)) { client ->
        val relay = relay()
        val held = heldListener()
        client.connect(relay.url, held)

        relay.send((0 until 30).map { "frame-$it".padEnd(1_000, '.') })
        // Ten frames of a thousand bytes are the ten thousand allowed; the eleventh would be over.
        awaitUntil("ten in flight, twenty dropped") {
            client.inboundState(relay.url) == InboundState(framesInFlight = 10, bytesInFlight = 10_000, framesDropped = 20, callbacksWaiting = 9)
        }

        held.letGo()
        awaitUntil("nothing in flight") { client.inboundState(relay.url)?.framesInFlight == 0 }
        assertEquals((0 until 10).map { "frame-$it" }, held.frames().map { it.trimEnd('.') })
        assertEquals(0L, client.inboundState(relay.url)?.bytesInFlight)
    }

    @Test
    fun `a frame over the size limit is dropped and the relay stays connected`() = flood(InboundLimits(maxFrameBytes = 2_000)) { client ->
        val relay = relay()
        val listener = heldListener().also { it.letGo() }
        client.connect(relay.url, listener)

        relay.send(listOf("x".repeat(2_001), "y".repeat(2_000), "small"))
        awaitUntil("the frame after it") { listener.frames().lastOrNull() == "small" }

        assertEquals(listOf("y".repeat(2_000), "small"), listener.frames(), "one byte over is dropped, exactly the limit is not")
        assertEquals(1L, client.inboundState(relay.url)?.framesDropped)
        assertEquals(listOf(2_000, 5), decodedSizes.toList(), "the frame over the limit was not even decoded")
        assertEquals(1, relay.connections(), "the relay was not disconnected for it")
        // Asking the relay again would only bring the same frame: a drop for size is not reported as lost.
        relay.send(listOf("end"))
        awaitUntil("one more frame, so every turn before it is over") { listener.frames().lastOrNull() == "end" }
        assertEquals(emptyList(), listener.drained())
        assertTrue("(0 over its backlog limit, 1 over the frame size limit" in reports.single(), reports.single())
    }

    @Test
    fun `a relay that lost frames for want of room is reported once its backlog has drained`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val relay = relay()
        val inFlightWhenReported = CopyOnWriteArrayList<Int?>()
        val held = heldListener(onDrained = { url -> inFlightWhenReported += client.inboundState(url)?.framesInFlight })
        client.connect(relay.url, held)

        relay.send((0 until 100).map { "frame-$it" })
        awaitUntil("92 dropped and the listener inside the first frame") {
            client.inboundState(relay.url)?.let { it.framesDropped == 92L && it.callbacksWaiting == 7 } == true && held.frames().size == 1
        }
        assertEquals(emptyList(), held.drained(), "eight frames are still in flight")

        held.letGo()
        awaitUntil("the report") { held.drained().isNotEmpty() }
        // One more frame through the same dispatcher: every turn before it is over by the time it arrives.
        relay.send(listOf("end"))
        awaitUntil("one more frame") { held.frames().lastOrNull() == "end" }
        relay.send(listOf("end again"))
        awaitUntil("and another") { held.frames().lastOrNull() == "end again" }

        assertEquals(listOf(92L), held.drained(), "reported once, with what was dropped")
        assertEquals(listOf<Int?>(0), inFlightWhenReported.toList(), "and only when nothing was in flight any more")
    }

    @Test
    fun `a listener that fails when told of dropped frames does not stop the others being called`() = flood(InboundLimits(maxFramesInFlight = 2)) { client ->
        val relay = relay()
        val told = CountDownLatch(1)
        val held = heldListener(onDrained = {
            told.countDown()
            error("the listener cannot cope")
        })
        client.connect(relay.url, held)
        relay.send((0 until 5).map { "frame-$it" })
        awaitUntil("three dropped and the listener inside the first frame") {
            client.inboundState(relay.url)?.let { it.framesDropped == 3L && it.callbacksWaiting == 1 } == true && held.frames() == listOf("frame-0")
        }

        held.letGo()
        assertTrue(told.await(30, TimeUnit.SECONDS), "the listener was never told")
        relay.send(listOf("after"))
        awaitUntil("a frame after the listener failed") { held.frames() == listOf("frame-0", "frame-1", "after") }
    }

    @Test
    fun `a relay that lost nothing is not reported`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val relay = relay()
        val listener = heldListener().also { it.letGo() }
        client.connect(relay.url, listener)

        relay.send(listOf("one", "two", "three"))
        awaitUntil("three frames") { listener.frames().size == 3 }
        relay.send(listOf("end"))
        awaitUntil("one more frame, so every turn before it is over") { listener.frames().lastOrNull() == "end" }

        assertEquals(emptyList(), listener.drained())
    }

    @Test
    fun `a relay that was disconnected is not reported`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val relay = relay()
        val other = relay()
        val held = heldListener()
        client.connect(relay.url, held)
        relay.send((0 until 20).map { "frame-$it" })
        awaitUntil("twelve dropped and the listener inside the first frame") {
            client.inboundState(relay.url)?.let { it.framesDropped == 12L && it.callbacksWaiting == 7 } == true && held.frames() == listOf("frame-0")
        }

        client.disconnect(relay.url)
        held.letGo()
        awaitUntil("nothing in flight") { client.inboundState(relay.url)?.framesInFlight == 0 }
        // Another relay's frame through the same dispatcher: the turns of the first are over by then.
        client.connect(other.url, held)
        other.send(listOf("from the other relay"))
        awaitUntil("the other relay's frame") { held.frames().lastOrNull() == "from the other relay" }

        assertEquals(emptyList(), held.drained(), "nobody is listening to that relay any more")
    }

    @Test
    fun `a log line that cannot be written does not cost the relay its connection`() = flood(InboundLimits(maxFramesInFlight = 2)) { client ->
        reportFails = true
        val relay = relay()
        val held = heldListener()
        client.connect(relay.url, held)

        relay.send((0 until 5).map { "frame-$it" })
        awaitUntil("three frames dropped") { client.inboundState(relay.url)?.framesDropped == 3L }
        assertEquals(1, reports.size, "the report was attempted: $reports")

        held.letGo()
        awaitUntil("nothing in flight") { client.inboundState(relay.url)?.framesInFlight == 0 }
        relay.send(listOf("after"))
        awaitUntil("the next frame on the same connection") { held.frames() == listOf("frame-0", "frame-1", "after") }
        assertEquals(1, relay.connections())
    }

    @Test
    fun `one relay's backlog does not stand in front of another relay`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val flooding = relay()
        val quiet = relay()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val held = heldListener(
            onOpen = { url -> order += "open ${if (url == flooding.url) "flooding" else "quiet"}" },
            onFrame = { url -> order += if (url == flooding.url) "flooding" else "quiet" },
        )

        client.connect(flooding.url, held)
        flooding.send((0 until 20).map { "frame-$it" })
        // Its open was handled, the listener is inside its first frame, seven wait, twelve were dropped.
        awaitUntil("the flooding relay's backlog is full and the listener is inside its first frame") {
            client.inboundState(flooding.url)?.let { it.framesDropped == 12L && it.callbacksWaiting == 7 } == true && held.frames().size == 1
        }
        // Only now does the quiet relay connect and say its one thing; both calls wait for their turn.
        client.connect(quiet.url, held)
        quiet.send(listOf("hello"))
        awaitUntil("the quiet relay's open and frame are waiting") { client.inboundState(quiet.url)?.callbacksWaiting == 2 }

        held.letGo()
        awaitUntil("everything handled") {
            client.inboundState(flooding.url)?.framesInFlight == 0 && client.inboundState(quiet.url)?.framesInFlight == 0
        }

        val handled = synchronized(order) { order.toList() }
        assertEquals(
            listOf("open flooding", "flooding", "open quiet", "flooding", "quiet"),
            handled.take(5),
            "the quiet relay takes turns with the flooding one instead of waiting for its seven frames",
        )
        assertEquals(8, handled.count { it == "flooding" })
    }

    @Test
    fun `a full backlog does not cost a relay its reconnect`() = flood(InboundLimits(maxFramesInFlight = 2)) { client ->
        val relay = relay()
        val opens = CountDownLatch(2)
        val held = heldListener(onOpen = { opens.countDown() })
        client.connect(relay.url, held, initialBackoffMs = 1)

        relay.send((0 until 10).map { "frame-$it" })
        // The first open was handled and the listener is inside the first frame; the second waits.
        awaitUntil("the backlog is full and the listener is inside the first frame") {
            client.inboundState(relay.url)?.let { it.framesDropped == 8L && it.callbacksWaiting == 1 } == true && held.frames() == listOf("frame-0")
        }
        // The connection ends, and is dialled again, while the listener is still inside the first
        // frame and the backlog is full: the end of a connection and its retry are not frames.
        relay.dropConnection()
        awaitUntil("the client dialled again") { relay.connections() == 2 }

        held.letGo()
        awaitUntil("room again") { client.inboundState(relay.url)?.framesInFlight == 0 }
        relay.send(listOf("again"))
        awaitUntil("a frame of the new connection") { held.frames().lastOrNull() == "again" }
        assertTrue(opens.await(30, TimeUnit.SECONDS), "the second connection was never reported open")
        // The frame that waited behind the first belongs to the connection that ended: whether it is
        // still handed over depends on whether the new one had opened by then.
        assertEquals(listOf("frame-0", "again"), held.frames() - "frame-1")
    }

    @Test
    fun `frames of a connection that was given up give their room back`() = flood(InboundLimits(maxFramesInFlight = 8)) { client ->
        val relay = relay()
        val held = heldListener()
        client.connect(relay.url, held)
        relay.send((0 until 8).map { "frame-$it" })
        // The listener is inside the first frame; the other seven wait.
        awaitUntil("eight in flight and the listener inside the first") {
            client.inboundState(relay.url)?.let { it.framesInFlight == 8 && it.callbacksWaiting == 7 } == true && held.frames() == listOf("frame-0")
        }

        // Seven frames are waiting for a listener that will now never be called for them.
        client.disconnect(relay.url)
        held.letGo()

        awaitUntil("nothing in flight") { client.inboundState(relay.url) == InboundState(0, 0, 0, 0) }
        assertEquals(listOf("frame-0"), held.frames(), "only the frame the listener was already inside")
    }

    private fun flood(limits: InboundLimits, test: suspend (KtorWebSocketClient) -> Unit) = runBlocking {
        val client = client(limits)
        try {
            test(client)
        } finally {
            heldListeners.forEach { it.letGo() }
            runCatching { client.shutdown() }
            http.close()
            relays.forEach { it.close() }
        }
    }
}

class OkHttpInboundFloodTest : InboundFloodTest() {
    override fun httpClient() = HttpClient(OkHttp) { install(WebSockets) }
}

class CioInboundFloodTest : InboundFloodTest() {
    override fun httpClient() = HttpClient(CIO) { install(WebSockets) }
}
