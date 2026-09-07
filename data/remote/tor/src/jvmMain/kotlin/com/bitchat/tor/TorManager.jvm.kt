package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * The Arti entry points [TorManager] drives, behind an interface so the manager's state machine can
 * be exercised without a native library. Production always gets the JNI implementation.
 *
 * Return contract, from `data/remote/tor/native/arti-desktop-wrapper/src/lib.rs` (do not change it
 * from the Kotlin side - these values are the wrapper's, not ours):
 *
 * - [initialize]: `0` success (the wrapper has built a Tokio runtime and awaited
 *   `TorClient::create_bootstrapped`, so a bootstrapped client exists); `-1` the data directory
 *   string was unusable; `-2` no Tokio runtime; `-3` the Arti client could not be created.
 * - [startSocksProxy]: `0` success - the wrapper bound `127.0.0.1:port` *synchronously* and handed
 *   the listening socket to its accept loop before returning; `-1` the Arti client is not
 *   initialized; `-2` no Tokio runtime; `-3` the bind failed.
 * - [stop]: `0`, always.
 */
internal interface ArtiNative {
    /** False when the JNI library could not be loaded, i.e. no call below can ever succeed. */
    val isAvailable: Boolean

    fun setLogCallback(callback: TorManager.LogCallback)

    fun initialize(dataDir: String): Int

    fun startSocksProxy(port: Int): Int

    fun stop(): Int
}

/**
 * Desktop Tor.
 *
 * State here is derived from the native return codes, never from parsed log text, because the
 * desktop wrapper's `nativeSetLogCallback` is a documented no-op: it logs to stderr and drops the
 * callback (`arti-desktop-wrapper/src/lib.rs`, "Desktop: log callback not implemented (uses
 * stderr)"). While readiness was driven by [handleLogLine] alone, `running`/[TorState.RUNNING] were
 * never reached on JVM desktop, [isProxyReady] was permanently false, and the OkHttp
 * `ProxySelector` in `ktorHttpClient.jvm.kt` answered `NO_PROXY` for every request - desktop Tor
 * bootstrapped and then proxied nothing at all.
 *
 * The proper long-term fix is a real JNI callback bridge in the Rust wrapper (cache the JavaVM in
 * `nativeSetLogCallback`, `AttachCurrentThread` from the tracing subscriber and call back into
 * [LogCallback]), which would also give desktop genuine bootstrap progress. That is tracked in
 * `../docs/reviews/2026-09-07-tor-not-proxying-apple-linux.md`; until it lands, [handleLogLine]
 * stays for the platforms whose libraries *do* deliver lines (Android, linuxArm64) and nothing on
 * JVM depends on it for correctness.
 */
actual class TorManager internal constructor(
    private val dataDir: String,
    private val native: ArtiNative,
) {
    actual constructor(dataDir: String) : this(dataDir, Jni)

    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            // Surfaced by the settings Tor card, so an unusable install explains itself
            // without the user having to switch Tor on first.
            lastLogLine = if (native.isAvailable) "" else nativeUnavailableSummary(),
            // The actionable reason has to be here, not only in [start]: the settings switch is
            // disabled on a host with no library, so the user can never trigger the start that
            // would fill this in. It is what SettingsState.torErrorMessage carries, and the only
            // thing standing between the user and the generic "tor not available in this build" -
            // which names no file, no lookup path and no way to fix it. It also opens the settings
            // Tor status card, which is gated on this being non-null.
            errorMessage = if (native.isAvailable) null else nativeUnavailableDetail(),
            state = TorState.OFF,
            socksPort = DEFAULT_SOCKS_PORT
        )
    )

    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var currentPort = DEFAULT_SOCKS_PORT

    init {
        File(dataDir).mkdirs()

        if (native.isAvailable) {
            // Registered for the day the wrapper learns to call it; today it is dropped on the
            // Rust side, which is why start() derives state from return codes instead.
            callNative {
                native.setLogCallback(object : LogCallback {
                    override fun onLogLine(message: String?) {
                        message?.let { handleLogLine(it) }
                    }
                })
            }.onFailure { e ->
                // Every native entry point can raise UnsatisfiedLinkError, which is a LinkageError
                // and therefore escapes `catch (e: Exception)` straight out of the Koin graph.
                System.err.println("$TAG: Failed to set log callback: ${e.message}")
            }
        }
        // The "library missing" case was already reported once from the companion initializer;
        // staying quiet here keeps startup output to a single, clear line.
    }

    actual fun getSocksProxyAddress(): Pair<String, Int>? {
        return if (isProxyReady()) {
            Pair("127.0.0.1", currentPort)
        } else {
            null
        }
    }

    actual fun isProxyReady(): Boolean {
        val status = _statusFlow.value
        // Deliberately not gated on bootstrapPercent. "The SOCKS proxy is listening" is the
        // safety-relevant property and it is distinct from "bootstrap is at 100%": traffic handed
        // to a listening Arti SOCKS port is carried by Tor or refused outright, never leaked
        // directly, even while circuits are still being built. Requiring a percentage that only a
        // log line can supply is exactly what left this false forever on desktop.
        return status.mode != TorMode.OFF &&
                status.running &&
                status.state == TorState.RUNNING
    }

    /**
     * False on any host where the Arti JNI library could not be loaded, i.e. Tor can never run
     * here no matter what the user switches on. Callers use it to disable the Tor setting and to
     * skip waiting for a proxy that will never arrive.
     */
    actual val isAvailable: Boolean
        get() = native.isAvailable

    actual suspend fun start() {
        if (!native.isAvailable) {
            _statusFlow.update {
                it.copy(
                    state = TorState.ERROR,
                    errorMessage = nativeUnavailableDetail(),
                    lastLogLine = nativeUnavailableSummary()
                )
            }
            return
        }

        if (!initialized) {
            println("$TAG: Initializing Arti...")
            val result = callNative { native.initialize(dataDir) }.getOrElse { e ->
                System.err.println("$TAG: Failed to initialize: ${e.message}")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
                return
            }

            if (result != NATIVE_OK) {
                System.err.println("$TAG: Initialization failed: $result")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Init failed: $result") }
                return
            }

            initialized = true
        }

        println("$TAG: Starting SOCKS proxy on port $currentPort...")
        _statusFlow.update {
            it.copy(mode = TorMode.ON, state = TorState.STARTING, errorMessage = null)
        }

        val result = callNative { native.startSocksProxy(currentPort) }.getOrElse { e ->
            System.err.println("$TAG: Failed to start proxy: ${e.message}")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
            return
        }

        if (result != NATIVE_OK) {
            System.err.println("$TAG: Start proxy failed: $result")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Start failed: $result") }
            return
        }

        markProxyListening()
    }

    /**
     * Called only after [ArtiNative.startSocksProxy] returned [NATIVE_OK], which the wrapper does
     * once the listening socket is bound - so from here on traffic pointed at the port is Tor
     * traffic and [isProxyReady] can say so.
     *
     * `bootstrapPercent` is cosmetic progress for the status card, not a gate. It is honest at 100
     * on this platform because [ArtiNative.initialize] blocks in `TorClient::create_bootstrapped`,
     * so a proxy that starts here is already backed by a bootstrapped client.
     */
    private fun markProxyListening() {
        val line = "SOCKS proxy listening on 127.0.0.1:$currentPort"
        println("$TAG: $line")
        _statusFlow.update {
            it.copy(
                mode = TorMode.ON,
                running = true,
                bootstrapPercent = 100,
                state = TorState.RUNNING,
                lastLogLine = line,
                errorMessage = null
            )
        }
    }

    actual suspend fun stop() {
        var stopFailure: String? = null

        if (native.isAvailable) {
            println("$TAG: Stopping Tor...")
            _statusFlow.update { it.copy(state = TorState.STOPPING) }

            callNative { native.stop() }.onFailure { e ->
                System.err.println("$TAG: Failed to stop: ${e.message}")
                stopFailure = "Failed to stop Tor: ${e.message}"
            }
        }

        _statusFlow.update {
            it.copy(
                mode = TorMode.OFF,
                running = false,
                bootstrapPercent = 0,
                state = TorState.OFF,
                // A stop that worked clears whatever went wrong before it. A stop that threw keeps
                // its own reason instead of reporting a clean shutdown that did not happen.
                // "No Arti library" is not something a stop can fix, so it survives here exactly
                // as it survives in lastLogLine below - otherwise switching Tor off would replace
                // the one actionable message with the generic "not available in this build".
                errorMessage = if (native.isAvailable) stopFailure else nativeUnavailableDetail(),
                lastLogLine = if (native.isAvailable) it.lastLogLine else nativeUnavailableSummary()
            )
        }
    }

    actual fun destroy() {

    }

    /**
     * Runs one JNI entry point, handing back the failure instead of throwing it.
     *
     * A partially installed library makes any `native*` call raise UnsatisfiedLinkError, which is
     * a LinkageError rather than an Exception, so both have to be handled here. Catching bare
     * Throwable would also swallow VirtualMachineError (OutOfMemoryError, StackOverflowError),
     * which must keep propagating - Tor status is not worth pretending the JVM is healthy.
     */
    private inline fun <T> callNative(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: LinkageError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Refines the status from Arti's own log lines. Dead on JVM desktop - the wrapper drops the
     * callback - but kept working for builds and platforms whose library does deliver lines, and
     * for the day the desktop wrapper gains a real callback bridge. It only ever *refines* what
     * [start] already established from the return codes.
     */
    private fun handleLogLine(line: String) {
        println("$TAG: Arti: $line")

        _statusFlow.update { it.copy(lastLogLine = line) }

        when {
            line.contains("AMEx: state changed to Initialized", ignoreCase = true) ||
                    line.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = it.state.unlessListening(TorState.STARTING)) }
            }

            line.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = maxOf(it.bootstrapPercent, 75),
                        state = it.state.unlessListening(TorState.BOOTSTRAPPING)
                    )
                }
            }

            line.contains("We have found that guard [scrubbed] is usable", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 100,
                        state = TorState.RUNNING,
                        running = true
                    )
                }
            }

            line.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.STOPPING, running = false) }
            }

            line.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
            }

            line.contains("ERROR", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = line) }
            }
        }
    }

    /**
     * Progress lines must not demote a proxy [start] already saw listening: on this platform the
     * native return code is the authority on readiness, and only [stop] or a genuine failure takes
     * it away. Relevant the day the wrapper starts delivering log lines, since its "sufficiently
     * bootstrapped" line arrives *after* the bind that already made the proxy usable.
     */
    private fun TorState.unlessListening(progress: TorState): TorState =
        if (this == TorState.RUNNING) this else progress

    interface LogCallback {
        fun onLogLine(message: String?)
    }

    companion object {
        private const val TAG = "TorManager"
        private const val DEFAULT_SOCKS_PORT = 9050

        /** Success from every `native*` entry point; see [ArtiNative] for the failure codes. */
        internal const val NATIVE_OK = 0

        /**
         * Base name of the Arti JNI library. Every desktop OS uses the same base name; the JVM
         * applies the platform prefix/suffix itself (libarti_desktop.so, libarti_desktop.dylib,
         * arti_desktop.dll).
         */
        private const val ARTI_LIB_NAME = "arti_desktop"

        /**
         * Set by the Compose Desktop runtime to the application's resources directory: a staged
         * directory under `build/compose/tmp/prepareAppResources` for `:apps:desktop:run`, and
         * `$APPDIR/resources` inside an installed package. `:apps:desktop` stages the Arti library
         * there (see its `stageAppResources` task), so an installed app finds it relative to the
         * installation instead of an absolute build-machine path.
         */
        private const val APP_RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

        /**
         * False when [ARTI_LIB_NAME] could not be loaded — the normal state on any host that has
         * not run `data/remote/tor/native/build-desktop.sh` (i.e. every Linux box today). Tor then
         * reports itself unavailable instead of taking the process down with it.
         */
        @Volatile
        internal var libraryLoaded = false
            private set

        internal fun nativeUnavailableSummary(): String =
            "Tor unavailable: native library ${System.mapLibraryName(ARTI_LIB_NAME)} not found"

        internal fun nativeUnavailableDetail(): String =
            "${nativeUnavailableSummary()} in $APP_RESOURCES_DIR_PROPERTY=" +
                    "${System.getProperty(APP_RESOURCES_DIR_PROPERTY)} " +
                    "or on java.library.path=${System.getProperty("java.library.path")}. " +
                    "Build it with data/remote/tor/native/build-desktop.sh, or leave Tor off."

        /** Preferred path: the library shipped next to the application. */
        private fun loadFromAppResources(): Boolean {
            val resourcesDir = System.getProperty(APP_RESOURCES_DIR_PROPERTY) ?: return false
            val library = File(resourcesDir, System.mapLibraryName(ARTI_LIB_NAME))
            if (!library.isFile) return false
            // UnsatisfiedLinkError is a LinkageError, so this must not narrow to Exception -
            // but VirtualMachineError keeps propagating rather than being reported as "no Tor".
            return try {
                System.load(library.absolutePath)
                println("$TAG: Loaded ${library.absolutePath}")
                true
            } catch (e: LinkageError) {
                System.err.println("$TAG: Failed to load ${library.absolutePath}: ${e.message}")
                false
            } catch (e: Exception) {
                System.err.println("$TAG: Failed to load ${library.absolutePath}: ${e.message}")
                false
            }
        }

        /** Fallback for hosts that put the library on java.library.path themselves. */
        private fun loadFromLibraryPath(): Boolean =
            try {
                System.loadLibrary(ARTI_LIB_NAME)
                println("$TAG: Loaded ${System.mapLibraryName(ARTI_LIB_NAME)}")
                true
            } catch (e: LinkageError) {
                false
            } catch (e: Exception) {
                false
            }

        init {
            libraryLoaded = loadFromAppResources() || loadFromLibraryPath()
            if (!libraryLoaded) {
                // Logged exactly once, at class-load time, i.e. the first TorManager construction.
                System.err.println("$TAG: ${nativeUnavailableDetail()}")
            }
        }

        /** The real bridge: every method here is one JNI call into the Arti wrapper. */
        private object Jni : ArtiNative {
            override val isAvailable: Boolean get() = libraryLoaded
            override fun setLogCallback(callback: LogCallback) = nativeSetLogCallback(callback)
            override fun initialize(dataDir: String): Int = nativeInitialize(dataDir)
            override fun startSocksProxy(port: Int): Int = nativeStartSocksProxy(port)
            override fun stop(): Int = nativeStop()
        }

        // JNI functions
        @JvmStatic
        private external fun nativeGetVersion(): String

        @JvmStatic
        private external fun nativeSetLogCallback(callback: LogCallback)

        @JvmStatic
        private external fun nativeInitialize(dataDir: String): Int

        @JvmStatic
        private external fun nativeStartSocksProxy(port: Int): Int

        @JvmStatic
        private external fun nativeStop(): Int
    }
}
