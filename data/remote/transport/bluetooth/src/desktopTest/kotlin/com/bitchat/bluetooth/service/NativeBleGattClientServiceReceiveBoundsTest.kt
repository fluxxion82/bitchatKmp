package com.bitchat.bluetooth.service

import kotlin.test.Test
import kotlin.test.assertTrue

class NativeBleGattClientServiceReceiveBoundsTest {

    @Test
    fun doesNotDeliverMoreBytesThanStartDeclared() {
        val received = mutableListOf<ByteArray>()
        val service = NativeBleGattClientService(NoopGattClientService, nativeAvailable = false)
        service.setDelegate(object : GattClientDelegate {
            override fun onCharacteristicRead(deviceAddress: String, data: ByteArray) {
                received += data
            }

            override fun onWriteSuccess(deviceAddress: String) = Unit

            override fun onWriteFailure(deviceAddress: String, error: String) = Unit
        })

        val receive = service.javaClass.getDeclaredMethod(
            "handleIncomingData",
            String::class.java,
            ByteArray::class.java,
        ).apply { isAccessible = true }

        receive.invoke(service, "peer", start(declaredLength = 10, payload = byteArrayOf(1)))
        repeat(20) { receive.invoke(service, "peer", byteArrayOf(0xFD.toByte(), 2, 3, 4)) }
        receive.invoke(service, "peer", byteArrayOf(0xFE.toByte(), 5))

        assertTrue(received.isEmpty(), "a frame larger than its START declaration must not reach the delegate")
    }

    private fun start(declaredLength: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0xFC.toByte(),
            ((declaredLength ushr 24) and 0xFF).toByte(),
            ((declaredLength ushr 16) and 0xFF).toByte(),
            ((declaredLength ushr 8) and 0xFF).toByte(),
            (declaredLength and 0xFF).toByte(),
        ) + payload

    private object NoopGattClientService : GattClientService {
        override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean = false
        override suspend fun disconnect(deviceAddress: String) = Unit
        override suspend fun disconnectAll() = Unit
        override fun setDelegate(delegate: GattClientDelegate) = Unit
    }
}
