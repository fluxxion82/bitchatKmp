package com.bitchat.bluetooth.linux

import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import com.bitchat.local.bridge.NativeLocationBridge
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/*
 * =============================================================================================
 * Bringing the D-Bus connection up, on demand and without ever blocking the caller.
 * =============================================================================================
 *
 * `BlueZBus.start()` is a synchronous SASL handshake over an AF_UNIX socket. Its own KDoc is
 * explicit that the native-unixsocket transport ignores `withTimeout`, so there is no
 * configuration that bounds how long a wedged bus can hold the call -- and the first thing that
 * ever asks about Bluetooth on this platform is `BluetoothMeshAppInitializer`, during app
 * startup, through `ConnectivityRepository.isBluetoothEnabled()`. A start that hung there would
 * hang app init.
 *
 * So the start runs as its own coroutine on `Dispatchers.IO` and the caller waits on
 * `Deferred.await()`, which *is* cancellable. `withTimeoutOrNull` wrapped directly around a
 * blocking body would not fire at all -- a coroutine that is blocking a thread never reaches a
 * cancellation check -- whereas cancelling an `await()` returns immediately and leaves the
 * abandoned attempt to finish, or not, on its own thread.
 *
 * There is deliberately no memoisation of the attempt. `start()` returns true immediately when a
 * connection already stands, so the success path costs one lock acquisition, and re-attempting is
 * exactly what should happen when the previous try failed because bluetoothd was not running yet.
 * The only case this leaves is a socket that accepts and then never answers, which would leak one
 * IO thread per attempt; that is the price of not hanging the app, and the attempts are rare --
 * one per poll of the adapter state, not one per call.
 */

/** Where an abandoned [BlueZBus.start] finishes after its caller has stopped waiting. */
private val busStartScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bluez-bus-start"))

private val busStartLog = LoggerFactory.getLogger("bitchat.ble.bus")

/**
 * How long a caller waits for the system bus before treating it as absent.
 *
 * Five seconds is far more than a healthy `forSystemBus()` needs (single-digit milliseconds) and
 * far less than a user will wait at a splash screen.
 */
internal const val BUS_START_TIMEOUT_MS: Long = 5_000L

/**
 * Idempotent, bounded, non-throwing: make sure the bus is up, and say whether it is.
 *
 * Called from the transport's start path rather than from a Koin provider. An exception escaping a
 * `single { }` aborts graph construction for the whole application, and a machine with no
 * bluetoothd is a perfectly ordinary machine to launch this app on.
 */
internal suspend fun BlueZBus.ensureStarted(timeoutMs: Long = BUS_START_TIMEOUT_MS): Boolean {
    // Fast path: an adapter is already resolved, so the connection stands and nothing is needed.
    if (status.value is BlueZStatus.Available) return true

    val attempt = busStartScope.async {
        // start() documents that it never throws, but it is called here from a path that must not
        // fail, so the guarantee is enforced rather than trusted.
        runCatching { start() }.getOrElse {
            busStartLog.warn("BlueZ bus start threw, which it should not: {}", it.message)
            false
        }
    }
    val started = withTimeoutOrNull(timeoutMs) { attempt.await() }
    if (started == null) {
        busStartLog.warn(
            "the system bus did not answer within {}ms; treating Bluetooth as unavailable",
            timeoutMs
        )
        return false
    }
    return started
}

/**
 * The desktop-Linux answer to "is Bluetooth usable right now?".
 *
 * This is a gate, not a diagnostic. `BluetoothMeshAppInitializer` and `ChatRepo` both refuse to
 * call `BluetoothMeshService.startServices()` unless this reports CONNECTED, and the stub it
 * replaces reported CONNECTED unconditionally -- so a machine with no adapter, or an adapter that
 * is soft-blocked by rfkill, started a transport that could not export a GATT application, could
 * not advertise, and logged registration failures for as long as the app ran. Reporting the truth
 * turns that into a state the UI can explain.
 *
 * Only the Bluetooth-facing method changed. [getConnectionEvent] is general network reachability,
 * which BlueZ knows nothing about, and [getLocationConnectionEvent] carries a permission check
 * that has nothing to do with this milestone; both are the desktop behaviour verbatim.
 */
