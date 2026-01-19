package com.bitchat.nostr.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = NostrFilter.Serializer::class)
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val since: Int? = null,
    val until: Int? = null,
    val limit: Int? = null,
    val tagFilters: Map<String, List<String>>? = null
) {
    fun matches(event: NostrEvent): Boolean {
        if (ids != null && !ids.contains(event.id)) {
            return false
        }

        if (authors != null && !authors.contains(event.pubkey)) {
            return false
        }

        if (kinds != null && !kinds.contains(event.kind)) {
            return false
        }

        if (since != null && event.createdAt < since) {
            return false
        }

        if (until != null && event.createdAt > until) {
            return false
        }

        if (tagFilters != null) {
            for ((tagName, requiredValues) in tagFilters) {
                val eventTags = event.tags.filter { it.isNotEmpty() && it[0] == tagName }
                val eventValues = eventTags.mapNotNull { tag ->
                    if (tag.size > 1) tag[1] else null
                }

                val hasMatch = requiredValues.any { requiredValue ->
                    eventValues.contains(requiredValue)
                }

                if (!hasMatch) {
                    return false
                }
            }
        }

        return true
    }

    fun getDebugDescription(): String {
        val parts = mutableListOf<String>()

        ids?.let { parts.add("ids=${it.size}") }
        authors?.let { parts.add("authors=${it.size}") }
        kinds?.let { parts.add("kinds=$it") }
        since?.let { parts.add("since=$it") }
        until?.let { parts.add("until=$it") }
        limit?.let { parts.add("limit=$it") }
        tagFilters?.let { filters ->
            filters.forEach { (tag, values) ->
                parts.add("#$tag=${values.size}")
            }
        }

        return "NostrFilter(${parts.joinToString(", ")})"
    }

    fun getGeohash(): String? {
        return tagFilters?.get("g")?.firstOrNull()
    }

    object Serializer : KSerializer<NostrFilter> {
        override val descriptor = buildClassSerialDescriptor("NostrFilter")

        override fun serialize(encoder: Encoder, value: NostrFilter) {
            val jsonEncoder = encoder as? JsonEncoder
                ?: error("NostrFilterSerializer only supports JSON encoding")

            val obj = buildJsonObject {
                value.ids?.let { put("ids", it.toJsonArray()) }
                value.authors?.let { put("authors", it.toJsonArray()) }
                value.kinds?.let { put("kinds", it.toJsonArrayInts()) }
                value.since?.let { put("since", JsonPrimitive(it)) }
                value.until?.let { put("until", JsonPrimitive(it)) }
                value.limit?.let { put("limit", JsonPrimitive(it)) }
                value.tagFilters?.forEach { (tag, values) ->
                    put("#$tag", values.toJsonArray())
                }
            }

            jsonEncoder.encodeJsonElement(obj)
        }

        override fun deserialize(decoder: Decoder): NostrFilter {
            val jsonDecoder = decoder as? JsonDecoder
                ?: error("NostrFilterSerializer only supports JSON decoding")
            val obj = jsonDecoder.decodeJsonElement().jsonObject

            val ids = obj["ids"]?.jsonArray?.toStringList()
            val authors = obj["authors"]?.jsonArray?.toStringList()
            val kinds = obj["kinds"]?.jsonArray?.toIntList()
            val since = obj["since"]?.jsonPrimitive?.content?.toIntOrNull()
            val until = obj["until"]?.jsonPrimitive?.content?.toIntOrNull()
            val limit = obj["limit"]?.jsonPrimitive?.content?.toIntOrNull()

            val tagFilters = mutableMapOf<String, List<String>>()
            obj.forEach { (key, value) ->
                if (key.startsWith("#")) {
                    tagFilters[key.removePrefix("#")] = value.jsonArray.toStringList()
                }
            }

            return NostrFilter(
                ids = ids,
                authors = authors,
                kinds = kinds,
                since = since,
                until = until,
                limit = limit,
                tagFilters = tagFilters.takeIf { it.isNotEmpty() }
            )
        }
    }

    companion object {
        fun giftWrapsFor(pubkey: String, since: Long? = null): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.GIFT_WRAP),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("p" to listOf(pubkey)),
                limit = 100
            )
        }

        fun geohashEphemeral(geohash: String, since: Long? = null, limit: Int = 200): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.EPHEMERAL_EVENT),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("g" to listOf(geohash)),
                limit = limit
            )
        }

        fun textNotesFrom(authors: List<String>, since: Long? = null, limit: Int = 50): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.TEXT_NOTE),
                authors = authors,
                since = since?.let { (it / 1000).toInt() },
                limit = limit
            )
        }

        fun geohashNotes(geohash: String, since: Long? = null, limit: Int = 200): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.TEXT_NOTE),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("g" to listOf(geohash)),
                limit = limit
            )
        }

        fun forEvents(ids: List<String>): NostrFilter {
            return NostrFilter(ids = ids)
        }
    }

    class Builder {
        private var ids: List<String>? = null
        private var authors: List<String>? = null
        private var kinds: List<Int>? = null
        private var since: Int? = null
        private var until: Int? = null
        private var limit: Int? = null
        private val tagFilters = mutableMapOf<String, List<String>>()

        fun ids(vararg ids: String) = apply { this.ids = ids.toList() }
        fun authors(vararg authors: String) = apply { this.authors = authors.toList() }
        fun kinds(vararg kinds: Int) = apply { this.kinds = kinds.toList() }
        fun since(timestamp: Long) = apply { this.since = (timestamp / 1000).toInt() }
        fun until(timestamp: Long) = apply { this.until = (timestamp / 1000).toInt() }
        fun limit(count: Int) = apply { this.limit = count }

        fun tagP(vararg pubkeys: String) = apply { tagFilters["p"] = pubkeys.toList() }
        fun tagE(vararg eventIds: String) = apply { tagFilters["e"] = eventIds.toList() }
        fun tagG(vararg geohashes: String) = apply { tagFilters["g"] = geohashes.toList() }
        fun tag(name: String, vararg values: String) = apply { tagFilters[name] = values.toList() }

        fun build(): NostrFilter {
            return NostrFilter(
                ids = ids,
                authors = authors,
                kinds = kinds,
                since = since,
                until = until,
                limit = limit,
                tagFilters = tagFilters.toMap()
            )
        }
    }
}

private fun List<String>.toJsonArray(): JsonArray {
    return JsonArray(map { JsonPrimitive(it) })
}

private fun List<Int>.toJsonArrayInts(): JsonArray {
    return JsonArray(map { JsonPrimitive(it) })
}

private fun JsonArray.toStringList(): List<String> {
    return mapNotNull { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()
    }
}

private fun JsonArray.toIntList(): List<Int> {
    return mapNotNull { element ->
        runCatching { element.jsonPrimitive.content.toInt() }.getOrNull()
    }
}
