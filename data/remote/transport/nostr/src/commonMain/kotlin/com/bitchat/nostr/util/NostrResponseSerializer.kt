package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object NostrResponseSerializer : KSerializer<NostrResponse> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("NostrResponse")

    override fun deserialize(decoder: Decoder): NostrResponse {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("NostrResponseSerializer works with JSON only")
        val el = input.decodeJsonElement()

        if (el !is JsonArray || el.isEmpty()) {
            // Preserve raw for debugging just like your Unknown branch
            return NostrResponse.Unknown(el.toString())
        }

        val type = el[0].jsonPrimitive.contentOrNull
            ?: return NostrResponse.Unknown(el.toString())

        return try {
            when (type) {
                "EVENT" -> {
                    // ["EVENT", <subscriptionId>, <eventObject>]
                    if (el.size < 3) return NostrResponse.Unknown(el.toString())
                    val subscriptionId = el[1].jsonPrimitive.content
                    val eventObj = el[2].jsonObject
                    val event = input.json.decodeFromJsonElement<NostrEvent>(eventObj)
                    NostrResponse.Event(subscriptionId, event)
                }

                "EOSE" -> {
                    // ["EOSE", <subscriptionId>]
                    if (el.size < 2) return NostrResponse.Unknown(el.toString())
                    val subscriptionId = el[1].jsonPrimitive.content
                    NostrResponse.EndOfStoredEvents(subscriptionId)
                }

                "OK" -> {
                    // ["OK", <eventId>, <bool>, <optional message>]
                    if (el.size < 3) return NostrResponse.Unknown(el.toString())
                    val eventId = el[1].jsonPrimitive.content
                    val accepted = el[2].jsonPrimitive.booleanOrNull
                        ?: return NostrResponse.Unknown(el.toString())
                    val message = el.getOrNull(3)?.jsonPrimitive?.contentOrNull
                    NostrResponse.Ok(eventId, accepted, message)
                }

                "NOTICE" -> {
                    // ["NOTICE", <message>]
                    if (el.size < 2) return NostrResponse.Unknown(el.toString())
                    val message = el[1].jsonPrimitive.content
                    NostrResponse.Notice(message)
                }

                else -> NostrResponse.Unknown(el.toString())
            }
        } catch (e: Exception) {
            NostrResponse.Unknown(el.toString())
        }
    }

    // Optional: serialize back to the array form the relays expect
    override fun serialize(encoder: Encoder, value: NostrResponse) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("NostrResponseSerializer works with JSON only")
        val json = output.json

        val array: JsonArray = when (value) {
            is NostrResponse.Event -> buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(JsonPrimitive(value.subscriptionId))
                add(json.encodeToJsonElement(value.event))
            }

            is NostrResponse.EndOfStoredEvents -> buildJsonArray {
                add(JsonPrimitive("EOSE"))
                add(JsonPrimitive(value.subscriptionId))
            }

            is NostrResponse.Ok -> buildJsonArray {
                add(JsonPrimitive("OK"))
                add(JsonPrimitive(value.eventId))
                add(JsonPrimitive(value.accepted))
                value.message?.let { add(JsonPrimitive(it)) }
            }

            is NostrResponse.Notice -> buildJsonArray {
                add(JsonPrimitive("NOTICE"))
                add(JsonPrimitive(value.message))
            }

            is NostrResponse.Unknown -> buildJsonArray {
                // keep Unknown as raw string; you could also try to parse and pass-through
                add(JsonPrimitive("UNKNOWN"))
                add(JsonPrimitive(value.raw))
            }
        }

        output.encodeJsonElement(array)
    }
}
