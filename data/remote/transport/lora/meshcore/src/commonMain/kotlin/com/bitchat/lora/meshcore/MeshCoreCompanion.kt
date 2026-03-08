package com.bitchat.lora.meshcore

/**
 * MeshCore companion protocol message types, parser, and command builders.
 *
 * Based on official MeshCore companion radio protocol specification:
 * https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol
 */

/**
 * Parsed response from MeshCore companion device.
 */
sealed class MeshCoreResponse {

    /**
     * Device info response (RESP_CODE_DEVICE_INFO = 0x0D).
     * Response to CMD_DEVICE_QUERY.
     */
    data class DeviceInfo(
        val firmwareVer: Int,
        val maxContacts: Int,
        val maxChannels: Int,
        val blePin: Long,
        val buildDate: String,
        val model: String,
        val semanticVersion: String
    ) : MeshCoreResponse()

    /**
     * Self info response (RESP_CODE_SELF_INFO = 0x05).
     * Response to CMD_APP_START.
     */
    data class SelfInfo(
        val advType: Int,
        val txPower: Int,
        val maxTxPower: Int,
        val publicKey: ByteArray,
        val lat: Double,
        val lon: Double,
        val radioFreq: Long,
        val radioBw: Long,
        val radioSf: Int,
        val radioCr: Int,
        val name: String
    ) : MeshCoreResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SelfInfo) return false
            return publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /**
     * Contact record (RESP_CODE_CONTACT = 0x03).
     * 147-byte payload: pubkey(32) + type(1) + flags(1) + out_path_len(1) +
     * out_path(64) + adv_name(32) + last_advert(4) + lat(4) + lon(4) + lastmod(4)
     */
    data class Contact(
        val publicKey: ByteArray,
        val type: ContactType,
        val flags: Int,
        val outPathLen: Int,
        val outPath: ByteArray,
        val name: String,
        val lastAdvert: Long,
        val lat: Double,
        val lon: Double,
        val lastmod: Long
    ) : MeshCoreResponse() {
        /** First 6 bytes of public key, used as identifier in messages */
        val pubKeyPrefix: ByteArray get() = publicKey.copyOfRange(0, 6)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Contact) return false
            return publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /**
     * Direct text message (v2: RESP_CODE_CONTACT_MSG = 0x07).
     */
    data class ContactMessage(
        val pubKeyPrefix: ByteArray,
        val pathLen: Int,
        val txtType: Int,
        val senderTimestamp: Long,
        val text: String,
        val snr: Float? = null
    ) : MeshCoreResponse()

    /**
     * Channel text message (v2: RESP_CODE_CHANNEL_MSG = 0x08).
     */
    data class ChannelMessage(
        val channelIdx: Int,
        val pathLen: Int,
        val txtType: Int,
        val senderTimestamp: Long,
        val text: String,
        val snr: Float? = null
    ) : MeshCoreResponse()

    /**
     * Contacts list start marker (RESP_CODE_CONTACTS_START = 0x02).
     */
    data class ContactsStart(val count: Int) : MeshCoreResponse()

    /**
     * End of contacts list (RESP_CODE_END_OF_CONTACTS = 0x04).
     */
    data class EndOfContacts(val mostRecentLastmod: Long) : MeshCoreResponse()

    /**
     * Message sent confirmation (RESP_CODE_SENT = 0x06).
     */
    data class Sent(
        val msgType: Int,
        val expectedAck: Long,
        val timeoutMs: Long
    ) : MeshCoreResponse()

    /**
     * Current device time (RESP_CODE_CURR_TIME = 0x09).
     */
    data class DeviceTime(val unixTime: Long) : MeshCoreResponse()

    /**
     * No more messages in offline queue (RESP_CODE_NO_MORE_MESSAGES = 0x0A).
     */
    data object NoMoreMessages : MeshCoreResponse()

    /** Generic OK (RESP_CODE_OK = 0x00). */
    data object Ok : MeshCoreResponse()

    /** Error (RESP_CODE_ERR = 0x01). */
    data class Error(val errorCode: Int) : MeshCoreResponse()

    /** Battery and storage info (RESP_CODE_BATT_AND_STORAGE = 0x0C). */
    data class BatteryAndStorage(
        val batteryMv: Int,
        val usedKb: Long?,
        val totalKb: Long?
    ) : MeshCoreResponse()

    /** Channel info (RESP_CODE_CHANNEL_INFO = 0x12). */
    data class ChannelInfo(
        val channelIdx: Int,
        val name: String,
        val secret: ByteArray
    ) : MeshCoreResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChannelInfo) return false
            return channelIdx == other.channelIdx && name == other.name && secret.contentEquals(other.secret)
        }
        override fun hashCode(): Int = 31 * channelIdx + name.hashCode() + secret.contentHashCode()
    }

    // --- Push notifications ---

    /** Known contact advertisement received (PUSH_CODE_ADVERT = 0x80). */
    data class PushAdvert(val publicKey: ByteArray) : MeshCoreResponse()

    /** Routing path updated (PUSH_CODE_PATH_UPDATED = 0x81). */
    data class PushPathUpdated(val publicKey: ByteArray) : MeshCoreResponse()

    /** Send confirmed with ACK (PUSH_CODE_SEND_CONFIRMED = 0x82). */
    data class PushSendConfirmed(val ackCode: Long, val roundTripMs: Long) : MeshCoreResponse()

    /** Message waiting, trigger CMD_SYNC_NEXT_MESSAGE (PUSH_CODE_MSG_WAITING = 0x83). */
    data object PushMsgWaiting : MeshCoreResponse()

    /** New advert from unknown node (PUSH_CODE_NEW_ADVERT = 0x8A). */
    data class PushNewAdvert(val contact: Contact) : MeshCoreResponse()

    /** Unknown/unparsed response. */
    data class Unknown(val code: Byte, val data: ByteArray) : MeshCoreResponse()
}

