package com.bitchat.api.dto.util

class ByteWriter(private val capacity: Int = 512) {
    private val data = ByteArray(capacity)
    private var position = 0

    fun put(byte: Byte) {
        ensureCapacity(1)
        data[position++] = byte
    }

    fun put(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureCapacity(bytes.size)
        bytes.copyInto(data, position)
        position += bytes.size
    }

    fun putShort(value: Short) {
        ensureCapacity(2)
        data[position++] = ((value.toInt() shr 8) and 0xFF).toByte()
        data[position++] = (value.toInt() and 0xFF).toByte()
    }

    fun putInt(value: Int) {
        ensureCapacity(4)
        data[position++] = ((value shr 24) and 0xFF).toByte()
        data[position++] = ((value shr 16) and 0xFF).toByte()
        data[position++] = ((value shr 8) and 0xFF).toByte()
        data[position++] = (value and 0xFF).toByte()
    }

    fun putLong(value: Long) {
        ensureCapacity(8)
        data[position++] = ((value shr 56) and 0xFF).toByte()
        data[position++] = ((value shr 48) and 0xFF).toByte()
        data[position++] = ((value shr 40) and 0xFF).toByte()
        data[position++] = ((value shr 32) and 0xFF).toByte()
        data[position++] = ((value shr 24) and 0xFF).toByte()
        data[position++] = ((value shr 16) and 0xFF).toByte()
        data[position++] = ((value shr 8) and 0xFF).toByte()
        data[position++] = (value and 0xFF).toByte()
    }

    fun position(): Int = position

    fun toByteArray(): ByteArray = data.sliceArray(0 until position)

    private fun ensureCapacity(needed: Int) {
        if (position + needed > capacity) {
            throw IllegalStateException("Buffer overflow: needed $needed bytes, but only ${capacity - position} available")
        }
    }
}

class ByteReader(private val data: ByteArray) {
    private var position = 0

    fun get(): Byte {
        if (position >= data.size) throw IndexOutOfBoundsException("No more data to read")
        return data[position++]
    }

    fun get(bytes: ByteArray) {
        if (position + bytes.size > data.size) throw IndexOutOfBoundsException("Not enough data to read")
        data.copyInto(bytes, 0, position, position + bytes.size)
        position += bytes.size
    }

    fun getShort(): Short {
        if (position + 2 > data.size) throw IndexOutOfBoundsException("Not enough data for short")
        val b1 = (data[position++].toInt() and 0xFF) shl 8
        val b2 = (data[position++].toInt() and 0xFF)
        return (b1 or b2).toShort()
    }

    fun getInt(): Int {
        if (position + 4 > data.size) throw IndexOutOfBoundsException("Not enough data for int")
        val b1 = (data[position++].toInt() and 0xFF) shl 24
        val b2 = (data[position++].toInt() and 0xFF) shl 16
        val b3 = (data[position++].toInt() and 0xFF) shl 8
        val b4 = (data[position++].toInt() and 0xFF)
        return b1 or b2 or b3 or b4
    }

    fun getLong(): Long {
        if (position + 8 > data.size) throw IndexOutOfBoundsException("Not enough data for long")
        val b1 = (data[position++].toLong() and 0xFF) shl 56
        val b2 = (data[position++].toLong() and 0xFF) shl 48
        val b3 = (data[position++].toLong() and 0xFF) shl 40
        val b4 = (data[position++].toLong() and 0xFF) shl 32
        val b5 = (data[position++].toLong() and 0xFF) shl 24
        val b6 = (data[position++].toLong() and 0xFF) shl 16
        val b7 = (data[position++].toLong() and 0xFF) shl 8
        val b8 = (data[position++].toLong() and 0xFF)
        return b1 or b2 or b3 or b4 or b5 or b6 or b7 or b8
    }

    fun position(): Int = position

    fun remaining(): Int = data.size - position

    fun hasRemaining(): Boolean = position < data.size
}