package com.bitchat.lora

const val LORA_HEARTBEAT_NICKNAME_BYTES = 24

/** The name carried by a LoRa heartbeat, limited without splitting a Unicode character. */
fun loRaHeartbeatNickname(nickname: String): String {
    var end = 0
    var bytes = 0
    while (end < nickname.length) {
        var next = end + 1
        if (nickname[end] in '\uD800'..'\uDBFF' && next < nickname.length && nickname[next] in '\uDC00'..'\uDFFF') {
            next++
        }
        val count = nickname.substring(end, next).encodeToByteArray().size
        if (bytes + count > LORA_HEARTBEAT_NICKNAME_BYTES) break
        bytes += count
        end = next
    }
    return nickname.substring(0, end)
}

/**
 * Whether a name heard in a bitchat LoRa heartbeat is long enough to be only the start of a longer
 * one. A cut leaves less than one character (at most 4 bytes) of the 24 unused, so a cut name has
 * 21 to 24 bytes; a name of that length may just as well be somebody's whole name. Such a name
 * says who MAY be meant, never who is.
 */
fun mayBeCutLoRaNickname(heard: String): Boolean =
    heard.encodeToByteArray().size in (LORA_HEARTBEAT_NICKNAME_BYTES - 3)..LORA_HEARTBEAT_NICKNAME_BYTES
