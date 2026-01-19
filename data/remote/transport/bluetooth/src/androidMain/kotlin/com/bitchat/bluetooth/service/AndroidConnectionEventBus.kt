package com.bitchat.bluetooth.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.os.Build
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

internal class AndroidConnectionEventBus(
    private val context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : ConnectionEventBus {
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver()

    private var locationStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.MODE_CHANGED_ACTION ||
                intent.action == LocationManager.PROVIDERS_CHANGED_ACTION
            ) {
                val locationEvent = isLocationConnected()
                coroutineScopeFacade.connectivityEventScope.launch {
                    locationState.emit(locationEvent)
                }
            }
        }
    }

    private val connectionState = MutableStateFlow(isConnected())
    private val bluetoothConnectionState = MutableStateFlow(isBluetoothConnected())

    private val locationState = MutableStateFlow(isLocationConnected())

    private val networkRequest: NetworkRequest by lazy {
        NetworkRequest.Builder().addTransportType(
            NetworkCapabilities.TRANSPORT_CELLULAR
        ).addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            coroutineScopeFacade.connectivityEventScope.launch {
                connectionState.emit(ConnectionEvent.CONNECTED)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            coroutineScopeFacade.connectivityEventScope.launch {
                connectionState.emit(ConnectionEvent.DISCONNECTED)
            }
        }
    }

    init {
        coroutineScopeFacade.connectivityEventScope.launch {
            connectionState
                .onStart {
                    registerCallback()
                }
                .onCompletion {
                    unregisterCallback()
                }
                .collect {
                    // This is needed to keep the coroutine active.
                    // don't need to do anything with collected values
                }
        }
        coroutineScopeFacade.connectivityEventScope.launch {
            bluetoothConnectionState
                .onStart {
                    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                    context.registerReceiver(bluetoothStateReceiver, filter)
                }
                .onCompletion {
                    context.unregisterReceiver(bluetoothStateReceiver)
                }
                .collect {
                    // This is needed to keep the coroutine active.
                    // don't need to do anything with collected values
                }
        }
        coroutineScopeFacade.connectivityEventScope.launch {
            locationState
                .onStart {
                    val filter = IntentFilter().apply {
                        addAction(LocationManager.MODE_CHANGED_ACTION)
                        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                    }
                    context.registerReceiver(locationStateReceiver, filter)
                }
                .onCompletion {
                    context.unregisterReceiver(locationStateReceiver)
                }
                .collect {
                    // This is needed to keep the coroutine active.
                    // don't need to do anything with collected values
                }
        }

    }

    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> {
        connectionState.emit(isConnected())

        return connectionState
    }


    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> {
        bluetoothConnectionState.emit(isBluetoothConnected())

        return bluetoothConnectionState
    }

    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> {
        locationState.emit(isLocationConnected())
        return locationState
    }

    private fun isConnected(): ConnectionEvent {
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo

        return if (activeNetwork?.isConnected == true) {
            ConnectionEvent.CONNECTED
        } else {
            ConnectionEvent.DISCONNECTED
        }
    }

    private fun isBluetoothConnected(): BluetoothConnectionEvent {
        val connected = if (bluetoothManager.adapter.isEnabled) {
            BluetoothConnectionEvent.CONNECTED
        } else {
            BluetoothConnectionEvent.DISCONNECTED
        }

        println("is bluetooth connected: $connected")
        return connected
    }

    private fun isLocationConnected(): LocationConnectionEvent {
        val connected = try {
            locationManager.let { lm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+ (Android 9) - Modern approach
                    lm.isLocationEnabled
                } else {
                    // Older devices - Check individual providers
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        println("is location connected: $connected")
        return if (connected) LocationConnectionEvent.CONNECTED else LocationConnectionEvent.DISCONNECTED
    }

    private fun registerCallback() {
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun unregisterCallback() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onFailure { it.printStackTrace() }
    }

    inner class BluetoothStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent?.action) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        coroutineScopeFacade.connectivityEventScope.launch {
                            bluetoothConnectionState.emit(BluetoothConnectionEvent.DISCONNECTED)
                        }
                    }

                    BluetoothAdapter.STATE_ON -> {
                        coroutineScopeFacade.connectivityEventScope.launch {
                            bluetoothConnectionState.emit(BluetoothConnectionEvent.CONNECTED)
                        }
                    }
                }
            }
        }
    }
}
