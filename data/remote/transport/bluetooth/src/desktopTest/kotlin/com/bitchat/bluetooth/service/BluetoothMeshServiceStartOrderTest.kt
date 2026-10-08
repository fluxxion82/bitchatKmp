package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the mesh service does before it becomes visible.
 *
 * On a board, bluetoothd keeps a link when the app exits, and the next process is told nothing
 * about it. The connection service drops such links in [BluetoothConnectionService.prepareForStart].
 * That is only safe while nothing can have connected to the new process yet, so it has to be
 * finished before the first advertisement goes out, not merely started.
 */
class BluetoothMeshServiceStartOrderTest {

    @Test
    fun linksFromBeforeStartAreDealtWithBeforeAnythingIsAdvertised() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val service = BluetoothMeshService(
            scanningService = object : CentralScanningService {
                override suspend fun startScan(lowLatency: Boolean) { events += "scan" }
                override suspend fun stopScan() = Unit
            },
            connectionService = object : BluetoothConnectionService {
                override suspend fun prepareForStart() {
                    events += "prepare begins"
                    gate.await()
                    events += "prepare ends"
                }
                override suspend fun connectToDevice(deviceAddress: String) = Unit
                override suspend fun confirmDevice() = Unit
                override suspend fun isDeviceConnecting(deviceAddress: String) = false
                override suspend fun disconnectDeviceByAddress(deviceAddress: String) = Unit
                override suspend fun clearConnections() = Unit
                override suspend fun broadcastPacket(packetData: ByteArray) = false
                override fun hasRequiredPermissions() = true
                override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) = Unit
                override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) = Unit
                override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) = Unit
            },
            gattServerService = object : GattServerService {
                override suspend fun startAdvertising() { events += "server" }
                override suspend fun stopAdvertising() = Unit
                override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) = Unit
                override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray) = true
                override fun setDelegate(delegate: GattServerDelegate) = Unit
            },
            advertisingService = object : AdvertisingService {
                override suspend fun startAdvertising(serviceUuid: String, deviceName: String) { events += "advertise" }
                override suspend fun stopAdvertising() = Unit
                override fun isAdvertising() = false
            },
            cryptoSigning = CryptoSigningFacade("1".repeat(64))
        )

        try {
            service.startServices()
            eventually("the preparation to begin") { "prepare begins" in events }

            // While the preparation has not returned, nothing else may have been started. The wait
            // is what a start that did not wait for it would need to get to the advertisement.
            delay(300)
            assertEquals(listOf("prepare begins"), events.toList())

            gate.complete(Unit)
            eventually("the scan to start") { "scan" in events }
            assertEquals(
                listOf("prepare begins", "prepare ends", "advertise", "server", "scan"),
                events.toList().take(5)
            )
        } finally {
            gate.complete(Unit)
            service.stopServices()
        }
    }

    private suspend fun eventually(description: String, condition: () -> Boolean) {
        val reached = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30.seconds) {
                while (!condition()) delay(10)
                true
            } ?: false
        }
        assertTrue(reached, "timed out waiting for $description")
    }
}
