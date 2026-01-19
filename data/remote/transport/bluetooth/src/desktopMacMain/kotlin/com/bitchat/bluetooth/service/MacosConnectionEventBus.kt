package com.bitchat.bluetooth.service

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_get_global_queue

class MacosConnectionEventBus(
    private val coroutineScopeFacade: CoroutineScopeFacade
) : ConnectionEventBus {

    private val connectionState = MutableStateFlow(getCurrentConnectionState())
    private val bluetoothConnectionState = MutableStateFlow<BluetoothConnectionEvent?>(null)
    private val locationConnectionState = MutableStateFlow(LocationConnectionEvent.DISCONNECTED)

    private val networkMonitor = nw_path_monitor_create()
    private val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)

    private var centralManager: CBCentralManager? = null
    private var bluetoothDelegate: BluetoothDelegate? = null

    init {
        setupNetworkMonitoring()
        setupBluetoothMonitoring()
    }

    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> {
        connectionState.emit(getCurrentConnectionState())
        return connectionState
    }

    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> {
        return bluetoothConnectionState.filterNotNull()
    }

    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> {
        // Location not needed on macOS desktop - always report disconnected
        return locationConnectionState
    }

    private fun setupNetworkMonitoring() {
        nw_path_monitor_set_update_handler(networkMonitor) { path ->
            val status = nw_path_get_status(path)
            val event = when (status) {
                nw_path_status_satisfied, nw_path_status_satisfiable -> ConnectionEvent.CONNECTED
                else -> ConnectionEvent.DISCONNECTED
            }

            coroutineScopeFacade.connectivityEventScope.launch {
                connectionState.emit(event)
            }
        }

        nw_path_monitor_set_queue(networkMonitor, queue)
        nw_path_monitor_start(networkMonitor)
    }

    private fun setupBluetoothMonitoring() {
        bluetoothDelegate = BluetoothDelegate { state ->
            println("🔵 [MacosConnectionEventBus] Bluetooth state changed: ${bluetoothStateString(state)}")
            val event = when (state) {
                CBManagerStatePoweredOn -> BluetoothConnectionEvent.CONNECTED
                CBManagerStatePoweredOff,
                CBManagerStateUnauthorized,
                CBManagerStateUnsupported -> BluetoothConnectionEvent.DISCONNECTED

                else -> {
                    // Don't emit for Unknown/Resetting states - wait for definitive state
                    println("🔵 [MacosConnectionEventBus] Waiting for definitive state (current: ${bluetoothStateString(state)})")
                    return@BluetoothDelegate
                }
            }

            println("🔵 [MacosConnectionEventBus] Emitting event: $event")
            coroutineScopeFacade.connectivityEventScope.launch {
                bluetoothConnectionState.value = event
            }
        }

        centralManager = CBCentralManager(
            delegate = bluetoothDelegate,
            queue = null
        )
        println("🔵 [MacosConnectionEventBus] CBCentralManager created, waiting for actual state...")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getCurrentConnectionState(): ConnectionEvent {
        val reachability = SCNetworkReachabilityCreateWithName(null, "www.apple.com")

        return memScoped {
            val flags = alloc<SCNetworkReachabilityFlagsVar>()

            if (reachability != null &&
                SCNetworkReachabilityGetFlags(reachability, flags.ptr)
            ) {
                if ((flags.value and kSCNetworkReachabilityFlagsReachable.toUInt()) != 0u) {
                    ConnectionEvent.CONNECTED
                } else {
                    ConnectionEvent.DISCONNECTED
                }
            } else {
                ConnectionEvent.DISCONNECTED
            }
        }
    }

    private fun bluetoothStateString(state: CBManagerState?): String {
        return when (state) {
            CBManagerStateUnknown -> "Unknown"
            CBManagerStateResetting -> "Resetting"
            CBManagerStateUnsupported -> "Unsupported"
            CBManagerStateUnauthorized -> "Unauthorized"
            CBManagerStatePoweredOff -> "Powered Off"
            CBManagerStatePoweredOn -> "Powered On"
            null -> "null"
            else -> "Other ($state)"
        }
    }

    fun cleanup() {
        nw_path_monitor_cancel(networkMonitor)
        centralManager = null
        bluetoothDelegate = null
    }

    private class BluetoothDelegate(
        private val onStateChange: (CBManagerState) -> Unit
    ) : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            onStateChange(central.state)
        }
    }
}