class LinuxConnectionEventBus(private val bus: BlueZBus) : ConnectionEventBus {

    private val log = LoggerFactory.getLogger("bitchat.ble.eventbus")

    /**
     * Unchanged from `DesktopConnectionEventBus`.
     *
     * This is the *network* connection event -- on Android it is backed by `ConnectivityManager`,
     * not by the Bluetooth adapter. The desktop has no monitor for it and reports reachable, and
     * making a BlueZ-backed guess here would gate Nostr and Tor on the state of a radio they do
     * not use.
     */
    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> = channelFlow {
        send(ConnectionEvent.CONNECTED)
    }

    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> =
        adapterUsable().map {
            if (it) BluetoothConnectionEvent.CONNECTED else BluetoothConnectionEvent.DISCONNECTED
        }

    /**
     * Verbatim from `DesktopConnectionEventBus`, including the `location.native` check.
     *
     * Nothing about location changes on Linux: geohash channels fall back to IP-based location,
     * which needs no permission, and the `location.native` property is how the macOS build asks
     * for the CoreLocation bridge instead. Rewriting this to always report CONNECTED would drop a
     * permission gate that the same desktop binary still needs when it runs on a Mac.
     */
    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> = channelFlow {
        val useNativeLocation = System.getProperty("location.native")?.lowercase() == "macos"
        val hasPermission = if (useNativeLocation && NativeLocationBridge.isAvailable()) {
            NativeLocationBridge.hasPermission()
        } else {
            true // IP-based fallback doesn't require permission
        }
        println("LinuxConnectionEventBus: location permission = $hasPermission (native=$useNativeLocation)")
        send(if (hasPermission) LocationConnectionEvent.CONNECTED else LocationConnectionEvent.DISCONNECTED)
    }

    /**
     * `Adapter1.Powered` on the adapter this transport is bound to, sampled.
     *
     * Sampled rather than watched because there is nothing to watch: `BlueZBus` narrows its
     * `PropertiesChanged` rule to `arg0='org.bluez.Device1'`, so an adapter-level `Powered` change
     * never reaches it, and widening that rule means touching a file this milestone must not
     * touch. Polling is honest about what it can do -- a user toggling the radio is noticed within
     * [POLL_INTERVAL_MS] -- and `distinctUntilChanged` keeps the cost off every collector
     * downstream.
     *
     * `flowOn(Dispatchers.IO)` is load-bearing twice over: `ensureStarted` waits on a socket and
     * `isPowered()` is a blocking `Properties.Get` round trip, and the only collector of this flow
     * is app initialisation.
     */
    private fun adapterUsable(): Flow<Boolean> = flow {
        while (true) {
            // Re-attempted on every pass, not once: on a laptop that launches the app before
            // bluetoothd is up, the first attempt fails and only a retry will ever see the daemon.
            // It is cheap once connected -- start() returns on a null check under one lock.
            val busUp = bus.ensureStarted()
            emit(busUp && bus.isPowered())
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged()
        .onEach { usable ->
            // Only transitions reach here, so this is a handful of lines over a session and it is
            // the one place that says *why* the mesh did or did not start.
            if (usable) {
                log.info("Bluetooth is usable -- {}", bus.status.value)
            } else {
                log.warn("Bluetooth is not usable -- {}; the mesh will not start", bus.status.value)
            }
        }
        .flowOn(Dispatchers.IO)

    private companion object {
        /**
         * How often the adapter is re-sampled. Long enough that a permanently absent adapter costs
         * nothing, short enough that switching the radio on and returning to the app finds it
         * working rather than needing a restart.
         */
        const val POLL_INTERVAL_MS: Long = 10_000L
    }
}
