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
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
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

class IosConnectionEventBus(
    private val coroutineScopeFacade: CoroutineScopeFacade
) : ConnectionEventBus {

    private val connectionState = MutableStateFlow(getCurrentConnectionState())
    private val bluetoothConnectionState = MutableStateFlow<BluetoothConnectionEvent?>(null)
    private val locationConnectionState = MutableStateFlow(getCurrentLocationState())

    private val networkMonitor = nw_path_monitor_create()
    private val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)

    private var centralManager: CBCentralManager? = null
    private var bluetoothDelegate: BluetoothDelegate? = null
    private var locationManager: CLLocationManager? = null
    private var locationDelegate: LocationDelegate? = null

    init {
        setupNetworkMonitoring()
        setupBluetoothMonitoring()
        setupLocationMonitoring()
    }

    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> {
        connectionState.emit(getCurrentConnectionState())
        return connectionState
    }

    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> {
        return bluetoothConnectionState.filterNotNull()
    }

    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> {
        locationConnectionState.emit(getCurrentLocationState())
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
            println("🔵 [IosConnectionEventBus] Bluetooth state changed: ${bluetoothStateString(state)}")
            val event = when (state) {
                CBManagerStatePoweredOn -> BluetoothConnectionEvent.CONNECTED
                CBManagerStatePoweredOff,
                CBManagerStateUnauthorized,
                CBManagerStateUnsupported -> BluetoothConnectionEvent.DISCONNECTED

                else -> {
                    // Don't emit for Unknown/Resetting states - wait for definitive state
                    println("🔵 [IosConnectionEventBus] Waiting for definitive state (current: ${bluetoothStateString(state)})")
                    return@BluetoothDelegate
                }
            }

            println("🔵 [IosConnectionEventBus] Emitting event: $event")
            coroutineScopeFacade.connectivityEventScope.launch {
                bluetoothConnectionState.value = event  // Set value instead of emit
            }
        }

        centralManager = CBCentralManager(
            delegate = bluetoothDelegate,
            queue = null
        )
        println("🔵 [IosConnectionEventBus] CBCentralManager created, waiting for actual state...")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getCurrentConnectionState(): ConnectionEvent {
        // Use SCNetworkReachability as a fallback
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

    private fun getCurrentBluetoothState(): BluetoothConnectionEvent? {
        return bluetoothConnectionState.value
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

    private fun setupLocationMonitoring() {
        locationDelegate = LocationDelegate { authStatus ->
            println("🗺️ [IosConnectionEventBus] Location authorization changed: ${locationAuthString(authStatus)}")
            val event = when {
                !CLLocationManager.locationServicesEnabled() -> {
                    println("🗺️ [IosConnectionEventBus] Location services disabled at system level")
                    LocationConnectionEvent.DISCONNECTED
                }

                authStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
                        authStatus == kCLAuthorizationStatusAuthorizedAlways -> {
                    println("🗺️ [IosConnectionEventBus] Location services authorized")
                    LocationConnectionEvent.CONNECTED
                }

                else -> {
                    println("🗺️ [IosConnectionEventBus] Location not authorized: ${locationAuthString(authStatus)}")
                    LocationConnectionEvent.DISCONNECTED
                }
            }

            coroutineScopeFacade.connectivityEventScope.launch {
                locationConnectionState.emit(event)
            }
        }

        locationManager = CLLocationManager().apply {
            delegate = locationDelegate
        }
        println("🗺️ [IosConnectionEventBus] CLLocationManager created for monitoring")
    }

    private fun getCurrentLocationState(): LocationConnectionEvent {
        val servicesEnabled = CLLocationManager.locationServicesEnabled()
        val authStatus = locationManager?.authorizationStatus ?: kCLAuthorizationStatusNotDetermined

        println("🗺️ [IosConnectionEventBus] getCurrentLocationState: servicesEnabled=$servicesEnabled, auth=${locationAuthString(authStatus)}")

        return when {
            !servicesEnabled -> LocationConnectionEvent.DISCONNECTED
            authStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
                    authStatus == kCLAuthorizationStatusAuthorizedAlways -> LocationConnectionEvent.CONNECTED

            else -> LocationConnectionEvent.DISCONNECTED
        }
    }

    private fun locationAuthString(status: CLAuthorizationStatus): String {
        return when (status) {
            kCLAuthorizationStatusNotDetermined -> "Not Determined"
            kCLAuthorizationStatusRestricted -> "Restricted"
            kCLAuthorizationStatusDenied -> "Denied"
            kCLAuthorizationStatusAuthorizedWhenInUse -> "Authorized When In Use"
            kCLAuthorizationStatusAuthorizedAlways -> "Authorized Always"
            else -> "Unknown ($status)"
        }
    }

    fun cleanup() {
        nw_path_monitor_cancel(networkMonitor)
        centralManager = null
        bluetoothDelegate = null
        locationManager = null
        locationDelegate = null
    }

    private class BluetoothDelegate(
        private val onStateChange: (CBManagerState) -> Unit
    ) : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            onStateChange(central.state)
        }
    }

    private class LocationDelegate(
        private val onAuthChange: (CLAuthorizationStatus) -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            onAuthChange(manager.authorizationStatus)
        }
    }
}
