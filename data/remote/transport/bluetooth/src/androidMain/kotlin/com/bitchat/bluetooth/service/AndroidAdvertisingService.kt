package com.bitchat.bluetooth.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class AndroidAdvertisingService(
    private val context: Context
) : AdvertisingService {
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isCurrentlyAdvertising = false

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    }

    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) = suspendCancellableCoroutine { continuation ->
        if (isCurrentlyAdvertising) {
            Log.d(TAG, "Already advertising")
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }

        if (advertiser == null) {
            Log.e(TAG, "Bluetooth LE Advertiser not available")
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }

        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val uuid = try {
                UUID.fromString(serviceUuid)
            } catch (e: Exception) {
                AndroidGattServerService.SERVICE_UUID
            }

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(uuid))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            advertiser?.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d(TAG, "Advertising started successfully with name: $deviceName")
                    isCurrentlyAdvertising = true
                    continuation.resume(Unit)
                }

                override fun onStartFailure(errorCode: Int) {
                    val errorMessage = when (errorCode) {
                        ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                        else -> "Unknown error: $errorCode"
                    }
                    Log.e(TAG, "Advertising failed: $errorMessage")
                    isCurrentlyAdvertising = false
                    continuation.resume(Unit)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting advertising: ${e.message}")
            continuation.resume(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising: ${e.message}")
            continuation.resume(Unit)
        }
    }

    override suspend fun stopAdvertising() {
        if (!isCurrentlyAdvertising) {
            Log.d(TAG, "Not currently advertising")
            return
        }

        try {
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
            isCurrentlyAdvertising = false
            Log.d(TAG, "Advertising stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping advertising: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }
    }

    override fun isAdvertising(): Boolean {
        return isCurrentlyAdvertising
    }

    companion object {
        private const val TAG = "AndroidAdvertising"
    }
}
