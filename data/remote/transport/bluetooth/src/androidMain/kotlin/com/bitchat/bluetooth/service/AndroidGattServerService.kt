package com.bitchat.bluetooth.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.bitchat.bluetooth.manager.UnsubscribedCentrals
import com.bitchat.bluetooth.protocol.ChunkReassembler
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
class AndroidGattServerService(
    private val context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : GattServerService {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var delegate: GattServerDelegate? = null

    private var isActive = false
    private val subscribedDevices = mutableMapOf<String, BluetoothDevice>()

    // Centrals that are connected and have not enabled notifications in this process. See the class
    // for why they exist (an app restart keeps the links) and why they are asked to leave.
    private val unsubscribedCentrals = UnsubscribedCentrals()
    private var unsubscribedCentralsJob: Job? = null

    // Held while a subscription is recorded and while a link is looked at one last time and
    // dropped, so that a subscription cannot arrive between that look and the drop.
    private val dropLock = Any()

    // A start, a stop and the set-up of a server each happen as one step. The mesh service launches
    // starts and stops independently, and two that overlapped could overwrite the reference of a
    // running check for unsubscribed centrals (which then outlives a later stop) or leave an active
    // service without one.
    private val lifecycleLock = Any()

    private val reassembler = ChunkReassembler(log = { Log.w(TAG, it) })

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }

    override suspend fun startAdvertising() = synchronized(lifecycleLock) { start() }

    override suspend fun stopAdvertising() = synchronized(lifecycleLock) { stop() }

    private fun start() {
        if (isActive) {
            Log.d(TAG, "GATT server already active")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }

        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }

        isActive = true

        coroutineScopeFacade.applicationScope.launch {
            setupGattServer()
            delay(300)
            startBleAdvertising()
        }

        unsubscribedCentralsJob?.cancel()
        unsubscribedCentralsJob = coroutineScopeFacade.applicationScope.launch {
            // `isActive` here is the service's own flag (the class member shadows the coroutine's);
            // the job is cancelled when the service stops, which ends the delay.
            var reported = UnsubscribedCentrals.Refusing(tracking = false, asking = false)
            while (isActive) {
                delay(UNSUBSCRIBED_CHECK_MS)
                if (!isActive) break
                // Each in a coroutine of its own: the wait inside must not put off the next look.
                unsubscribedCentrals.due(SystemClock.elapsedRealtime()).forEach { due ->
                    launch { askToLeave(due) }
                }
                // One line when a table fills and one when it has room again: a central left deaf
                // for want of room should not have to be guessed at.
                val refusing = unsubscribedCentrals.refusing()
                if (refusing != reported) {
                    reported = refusing
                    when {
                        refusing.tracking -> Log.w(
                            TAG,
                            "Server: more centrals than can be told apart (${UnsubscribedCentrals.MAX_TRACKED}); " +
                                "none is asked to leave until the server is set up again"
                        )
                        refusing.asking -> Log.w(
                            TAG,
                            "Server: ${UnsubscribedCentrals.MAX_TRACKED} centrals were asked to leave in the last " +
                                "${UnsubscribedCentrals.ASK_AGAIN_AFTER_MS / 60_000} min; others that have not subscribed wait"
                        )
                        else -> Log.i(TAG, "Server: centrals that have not subscribed are asked to leave again")
                    }
                }
            }
        }

        Log.i(TAG, "GATT server started")
    }

    private fun stop() {
        if (!isActive) {
            stopBleAdvertising()
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "GATT server stopped (already inactive)")
            return
        }

        isActive = false
        unsubscribedCentralsJob?.cancel()
        unsubscribedCentralsJob = null
        // Disconnections are not reported once the service is inactive, and whatever was subscribed
        // was subscribed to the service this takes down; its callbacks still on their way report to
        // nobody from here on.
        unsubscribedCentrals.onServiceReset()

        coroutineScopeFacade.applicationScope.launch {
            stopBleAdvertising()

            try {
                subscribedDevices.values.forEach { device ->
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (_: Exception) {
                    }
                }
                subscribedDevices.clear()
            } catch (_: Exception) {
            }

            gattServer?.close()
            gattServer = null

            Log.i(TAG, "GATT server stopped")
        }
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        // This is handled internally by the GATT server callback
        // Kept for interface compliance
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        try {
            val device = subscribedDevices[deviceAddress]
            if (device == null) {
                Log.w(TAG, "No subscribed device found for $deviceAddress")
                return false
            }

            val char = characteristic
            if (char == null) {
                Log.w(TAG, "Characteristic not found for notification")
                return false
            }

            val server = gattServer
            if (server == null) {
                Log.w(TAG, "GATT server not available for notification")
                return false
            }

            return if (data.size <= CHUNK_SIZE) {
                char.value = data
                val success = server.notifyCharacteristicChanged(device, char, false)
                if (success) {
                    Log.d(TAG, "Server: Notify succeeded to $deviceAddress (${data.size} bytes)")
                } else {
                    Log.w(TAG, "Server: Notify failed to $deviceAddress")
                }
                success
            } else {
                notifyChunked(device, char, server, deviceAddress, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying characteristic: ${e.message}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun notifyChunked(
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        server: BluetoothGattServer,
        deviceAddress: String,
        data: ByteArray
    ): Boolean {
        val totalSize = data.size
        val chunks = mutableListOf<ByteArray>()

        var offset = 0
        var chunkIndex = 0

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val isFirst = chunkIndex == 0
            val headerSize = if (isFirst) 5 else 1
            val payloadSize = minOf(remaining, CHUNK_SIZE - headerSize)
            val isLast = offset + payloadSize >= totalSize

            val chunkType: Byte = when {
                isFirst -> CHUNK_START
                isLast -> CHUNK_END
                else -> CHUNK_CONTINUE
            }

            val chunk = if (isFirst) {
                ByteArray(1 + 4 + payloadSize).apply {
                    this[0] = chunkType
                    this[1] = ((totalSize shr 24) and 0xFF).toByte()
                    this[2] = ((totalSize shr 16) and 0xFF).toByte()
                    this[3] = ((totalSize shr 8) and 0xFF).toByte()
                    this[4] = (totalSize and 0xFF).toByte()
                    System.arraycopy(data, offset, this, 5, payloadSize)
                }
            } else {
                ByteArray(1 + payloadSize).apply {
                    this[0] = chunkType
                    System.arraycopy(data, offset, this, 1, payloadSize)
                }
            }

            chunks.add(chunk)
            offset += payloadSize
            chunkIndex++
        }

        Log.i(TAG, "Chunking notification ${data.size} bytes into ${chunks.size} chunks for $deviceAddress")

        var allSuccess = true
        for ((index, chunk) in chunks.withIndex()) {
            char.value = chunk
            val success = server.notifyCharacteristicChanged(device, char, false)

            if (!success) {
                Log.e(TAG, "Failed to notify chunk ${index + 1}/${chunks.size} to $deviceAddress")
                allSuccess = false
                break
            }

            Log.d(TAG, "Notified chunk ${index + 1}/${chunks.size} (${chunk.size} bytes) to $deviceAddress")

            if (index < chunks.size - 1) {
                delay(CHUNK_DELAY_MS)
            }
        }

        if (allSuccess) {
            Log.i(TAG, "Successfully notified all ${chunks.size} chunks (${data.size} bytes) to $deviceAddress")
        }

        return allSuccess
    }

    /**
     * Drops the link to a central that writes to us and has not subscribed, so that it connects
     * and subscribes afresh; until then it hears nothing this device sends (see
     * [UnsubscribedCentrals]).
     *
     * A link the central made belongs to no app on this side, and `cancelConnection()` only lets
     * go of a link this server holds: hence the `connect()` first.
     *
     * That `connect()` is a claim: it makes this server a holder of the link or, if the link has
     * just ended, starts a dial to that address. It is given back on every way out of here,
     * a cancelled coroutine included, with one exception: the central has subscribed meanwhile,
     * and letting go of its link would end it.
     */
    @Suppress("MissingPermission")
    private suspend fun askToLeave(due: UnsubscribedCentrals.Due) {
        val deviceAddress = due.address
        val server = gattServer ?: return
        if (!unsubscribedCentrals.isStillDue(due)) return
        val device = subscribedDevices[deviceAddress]
        if (device == null) {
            // Gone already, and its disconnection was not reported to the tracker: the record goes.
            unsubscribedCentrals.takeIfStillDue(due, SystemClock.elapsedRealtime())
            return
        }
        Log.i(
            TAG,
            "Server: $deviceAddress has not subscribed ${UnsubscribedCentrals.SUBSCRIBE_GRACE_MS / 1000} s after its " +
                "first write (a link from before this start?); dropping it so that it connects afresh"
        )
        var claimed = false
        var dropped = false
        try {
            // Claimed under the lock a subscription is recorded under, and only for a connection
            // that is still due: nothing is claimed for a central that has just subscribed.
            claimed = synchronized(dropLock) {
                if (unsubscribedCentrals.isStillDue(due)) {
                    server.connect(device, false)
                    true
                } else {
                    false
                }
            }
            if (!claimed) return
            delay(CLAIM_BEFORE_CANCEL_MS)
            // Looked at again at the last moment, under the same lock: the decision is a few
            // hundred milliseconds old, and a central that subscribed meanwhile, or a new
            // connection from the same address, is not the one that was due.
            dropped = synchronized(dropLock) {
                if (unsubscribedCentrals.takeIfStillDue(due, SystemClock.elapsedRealtime())) {
                    server.cancelConnection(device)
                    true
                } else {
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Server: could not drop the link to $deviceAddress: ${e.message}")
        } finally {
            if (claimed && !dropped) giveBack(server, device)
        }
    }

    /**
     * Gives back what `connect()` claimed for a connection that was then not dropped: the central
     * left (and the claim may be a dial under way to its address), or the coroutine was cancelled.
     * Not when the central has, or may have, subscribed: its link is in use, and the claim ends
     * with the link.
     */
    @Suppress("MissingPermission")
    private fun giveBack(server: BluetoothGattServer, device: BluetoothDevice) {
        try {
            synchronized(dropLock) {
                if (unsubscribedCentrals.mayHaveSubscribed(device.address)) {
                    Log.i(TAG, "Server: ${device.address} subscribed meanwhile; leaving it alone")
                } else {
                    server.cancelConnection(device)
                    Log.i(TAG, "Server: ${device.address} left meanwhile; letting go of it")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server: could not let go of ${device.address}: ${e.message}")
        }
    }

    private fun handleIncomingData(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) {
            Log.w(TAG, "Received empty data from $deviceAddress")
            return
        }

        val frame = reassembler.receive(deviceAddress, value) ?: return
        Log.i(TAG, "Server: Received packet from $deviceAddress, size: ${frame.size} bytes")
        delegate?.onDataReceived(frame, deviceAddress)
    }

    // Under the lock a start and a stop take: two set-ups that overlapped (a start, a stop and a
    // start in quick succession launch two) could leave the server of one open with the tracker
    // listening to the other's callbacks.
    private fun setupGattServer() = synchronized(lifecycleLock) { openGattServer() }

    @Suppress("DEPRECATION")
    private fun openGattServer() {
        // What this server's callbacks report to. Taken right before the server is opened, in
        // the same locked step, so that the tracker always listens to the server that is open; a
        // callback of an earlier server that is still on its way holds that server's own, which
        // the tracker no longer listens to.
        lateinit var centrals: UnsubscribedCentrals.Service

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring connection state change after shutdown")
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")
                        subscribedDevices[device.address] = device
                        // Not reported to `centrals`: this callback also comes for a link this
                        // device's own GATT client made, and the other end of such a link is no
                        // central of ours and owes no subscription. A central is known by its
                        // first write.

                        coroutineScopeFacade.applicationScope.launch {
                            delay(100)
                            if (isActive) {
                                delegate?.onClientConnected(device.address)
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        subscribedDevices.remove(device.address)
                        centrals.onGone(device.address)
                        reassembler.forget(device.address)
                        delegate?.onClientDisconnected(device.address)
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring service added callback after shutdown")
                    return
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Failed to add service: ${service.uuid}, status: $status")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring characteristic write after shutdown")
                    return
                }

                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    // Whoever writes here uses this device as its peripheral: a central. Its
                    // grace runs from its first write on this connection.
                    centrals.onSeen(device.address, SystemClock.elapsedRealtime())

                    // Ensure device is in subscribedDevices so we can notify it back
                    // This handles cases where the connection event was missed or address changed
                    if (!subscribedDevices.containsKey(device.address)) {
                        Log.i(TAG, "Server: Adding write-sender to subscribers: ${device.address}")
                        subscribedDevices[device.address] = device
                        coroutineScopeFacade.applicationScope.launch {
                            delay(100)
                            if (isActive) {
                                delegate?.onClientConnected(device.address)
                            }
                        }
                    }

                    handleIncomingData(device.address, value)

                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring descriptor write after shutdown")
                    return
                }

                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    subscribedDevices[device.address] = device
                    synchronized(dropLock) { centrals.onSubscribed(device.address) }
                    Log.d(TAG, "Server: Connection setup complete for ${device.address}")

                    coroutineScopeFacade.applicationScope.launch {
                        delay(100)
                        if (isActive) {
                            delegate?.onClientConnected(device.address)
                        }
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }

        // Small delay to ensure cleanup is complete
        Thread.sleep(100)

        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }

        // A new service: no subscription to an earlier one counts for it.
        centrals = unsubscribedCentrals.onServiceReset()
        gattServer = bluetoothManager.openGattServer(context, serverCallback)

        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val descriptor = BluetoothGattDescriptor(
            DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        gattServer?.addService(service)

        Log.i(TAG, "GATT server setup complete")
    }

    @Suppress("DEPRECATION")
    private fun startBleAdvertising() {
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }

        if (advertiseCallback != null) {
            Log.d(TAG, "Already advertising")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed to start, error code: $errorCode")
            }
        }

        advertiseCallback = callback

        try {
            bleAdvertiser.startAdvertising(settings, data, callback)
            Log.i(TAG, "BLE advertising initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}")
            advertiseCallback = null
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBleAdvertising() {
        advertiseCallback?.let { callback ->
            try {
                bleAdvertiser?.stopAdvertising(callback)
                Log.i(TAG, "Advertising stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertising: ${e.message}")
            }
            advertiseCallback = null
        }
    }

    companion object {
        private const val TAG = "AndroidGattServerService"

        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD

        private const val CHUNK_SIZE = 500
        private const val CHUNK_DELAY_MS = 25L

        private const val UNSUBSCRIBED_CHECK_MS = 5_000L
        private const val CLAIM_BEFORE_CANCEL_MS = 300L

        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
