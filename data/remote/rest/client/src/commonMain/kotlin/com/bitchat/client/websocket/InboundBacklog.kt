package com.bitchat.client.websocket

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized

/**
 * How much of one relay's traffic may be in flight: read from its socket and not yet through its
 * listener. A relay is not trusted to send only as fast as its frames can be handled, and two of
 * the three engines cannot make it wait (OkHttp and Curl hand frames over without ever suspending),
 * so the only bound that holds everywhere is to stop keeping what arrives.
 *
 * The frame and byte limits leave room for an honest relay replaying stored events to a slow
 * device; [maxFrameBytes] is what upstream iOS allows one frame.
 */
internal class InboundLimits(
    val maxFramesInFlight: Int = 4_096,
    val maxBytesInFlight: Long = 4L * 1024 * 1024,
    val maxFrameBytes: Int = 512 * 1024,
)

/** What one relay has in flight right now, and what it has lost to its limits so far. */
internal data class InboundState(
    val framesInFlight: Int,
    val bytesInFlight: Long,
    val framesDropped: Long,
    val callbacksWaiting: Int,
)

/**
 * The account of one relay's frames in flight. Room is taken when a frame is read and given back
 * exactly once, when its listener call returned or the frame was discarded on the way there.
 */
@OptIn(InternalCoroutinesApi::class)
internal class InboundBacklog(private val limits: InboundLimits) {
    private val lock = SynchronizedObject()
    private var frames = 0
    private var bytes = 0L
    private var dropped = 0L
    private var droppedForRoomSinceDrained = 0L

    /** Whether a frame of [frameBytes] is over the size limit. One that is counts as dropped, whatever room there is. */
    fun refusesSize(frameBytes: Int): Boolean = synchronized(lock) {
        (frameBytes > limits.maxFrameBytes).also { tooLarge -> if (tooLarge) dropped++ }
    }

    /** Takes room for a frame of [frameBytes]; false, and the frame counts as dropped, when the relay has its limit in flight. */
    fun take(frameBytes: Int): Boolean = synchronized(lock) {
        val fits = frames < limits.maxFramesInFlight && bytes + frameBytes <= limits.maxBytesInFlight
        if (fits) {
            frames++
            bytes += frameBytes
        } else {
            dropped++
            droppedForRoomSinceDrained++
        }
        fits
    }

    /**
     * How many frames were dropped for want of room since this last answered, once nothing is in
     * flight any more; null while something is, or when none were. A frame over the size limit does
     * not count: asking again would only bring the same frame.
     */
    fun drainedAfterDrops(): Long? = synchronized(lock) {
        if (frames != 0 || droppedForRoomSinceDrained == 0L) return@synchronized null
        droppedForRoomSinceDrained.also { droppedForRoomSinceDrained = 0 }
    }

    fun giveBack(frameBytes: Int) = synchronized(lock) {
        frames--
        bytes -= frameBytes
    }

    fun state(callbacksWaiting: Int): InboundState = synchronized(lock) {
        InboundState(frames, bytes, dropped, callbacksWaiting)
    }
}
