package com.bitchat.tor

import com.bitchat.tor.native.arti_set_log_callback
import com.bitchat.tor.native.arti_set_status_callback
import com.bitchat.tor.native.arti_start
import com.bitchat.tor.native.arti_stop_generation
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import platform.Foundation.NSLog
import kotlin.concurrent.Volatile

/** One native status, already copied out of the callback's transient memory. */
internal data class ArtiStatusEvent(
    val state: Int,
    val port: Int,
    val generation: ULong,
    val message: String,
)

/**
 * Receives one copied native status. Implementations are called from Arti's threads, or from the
 * caller's own thread inside `arti_start`/`arti_stop_generation`, while the wrapper holds its locks:
 * they must only enqueue and return (B2.5).
 */
internal fun interface ArtiStatusSink {
    fun onStatus(state: Int, port: Int, generation: ULong, message: String)
}

/**
 * The Arti entry points [TorManager] drives (`arti_ios.h`, shared with Linux), behind an interface
 * so the manager's state machine can be exercised without bootstrapping Tor. Production always gets
 * [ArtiNativeBinding].
 */
internal interface ArtiNative {
    /** Installs the process-wide status sink; statuses for every later generation go through it. */
    fun setStatusSink(sink: ArtiStatusSink)

    /**
     * `arti_start`: `0` when the bootstrap task was spawned (progress then arrives as statuses tagged
     * with [generation]), negative on a rejected argument or a failed stop of the previous generation.
     * Blocks while that previous generation is joined (15 s bound).
     */
    fun start(dataDir: String, requestedPort: Int, generation: ULong): Int

    /**
     * `arti_stop_generation`: aborts and joins the generation's bootstrap, listener and connection
     * tasks, reports STOPPED (or ERROR after the 15 s bound, returning -3), then returns.
     */
    fun stopGeneration(generation: ULong): Int
}

/**
 * The one thread every blocking Arti call runs on, for the life of the process (B1.1). Every
 * dispatcher in the Apple `CoroutinesContextFacade` is the main queue, and `arti_start`/
 * `arti_stop_generation` block while Arti joins its tasks, so a native call on the facade's contexts
 * would freeze the UI. Never closed: dispatching to a closed dispatcher throws on Native, and a stop
 * can be requested at any point in the process's life.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal val artiLifecycleWorker: CoroutineDispatcher = newSingleThreadContext("arti-lifecycle")

@Volatile
private var installedSink: ArtiStatusSink? = null

/**
 * The C callback. Runs on an Arti thread, or on the worker inside `arti_start`/`arti_stop_generation`
 * with the wrapper's lifecycle and publication locks held. It copies the message (valid only during
 * this call), hands the event to the sink - which only enqueues - and returns; nothing here waits,
 * takes a Kotlin lock, or calls back into the library.
 */
@OptIn(ExperimentalForeignApi::class)
private fun nativeStatusCallback(state: Int, port: Int, generation: ULong, message: CPointer<ByteVar>?) {
    val copied = message?.toKString().orEmpty()
    installedSink?.onStatus(state, port, generation, copied)
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeLogCallback(message: CPointer<ByteVar>?) {
    logArtiLine(message?.toKString().orEmpty())
}

/**
 * Logs one Arti line. Kotlin/Native hands a Kotlin String to a C variadic as a C string, so the
 * format must be `%s`: `%@` would treat the text as an object pointer and crash on the first line.
 */
internal fun logArtiLine(line: String) {
    NSLog("%s", "TorManager: $line")
}

/** The static library linked into this binary. */
@OptIn(ExperimentalForeignApi::class)
internal object ArtiNativeBinding : ArtiNative {
    // Process-lifetime function pointers: the wrapper keeps whatever it was given last, forever.
    private val statusCallback: CPointer<CFunction<(Int, Int, ULong, CPointer<ByteVar>?) -> Unit>> =
        staticCFunction(::nativeStatusCallback).reinterpret()
    private val logCallback: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>> =
        staticCFunction(::nativeLogCallback).reinterpret()

    override fun setStatusSink(sink: ArtiStatusSink) {
        installedSink = sink
        arti_set_status_callback(statusCallback)
        arti_set_log_callback(logCallback)
    }

    override fun start(dataDir: String, requestedPort: Int, generation: ULong): Int =
        arti_start(dataDir, requestedPort, generation)

    override fun stopGeneration(generation: ULong): Int = arti_stop_generation(generation)
}