/**
 * Contact types in MeshCore.
 */
enum class ContactType(val value: Int) {
    NONE(0),
    CHAT(1),
    REPEATER(2),
    ROOM(3);

    companion object {
        fun fromValue(value: Int): ContactType =
            entries.find { it.value == value } ?: CHAT
    }
}

/**
 * MeshCore companion protocol command builder.
 */
object MeshCoreCommands {

    /**
     * CMD_DEVICE_QUERY (0x16) - Query device capabilities.
     * Must be sent first after connection.
     */
    fun deviceQuery(appTargetVer: Byte = MeshCoreConstants.APP_TARGET_VER): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_DEVICE_QUERY, appTargetVer)

    /**
     * CMD_APP_START (0x01) - Register app with device.
     * Sent after receiving DEVICE_INFO response.
     *
     * @param appVer App protocol version
     * @param appName App name string
     */
    fun appStart(
        appVer: Byte = MeshCoreConstants.APP_TARGET_VER,
        appName: String = "BitChat"
    ): ByteArray {
        val nameBytes = appName.encodeToByteArray()
        // Frame layout: cmd(1) + reserved(7) + app_name(variable)
        // The daemon requires len >= 8 (cmd + 7 reserved bytes).
        // reserved[0] = appVer, reserved[1..6] = 0
        val reserved = ByteArray(7)
        reserved[0] = appVer
        return byteArrayOf(MeshCoreConstants.CMD_APP_START) + reserved + nameBytes
    }

    /**
     * CMD_GET_CONTACTS (0x04) - Request contact list.
     *
     * @param since Optional timestamp to get only contacts modified since this time.
     *              Pass 0 or omit to get all contacts.
     */
    fun getContacts(since: Long = 0): ByteArray {
        return if (since > 0) {
            byteArrayOf(MeshCoreConstants.CMD_GET_CONTACTS) + since.toUInt32LE()
        } else {
            byteArrayOf(MeshCoreConstants.CMD_GET_CONTACTS)
        }
    }

    /**
     * CMD_SEND_TXT_MSG (0x02) - Send direct message to a contact.
     *
     * @param pubKeyPrefix First 6 bytes of recipient's public key
     * @param text Message text
     * @param txtType Text type (0=PLAIN, 1=CLI_DATA, 2=SIGNED)
     * @param attempt Attempt number (0 for first send)
     * @param timestamp Sender timestamp (epoch seconds)
     */
    fun sendTextMessage(
        pubKeyPrefix: ByteArray,
        text: String,
        txtType: Byte = MeshCoreConstants.TXT_TYPE_PLAIN,
        attempt: Byte = 0,
        timestamp: Long = 0
    ): ByteArray {
        require(pubKeyPrefix.size >= 6) { "Public key prefix must be at least 6 bytes" }
        val textBytes = text.encodeToByteArray()
        return byteArrayOf(MeshCoreConstants.CMD_SEND_TXT_MSG, txtType, attempt) +
                timestamp.toUInt32LE() +
                pubKeyPrefix.copyOfRange(0, 6) +
                textBytes
    }

    /**
     * CMD_SEND_CHANNEL_TXT_MSG (0x03) - Send channel/broadcast message.
     *
     * @param channelIdx Channel index (0 = public broadcast)
     * @param text Message text
     * @param txtType Text type
     * @param timestamp Sender timestamp (epoch seconds)
     */
    fun sendChannelMessage(
        channelIdx: Int = 0,
        text: String,
        txtType: Byte = MeshCoreConstants.TXT_TYPE_PLAIN,
        timestamp: Long = 0
    ): ByteArray {
        val textBytes = text.encodeToByteArray()
        return byteArrayOf(
            MeshCoreConstants.CMD_SEND_CHANNEL_TXT_MSG,
            txtType,
            channelIdx.toByte()
        ) + timestamp.toUInt32LE() + textBytes
    }

    /**
     * CMD_SEND_SELF_ADVERT (0x07) - Broadcast self-advertisement.
     *
     * @param flood If true, sends as flood advert
     */
    fun sendSelfAdvert(flood: Boolean = false): ByteArray {
        return if (flood) {
            byteArrayOf(MeshCoreConstants.CMD_SEND_SELF_ADVERT, 0x01)
        } else {
            byteArrayOf(MeshCoreConstants.CMD_SEND_SELF_ADVERT)
        }
    }

    /**
     * CMD_SYNC_NEXT_MESSAGE (0x0A) - Get next message from offline queue.
     */
    fun syncNextMessage(): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_SYNC_NEXT_MESSAGE)

    /**
     * CMD_GET_DEVICE_TIME (0x05) - Get device clock.
     */
    fun getDeviceTime(): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_GET_DEVICE_TIME)

    /**
     * CMD_SET_DEVICE_TIME (0x06) - Set device clock.
     */
    fun setDeviceTime(epochSecs: Long): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_SET_DEVICE_TIME) + epochSecs.toInt32LE()

    /**
     * CMD_SET_RADIO_PARAMS (0x0B) - Set radio frequency, bandwidth, SF, CR.
     *
     * Wire format: cmd(1) + freq(4 bytes LE, kHz) + bw(4 bytes LE, Hz) + sf(1) + cr(1)
     *
     * @param freqKhz Frequency in kHz (e.g., 910525 for 910.525 MHz)
     * @param bwHz Bandwidth in Hz (e.g., 250000 for 250 kHz)
     * @param sf Spreading factor (5-12)
     * @param cr Coding rate (5-8)
     */
    fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): ByteArray {
        return byteArrayOf(MeshCoreConstants.CMD_SET_RADIO_PARAMS) +
                freqKhz.toUInt32LE() +
                bwHz.toUInt32LE() +
                byteArrayOf(sf.toByte(), cr.toByte())
    }

    /**
     * CMD_SET_ADVERT_NAME (0x08) - Set broadcast name.
     */
    fun setAdvertName(name: String): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_SET_ADVERT_NAME) + name.encodeToByteArray()

    /**
     * CMD_RESET_PATH (0x0D) - Reset routing path for a contact.
     */
    fun resetPath(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "Public key must be 32 bytes" }
        return byteArrayOf(MeshCoreConstants.CMD_RESET_PATH) + publicKey
    }

    /**
     * CMD_GET_BATT_AND_STORAGE (0x14) - Query battery and storage.
     */
    fun getBattAndStorage(): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_GET_BATT_AND_STORAGE)

    /**
     * CMD_GET_CHANNEL (0x1F) - Get channel info.
     */
    fun getChannel(channelIdx: Int): ByteArray =
        byteArrayOf(MeshCoreConstants.CMD_GET_CHANNEL, channelIdx.toByte())

    /**
     * CMD_SET_CHANNEL (0x20) - Configure a channel with name and secret key.
     *
     * Wire format: [CMD_SET_CHANNEL, channelIdx, name(32 bytes), secret(16 bytes)]
     *
     * @param channelIdx Channel index (0 = primary)
     * @param name Channel name (max 31 chars, null-terminated in 32-byte field)
     * @param secret Channel secret key (exactly 16 bytes / 128-bit)
     */
    fun setChannel(channelIdx: Int, name: String, secret: ByteArray): ByteArray {
        require(secret.size == 16) { "Channel secret must be exactly 16 bytes" }
        val nameField = ByteArray(32) // zero-filled
        val nameBytes = name.encodeToByteArray()
        nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, 31))
        return byteArrayOf(MeshCoreConstants.CMD_SET_CHANNEL, channelIdx.toByte()) +
                nameField + secret
    }
}

