package com.bitchat.bluetooth.service

interface BluetoothConnectionService {
    suspend fun connectToDevice(deviceAddress: String)
    suspend fun confirmDevice()
    suspend fun isDeviceConnecting(deviceAddress: String): Boolean
    suspend fun disconnectDeviceByAddress(deviceAddress: String)
    suspend fun clearConnections()

    /**
     * Send [packetData] to every peer this node currently holds a link to.
     *
     * @return true when at least one live link received it. A peripheral-role notification is a
     *   D-Bus signal on a device-agnostic object path, so the send itself cannot tell a caller that
     *   nobody was listening; without this return value a broadcast into no links at all looked
     *   exactly like a delivered one, and a Noise handshake spent its whole retry budget on packets
     *   that could not reach anybody.
     */
    suspend fun broadcastPacket(packetData: ByteArray): Boolean

    fun hasRequiredPermissions(): Boolean

    /**
     * Teach this service which other addresses already stand for the peer behind an address, so it
     * does not open a second link to a device it is already talking to.
     *
     * Only the peer ID makes two addresses one device: Android rotates its resolvable private
     * address, so a phone that is already connected is offered again by the scanner under a MAC
     * nothing else can relate to the first. The mapping lives in the mesh service, which is the
     * only layer that sees peer IDs, and the lookup must not block -- it is called while a
     * connection decision is being made.
     *
     * The default is no knowledge, which is the behaviour of every platform that does not drive its
     * own connection policy.
     */
    fun setPeerAddressLookup(lookup: (String) -> Set<String>) {
        // Optional: only the BlueZ central role acts on this.
    }

    fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback)
    fun setConnectionReadyCallback(callback: ConnectionReadyCallback)
    fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback)
}

interface ConnectionReadyCallback {
    fun onConnectionReady(deviceAddress: String)
}

interface OnPacketReceivedCallback {
    fun onPacketReceived(data: ByteArray, deviceAddress: String)
}
