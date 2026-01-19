package com.bitchat.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.bluetooth.service.AndroidGattServerService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class AndroidCentralScanningService(
    context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : CentralScanningService {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: BleScanCallback? = null
    private var isScanning = false

    private var onDeviceDiscoveredCallback: ((String, String?, Int) -> Unit)? = null

    init {
        val bluetoothManager: BluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    fun setOnDeviceDiscoveredCallback(callback: (String, String?, Int) -> Unit) {
        this.onDeviceDiscoveredCallback = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning, stopping first")
            stopScan()
        }

        Log.i(TAG, "Starting BLE scan for Bitchat devices")

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(AndroidGattServerService.SERVICE_UUID))
                .build()
        )

        val scanMode = if (lowLatency) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        scanCallback = BleScanCallback()

        isScanning = true
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)

        Log.i(TAG, "BLE scan started")
    }

    override suspend fun stopScan() {
        if (!isScanning) {
            return
        }

        if (bluetoothAdapter?.state != BluetoothAdapter.STATE_ON) {
            Log.w(TAG, "Bluetooth not enabled, cannot stop scan")
            isScanning = false
            return
        }

        scanCallback?.let { callback ->
            try {
                bluetoothLeScanner?.stopScan(callback)
                bluetoothLeScanner?.flushPendingScanResults(callback)
                Log.i(TAG, "BLE scan stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan: ${e.message}")
            }
        }

        isScanning = false
        scanCallback = null
    }

    private inner class BleScanCallback : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning) {
                return
            }

            val device = result.device
            val rssi = result.rssi
            val deviceName = device.name ?: "Unknown"
            val deviceAddress = device.address

            coroutineScopeFacade.applicationScope.launch {
                onDeviceDiscoveredCallback?.invoke(deviceAddress, deviceName, rssi)
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            if (!isScanning) {
                return
            }

            Log.d(TAG, "Batch scan results: ${results.size} devices")

            results.forEach { result ->
                val device = result.device
                val rssi = result.rssi
                val deviceName = device.name ?: "Unknown"
                val deviceAddress = device.address

                Log.d(TAG, "Batch discovered: $deviceName ($deviceAddress), RSSI: $rssi")

                coroutineScopeFacade.applicationScope.launch {
                    onDeviceDiscoveredCallback?.invoke(deviceAddress, deviceName, rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            super.onScanFailed(errorCode)
            isScanning = false
        }
    }

    fun cleanup() {
        coroutineScopeFacade.applicationScope.launch {
            stopScan()
        }
    }

    companion object {
        private const val TAG = "AndroidCentralScanningService"
    }
}