/**
 * MeshCore companion protocol response parser.
 */
object MeshCoreParser {

    /**
     * Parse a response payload from MeshCore device.
     *
     * @param data Raw payload bytes (without frame header)
     * @return Parsed response
     */
    fun parseResponse(data: ByteArray): MeshCoreResponse {
        if (data.isEmpty()) return MeshCoreResponse.Unknown(0, data)

        val code = data[0]
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)

        return when (code) {
            MeshCoreConstants.RESP_CODE_OK -> MeshCoreResponse.Ok

            MeshCoreConstants.RESP_CODE_ERR -> {
                val errorCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                MeshCoreResponse.Error(errorCode)
            }

            MeshCoreConstants.RESP_CODE_CONTACTS_START -> {
                val count = if (payload.size >= 4) payload.readUInt32LE(0) else 0L
                MeshCoreResponse.ContactsStart(count.toInt())
            }

            MeshCoreConstants.RESP_CODE_CONTACT -> parseContact(payload)

            MeshCoreConstants.RESP_CODE_END_OF_CONTACTS -> {
                val lastmod = if (payload.size >= 4) payload.readUInt32LE(0) else 0L
                MeshCoreResponse.EndOfContacts(lastmod)
            }

            MeshCoreConstants.RESP_CODE_SELF_INFO -> parseSelfInfo(payload)

            MeshCoreConstants.RESP_CODE_SENT -> parseSent(payload)

            MeshCoreConstants.RESP_CODE_CONTACT_MSG -> parseContactMessage(payload)
            MeshCoreConstants.RESP_CODE_CHANNEL_MSG -> parseChannelMessage(payload)

            MeshCoreConstants.RESP_CODE_CURR_TIME -> {
                if (payload.size >= 4) {
                    MeshCoreResponse.DeviceTime(payload.readUInt32LE(0))
                } else {
                    MeshCoreResponse.Unknown(code, data)
                }
            }

            MeshCoreConstants.RESP_CODE_NO_MORE_MESSAGES -> MeshCoreResponse.NoMoreMessages

            MeshCoreConstants.RESP_CODE_BATT_AND_STORAGE -> parseBattAndStorage(payload)

            MeshCoreConstants.RESP_CODE_DEVICE_INFO -> parseDeviceInfo(payload)

            MeshCoreConstants.RESP_CODE_CONTACT_MSG_V3 -> parseContactMessageV3(payload)
            MeshCoreConstants.RESP_CODE_CHANNEL_MSG_V3 -> parseChannelMessageV3(payload)

            MeshCoreConstants.RESP_CODE_CHANNEL_INFO -> parseChannelInfo(payload)

            // Push notifications
            MeshCoreConstants.PUSH_CODE_ADVERT -> {
                if (payload.size >= 32) {
                    MeshCoreResponse.PushAdvert(payload.copyOfRange(0, 32))
                } else {
                    MeshCoreResponse.Unknown(code, data)
                }
            }

            MeshCoreConstants.PUSH_CODE_PATH_UPDATED -> {
                if (payload.size >= 32) {
                    MeshCoreResponse.PushPathUpdated(payload.copyOfRange(0, 32))
                } else {
                    MeshCoreResponse.Unknown(code, data)
                }
            }

            MeshCoreConstants.PUSH_CODE_SEND_CONFIRMED -> {
                if (payload.size >= 8) {
                    MeshCoreResponse.PushSendConfirmed(
                        ackCode = payload.readUInt32LE(0),
                        roundTripMs = payload.readUInt32LE(4)
                    )
                } else {
                    MeshCoreResponse.Unknown(code, data)
                }
            }

            MeshCoreConstants.PUSH_CODE_MSG_WAITING -> MeshCoreResponse.PushMsgWaiting

            MeshCoreConstants.PUSH_CODE_NEW_ADVERT -> {
                // Same format as RESP_CODE_CONTACT
                if (payload.size >= MeshCoreConstants.CONTACT_RECORD_SIZE) {
                    val contact = parseContact(payload)
                    if (contact is MeshCoreResponse.Contact) {
                        MeshCoreResponse.PushNewAdvert(contact)
                    } else {
                        MeshCoreResponse.Unknown(code, data)
                    }
                } else {
                    MeshCoreResponse.Unknown(code, data)
                }
            }

            else -> MeshCoreResponse.Unknown(code, data)
        }
    }

    /**
     * Parse contact record (147 bytes).
     * Layout: pubkey(32) + type(1) + flags(1) + out_path_len(1) + out_path(64) +
     * adv_name(32) + last_advert(4) + lat(4) + lon(4) + lastmod(4)
     */
    private fun parseContact(payload: ByteArray): MeshCoreResponse {
        if (payload.size < MeshCoreConstants.CONTACT_RECORD_SIZE) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CONTACT, payload)
        }

        val publicKey = payload.copyOfRange(0, 32)
        val type = ContactType.fromValue(payload[32].toInt() and 0xFF)
        val flags = payload[33].toInt() and 0xFF
        val outPathLen = payload[34].toInt() // signed: -1 (0xFF) means no path
        val outPath = payload.copyOfRange(35, 99)
        val nameBytes = payload.copyOfRange(99, 131)
        val name = nameBytes.decodeToString().trimEnd('\u0000')
        val lastAdvert = payload.readUInt32LE(131)
        val lat = payload.readInt32LE(135) / 1_000_000.0
        val lon = payload.readInt32LE(139) / 1_000_000.0
        val lastmod = payload.readUInt32LE(143)

        return MeshCoreResponse.Contact(
            publicKey = publicKey,
            type = type,
            flags = flags,
            outPathLen = outPathLen,
            outPath = outPath,
            name = name,
            lastAdvert = lastAdvert,
            lat = lat,
            lon = lon,
            lastmod = lastmod
        )
    }

    /**
     * Parse self info response.
     * Layout: adv_type(1) + tx_power(1) + max_tx_power(1) + pubkey(32) +
     * lat(4) + lon(4) + multi_acks(1) + loc_policy(1) + telemetry(1) +
     * manual_add(1) + radio_freq(4) + radio_bw(4) + radio_sf(1) + radio_cr(1) + name(var)
     */
    private fun parseSelfInfo(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 57) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_SELF_INFO, payload)
        }

        val advType = payload[0].toInt() and 0xFF
        val txPower = payload[1].toInt() and 0xFF
        val maxTxPower = payload[2].toInt() and 0xFF
        val publicKey = payload.copyOfRange(3, 35)
        val lat = payload.readInt32LE(35) / 1_000_000.0
        val lon = payload.readInt32LE(39) / 1_000_000.0
        // bytes 43-46: multi_acks, loc_policy, telemetry, manual_add
        val radioFreq = payload.readUInt32LE(47)
        val radioBw = payload.readUInt32LE(51)
        val radioSf = payload[55].toInt() and 0xFF
        val radioCr = payload[56].toInt() and 0xFF
        val name = if (payload.size > 57) {
            payload.copyOfRange(57, payload.size).decodeToString().trimEnd('\u0000')
        } else {
            "MeshCore"
        }

        return MeshCoreResponse.SelfInfo(
            advType = advType,
            txPower = txPower,
            maxTxPower = maxTxPower,
            publicKey = publicKey,
            lat = lat,
            lon = lon,
            radioFreq = radioFreq,
            radioBw = radioBw,
            radioSf = radioSf,
            radioCr = radioCr,
            name = name
        )
    }

    /**
     * Parse device info response.
     * Layout: firmware_ver(1) + max_contacts_div2(1) + max_channels(1) + ble_pin(4) +
     * build_date(12) + model(40) + semantic_version(var)
     */
    private fun parseDeviceInfo(payload: ByteArray): MeshCoreResponse {
        if (payload.isEmpty()) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_DEVICE_INFO, payload)
        }

        val firmwareVer = payload[0].toInt() and 0xFF
        val maxContacts = if (payload.size > 1) (payload[1].toInt() and 0xFF) * 2 else 0
        val maxChannels = if (payload.size > 2) payload[2].toInt() and 0xFF else 0
        val blePin = if (payload.size >= 7) payload.readUInt32LE(3) else 0L
        val buildDate = if (payload.size >= 19) {
            payload.copyOfRange(7, 19).decodeToString().trimEnd('\u0000')
        } else ""
        val model = if (payload.size >= 59) {
            payload.copyOfRange(19, 59).decodeToString().trimEnd('\u0000')
        } else ""
        val semanticVersion = if (payload.size > 59) {
            payload.copyOfRange(59, payload.size).decodeToString().trimEnd('\u0000')
        } else ""

        return MeshCoreResponse.DeviceInfo(
            firmwareVer = firmwareVer,
            maxContacts = maxContacts,
            maxChannels = maxChannels,
            blePin = blePin,
            buildDate = buildDate,
            model = model,
            semanticVersion = semanticVersion
        )
    }

    /**
     * Parse v2 direct message (RESP_CODE_CONTACT_MSG = 0x07).
     * Layout: pubkey_prefix(6) + path_len(1) + txt_type(1) + sender_timestamp(4) + text(var)
     */
    private fun parseContactMessage(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 12) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CONTACT_MSG, payload)
        }

        val pubKeyPrefix = payload.copyOfRange(0, 6)
        val pathLen = payload[6].toInt() and 0xFF
        val txtType = payload[7].toInt() and 0xFF
        val senderTimestamp = payload.readUInt32LE(8)
        val text = if (payload.size > 12) {
            payload.copyOfRange(12, payload.size).decodeToString()
        } else ""

        return MeshCoreResponse.ContactMessage(
            pubKeyPrefix = pubKeyPrefix,
            pathLen = pathLen,
            txtType = txtType,
            senderTimestamp = senderTimestamp,
            text = text
        )
    }

    /**
     * Parse v2 channel message (RESP_CODE_CHANNEL_MSG = 0x08).
     * Layout: channel_idx(1) + path_len(1) + txt_type(1) + sender_timestamp(4) + text(var)
     */
    private fun parseChannelMessage(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 7) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CHANNEL_MSG, payload)
        }

        val channelIdx = payload[0].toInt() and 0xFF
        val pathLen = payload[1].toInt() and 0xFF
        val txtType = payload[2].toInt() and 0xFF
        val senderTimestamp = payload.readUInt32LE(3)
        val text = if (payload.size > 7) {
            payload.copyOfRange(7, payload.size).decodeToString()
        } else ""

        return MeshCoreResponse.ChannelMessage(
            channelIdx = channelIdx,
            pathLen = pathLen,
            txtType = txtType,
            senderTimestamp = senderTimestamp,
            text = text
        )
    }

    /**
     * Parse v3 direct message (RESP_CODE_CONTACT_MSG_V3 = 0x10).
     * Layout: snr(1) + reserved(2) + pubkey_prefix(6) + path_len(1) + txt_type(1) +
     * sender_timestamp(4) + text(var)
     */
    private fun parseContactMessageV3(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 15) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CONTACT_MSG_V3, payload)
        }

        val snrRaw = payload[0].toInt() // signed byte
        val snr = snrRaw / 4.0f
        // bytes 1-2: reserved
        val pubKeyPrefix = payload.copyOfRange(3, 9)
        val pathLen = payload[9].toInt() and 0xFF
        val txtType = payload[10].toInt() and 0xFF
        val senderTimestamp = payload.readUInt32LE(11)
        val text = if (payload.size > 15) {
            payload.copyOfRange(15, payload.size).decodeToString()
        } else ""

        return MeshCoreResponse.ContactMessage(
            pubKeyPrefix = pubKeyPrefix,
            pathLen = pathLen,
            txtType = txtType,
            senderTimestamp = senderTimestamp,
            text = text,
            snr = snr
        )
    }

    /**
     * Parse v3 channel message (RESP_CODE_CHANNEL_MSG_V3 = 0x11).
     * Layout: snr(1) + reserved(2) + channel_idx(1) + path_len(1) + txt_type(1) +
     * sender_timestamp(4) + text(var)
     */
    private fun parseChannelMessageV3(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 10) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CHANNEL_MSG_V3, payload)
        }

        val snrRaw = payload[0].toInt() // signed byte
        val snr = snrRaw / 4.0f
        // bytes 1-2: reserved
        val channelIdx = payload[3].toInt() and 0xFF
        val pathLen = payload[4].toInt() and 0xFF
        val txtType = payload[5].toInt() and 0xFF
        val senderTimestamp = payload.readUInt32LE(6)
        val text = if (payload.size > 10) {
            payload.copyOfRange(10, payload.size).decodeToString()
        } else ""

        return MeshCoreResponse.ChannelMessage(
            channelIdx = channelIdx,
            pathLen = pathLen,
            txtType = txtType,
            senderTimestamp = senderTimestamp,
            text = text,
            snr = snr
        )
    }

    /**
     * Parse sent confirmation (RESP_CODE_SENT = 0x06).
     */
    private fun parseSent(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 9) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_SENT, payload)
        }
        return MeshCoreResponse.Sent(
            msgType = payload[0].toInt() and 0xFF,
            expectedAck = payload.readUInt32LE(1),
            timeoutMs = payload.readUInt32LE(5)
        )
    }

    /**
     * Parse battery and storage (RESP_CODE_BATT_AND_STORAGE = 0x0C).
     */
    private fun parseBattAndStorage(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 2) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_BATT_AND_STORAGE, payload)
        }
        val batteryMv = payload.readUInt16LE(0)
        val usedKb = if (payload.size >= 6) payload.readUInt32LE(2) else null
        val totalKb = if (payload.size >= 10) payload.readUInt32LE(6) else null
        return MeshCoreResponse.BatteryAndStorage(batteryMv, usedKb, totalKb)
    }

    /**
     * Parse channel info (RESP_CODE_CHANNEL_INFO = 0x12).
     * Layout: channel_idx(1) + name(32) + secret(16)
     */
    private fun parseChannelInfo(payload: ByteArray): MeshCoreResponse {
        if (payload.size < 49) {
            return MeshCoreResponse.Unknown(MeshCoreConstants.RESP_CODE_CHANNEL_INFO, payload)
        }
        val channelIdx = payload[0].toInt() and 0xFF
        val name = payload.copyOfRange(1, 33).decodeToString().trimEnd('\u0000')
        val secret = payload.copyOfRange(33, 49)
        return MeshCoreResponse.ChannelInfo(channelIdx, name, secret)
    }
}

// --- Byte array utility extensions ---

internal fun ByteArray.readUInt16LE(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF)) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun ByteArray.readInt32LE(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF)) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}

internal fun ByteArray.readUInt32LE(offset: Int): Long {
    return ((this[offset].toLong() and 0xFF)) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}

internal fun Long.toUInt32LE(): ByteArray {
    return byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )
}

internal fun Long.toInt32LE(): ByteArray = this.toUInt32LE()

internal fun ByteArray.toHexString(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
