package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.bridge.NativeBleBridge
import com.bitchat.bluetooth.linux.BlueZBus
import com.bitchat.bluetooth.linux.LinuxAdvertisingService
import com.bitchat.bluetooth.linux.LinuxConnectionEventBus
import com.bitchat.bluetooth.linux.LinuxConnectionService
import com.bitchat.bluetooth.linux.LinuxGattClientService
import com.bitchat.bluetooth.linux.LinuxGattServerService
import com.bitchat.bluetooth.linux.LinuxScanningService
import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.DesktopAdvertisingService
import com.bitchat.bluetooth.service.DesktopCentralScanningService
import com.bitchat.bluetooth.service.DesktopConnectionEventBus
import com.bitchat.bluetooth.service.DesktopConnectionService
import com.bitchat.bluetooth.service.DesktopGattClientService
import com.bitchat.bluetooth.service.DesktopGattServerService
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import com.bitchat.bluetooth.service.NativeBleAdvertisingService
import com.bitchat.bluetooth.service.NativeBleConnectionService
import com.bitchat.bluetooth.service.NativeBleGattClientService
import com.bitchat.bluetooth.service.NativeBleGattServerService
import com.bitchat.bluetooth.service.NativeBleScanningService
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/*
 * =============================================================================================
 * Which BLE transport the one desktop binary uses, decided at graph-construction time.
 * =============================================================================================
 *
 * The `desktop` JVM target is a single artifact that runs on Linux, macOS and Windows, so this
 * module is the only place that gets to ask which of them it is on. Three answers:
 *
 *   Linux                          BlueZ over D-Bus -- a real transport, both roles
 *   ble.native=macos               the existing CoreBluetooth JNI bridge, unchanged
 *   anything else                  the existing no-op stubs, unchanged
 *
 * The Linux bindings live inside [LinuxBle] rather than in a branch of this lambda, and that is
 * not organisation -- it is the guard. Every class the Linux branch names transitively drags in
 * dbus-java, which is on the classpath everywhere but is only *usable* where an AF_UNIX system bus
 * exists. Keeping those references inside a separate class means the JVM has no reason to load or
 * verify any of them until something on Linux actually calls into it; put in this lambda's body
 * they would be resolved on every desktop platform the moment the module is built.
 */

/**
 * `os.name` is `Linux` on every JVM that matters here (`Mac OS X` on macOS, `Windows 1x`
 * elsewhere). Checked as a prefix rather than for equality because some JVMs append a version.
 */
private val isLinux: Boolean =
    System.getProperty("os.name")?.lowercase()?.startsWith("linux") == true

actual val platformBleModule = module {
    // Unchanged: the macOS bridge is requested by property, not detected, so that a developer can
    // force the stubs on a Mac. Evaluated before the branch so the Linux path never touches it --
    // NativeBleBridge.init() loads a dylib and has no business running on a Linux desktop.
    val useNativeMac = !isLinux && System.getProperty("ble.native")?.lowercase() == "macos"

    if (isLinux) {
        LinuxBle.bindInto(this)
    } else {
        val nativeLoaded = if (useNativeMac) NativeBleBridge.init() else false
        single<AdvertisingService> {
            val fallback = DesktopAdvertisingService()
            if (nativeLoaded) NativeBleAdvertisingService(fallback, true) else fallback
        }
        single<CentralScanningService> {
            val fallback = DesktopCentralScanningService()
            if (nativeLoaded) {
                NativeBleScanningService(fallback, true).apply {
                    setOnDeviceDiscoveredCallback { id, name, rssi ->
                        val connection = get<BluetoothConnectionService>()
                        runBlocking {
                            connection.connectToDevice(id)
                        }
                    }
                }
            } else {
                fallback
            }
        }
        single<GattServerService> {
            val fallback = DesktopGattServerService()
            if (nativeLoaded) NativeBleGattServerService(fallback, true) else fallback
        }
        single<GattClientService> {
            val fallback = DesktopGattClientService()
            if (nativeLoaded) NativeBleGattClientService(fallback, true) else fallback
        }
        single<BluetoothConnectionService> {
            val fallback = DesktopConnectionService()
            if (nativeLoaded) NativeBleConnectionService(fallback) else fallback
        }
        single<ConnectionEventBus> { DesktopConnectionEventBus() }
    }
}

