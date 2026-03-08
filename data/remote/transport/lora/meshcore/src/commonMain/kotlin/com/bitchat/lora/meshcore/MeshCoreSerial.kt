package com.bitchat.lora.meshcore

import kotlinx.coroutines.flow.Flow

/**
 * MeshCore serial/TCP connection interface.
 *
 * On embedded Linux (Orange Pi), connects to meshcore-pi daemon via TCP on port 5000.
 * On other platforms, provides stub implementations.
 *
 * MeshCore companion protocol framing:
 * - Outbound (app -> daemon): '<' + 2-byte LE length + payload
 * - Inbound (daemon -> app): '>' + 2-byte LE length + payload
 */
expect class MeshCoreSerial() {

    /**
     * Flow of incoming payload bytes (after frame parsing).
     */
    val incoming: Flow<ByteArray>

    /**
     * Whether connected to meshcore-pi daemon.
     */
    val isConnected: Boolean

    /**
     * Callback invoked when connection is lost unexpectedly.
     */
    var onDisconnect: (() -> Unit)?

    /**
     * Open connection to meshcore-pi daemon.
     *
     * @return true if connection established
     */
    fun open(): Boolean

    /**
     * Close the connection.
     */
    fun close()

    /**
     * Send a command payload to meshcore-pi.
     *
     * The payload will be framed with '<' + 2-byte LE length.
     *
     * @param data Command payload bytes
     * @return true if sent successfully
     */
    fun send(data: ByteArray): Boolean
}

/**
 * MeshCore companion protocol constants.
 *
 * Based on official MeshCore companion radio protocol specification:
 * https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol
 */
object MeshCoreConstants {
    /** Default TCP port for meshcore daemon */
    const val DEFAULT_PORT = 5000

    /** Default host for meshcore daemon */
    const val DEFAULT_HOST = "127.0.0.1"

    /** Frame start byte for inbound messages (from daemon): '>' (0x3E) */
    const val FRAME_START_INBOUND: Byte = '>'.code.toByte()

    /** Frame start byte for outbound messages (to daemon): '<' (0x3C) */
    const val FRAME_START_OUTBOUND: Byte = '<'.code.toByte()

    /** Maximum frame size */
    const val MAX_FRAME_SIZE = 255

    /** Current app protocol version */
    const val APP_TARGET_VER: Byte = 0x03

    // --- Command codes (app -> device) ---
    const val CMD_APP_START: Byte = 0x01
    const val CMD_SEND_TXT_MSG: Byte = 0x02
    const val CMD_SEND_CHANNEL_TXT_MSG: Byte = 0x03
    const val CMD_GET_CONTACTS: Byte = 0x04
    const val CMD_GET_DEVICE_TIME: Byte = 0x05
    const val CMD_SET_DEVICE_TIME: Byte = 0x06
    const val CMD_SEND_SELF_ADVERT: Byte = 0x07
    const val CMD_SET_ADVERT_NAME: Byte = 0x08
    const val CMD_ADD_UPDATE_CONTACT: Byte = 0x09
    const val CMD_SYNC_NEXT_MESSAGE: Byte = 0x0A
    const val CMD_SET_RADIO_PARAMS: Byte = 0x0B
    const val CMD_SET_RADIO_TX_POWER: Byte = 0x0C
    const val CMD_RESET_PATH: Byte = 0x0D
    const val CMD_SET_ADVERT_LATLON: Byte = 0x0E
    const val CMD_REMOVE_CONTACT: Byte = 0x0F
    const val CMD_SHARE_CONTACT: Byte = 0x10
    const val CMD_EXPORT_CONTACT: Byte = 0x11
    const val CMD_IMPORT_CONTACT: Byte = 0x12
    const val CMD_REBOOT: Byte = 0x13
    const val CMD_GET_BATT_AND_STORAGE: Byte = 0x14
    const val CMD_SET_TUNING_PARAMS: Byte = 0x15
    const val CMD_DEVICE_QUERY: Byte = 0x16
    const val CMD_GET_CHANNEL: Byte = 0x1F
    const val CMD_SET_CHANNEL: Byte = 0x20

    // --- Response codes (device -> app, synchronous) ---
    const val RESP_CODE_OK: Byte = 0x00
    const val RESP_CODE_ERR: Byte = 0x01
    const val RESP_CODE_CONTACTS_START: Byte = 0x02
    const val RESP_CODE_CONTACT: Byte = 0x03
    const val RESP_CODE_END_OF_CONTACTS: Byte = 0x04
    const val RESP_CODE_SELF_INFO: Byte = 0x05
    const val RESP_CODE_SENT: Byte = 0x06
    const val RESP_CODE_CONTACT_MSG: Byte = 0x07
    const val RESP_CODE_CHANNEL_MSG: Byte = 0x08
    const val RESP_CODE_CURR_TIME: Byte = 0x09
    const val RESP_CODE_NO_MORE_MESSAGES: Byte = 0x0A
    const val RESP_CODE_EXPORT_CONTACT: Byte = 0x0B
    const val RESP_CODE_BATT_AND_STORAGE: Byte = 0x0C
    const val RESP_CODE_DEVICE_INFO: Byte = 0x0D
    const val RESP_CODE_CONTACT_MSG_V3: Byte = 0x10
    const val RESP_CODE_CHANNEL_MSG_V3: Byte = 0x11
    const val RESP_CODE_CHANNEL_INFO: Byte = 0x12

    // --- Push notification codes (device -> app, asynchronous) ---
    const val PUSH_CODE_ADVERT: Byte = 0x80.toByte()
    const val PUSH_CODE_PATH_UPDATED: Byte = 0x81.toByte()
    const val PUSH_CODE_SEND_CONFIRMED: Byte = 0x82.toByte()
    const val PUSH_CODE_MSG_WAITING: Byte = 0x83.toByte()
    const val PUSH_CODE_RAW_DATA: Byte = 0x84.toByte()
    const val PUSH_CODE_LOG_DATA: Byte = 0x88.toByte()
    const val PUSH_CODE_NEW_ADVERT: Byte = 0x8A.toByte()

    // --- Error codes (within RESP_CODE_ERR) ---
    const val ERR_UNSUPPORTED_CMD: Byte = 0x01
    const val ERR_NOT_FOUND: Byte = 0x02
    const val ERR_TABLE_FULL: Byte = 0x03
    const val ERR_BAD_STATE: Byte = 0x04

    // --- Contact/advertisement types ---
    const val ADV_TYPE_NONE = 0
    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3

    // --- Text types ---
    const val TXT_TYPE_PLAIN: Byte = 0x00
    const val TXT_TYPE_CLI_DATA: Byte = 0x01
    const val TXT_TYPE_SIGNED: Byte = 0x02

    // --- Contact record size (payload after response code byte) ---
    const val CONTACT_RECORD_SIZE = 147

    /** Public key prefix size used in messages */
    const val PUBKEY_PREFIX_SIZE = 6
}