/**
 * The BlueZ bindings, in a class of their own so that nothing here is loaded off Linux.
 *
 * Everything is `single`: BlueZ's registrations are per D-Bus client and this process is one
 * client, so a second [BlueZBus] would mean a second connection with its own discovery lease and
 * its own view of bluetoothd, and a second [LinuxGattServerService] would try to export a GATT
 * application at an object path that is already taken.
 */
private object LinuxBle {

    fun bindInto(module: Module): Unit = with(module) {

        /**
         * Constructed here, connected nowhere near here.
         *
         * `BlueZBus.start()` is a blocking SASL handshake against a socket that may not exist, and
         * an exception -- or a stall -- inside a Koin provider aborts construction of the whole
         * application graph. The connection is opened lazily instead, from
         * `BlueZBus.ensureStarted()`, and a machine with no bluetoothd degrades to the event bus
         * reporting Bluetooth unavailable rather than to a desktop app that will not launch.
         */
        single { BlueZBus() }

        single<AdvertisingService> { LinuxAdvertisingService(get()) }

        // Bound twice on purpose: the mesh takes the `commonMain` interface, while the connection
        // service needs the concrete type for `notifySubscribers`, which is the peripheral-role
        // broadcast the interface has no room for.
        single { LinuxGattServerService(get()) }
        single<GattServerService> { get<LinuxGattServerService>() }

        // Bound twice for the same reason the server is: the mesh takes the `commonMain` interface,
        // while the connection service needs the concrete type for `connect`, `readyAddresses` and
        // `forgetDevice` -- the central-role vocabulary `GattClientService` has no room for.
        single { LinuxGattClientService(get()) }
        single<GattClientService> { get<LinuxGattClientService>() }

        /**
         * Discovery. Bound twice as well: the mesh asks for `CentralScanningService`, and the
         * connection service's wiring below needs the concrete type to install its callback.
         *
         * `startScan` is also where the bus is brought up, which is not incidental. Connecting
         * inside a `single { }` is what the KDoc on the [BlueZBus] binding above refuses to do --
         * an exception or a stall in a provider aborts construction of the whole application graph
         * -- and `BluetoothMeshService.startServices()` is the transport's real start signal.
         */
        single { LinuxScanningService(get()) }
        single<CentralScanningService> { get<LinuxScanningService>() }

        /**
         * The three wires that make the two roles one transport.
         *
         * They are set here rather than inside `LinuxConnectionService`'s constructor so that the
         * direction of every dependency is visible in one place, exactly as `BleModule.kt` does it
         * on Android:
         *
         *  - the GATT **server** hands it inbound frames from centrals that dialled us;
         *  - the GATT **client** hands it inbound frames from peers we dialled, and tells it when
         *    one of those links goes down;
         *  - the **scanner** hands it addresses worth dialling.
         *
         * The scanner's callback is a plain function reference and not a lambda that suspends,
         * because it is invoked from the scanner's own drain loop: `onDeviceDiscovered` returns
         * immediately, having launched the decision onto the connection service's scope. A
         * `runBlocking` here -- which is what the macOS branch above does, and can afford to --
         * would stall discovery for the length of a connection attempt.
         */
        single {
            val server = get<LinuxGattServerService>()
            val client = get<LinuxGattClientService>()
            val scanner = get<LinuxScanningService>()
            LinuxConnectionService(server, client).also { connection ->
                server.setDelegate(connection)
                client.setListener(connection)
                scanner.setOnDeviceDiscoveredCallback(connection::onDeviceDiscovered)
            }
        } bind BluetoothConnectionService::class

        single<ConnectionEventBus> { LinuxConnectionEventBus(get()) }
    }
}
