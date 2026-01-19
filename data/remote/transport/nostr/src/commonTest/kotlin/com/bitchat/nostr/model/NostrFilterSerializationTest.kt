package com.bitchat.nostr.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrFilterSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun geohashEphemeralSerializesGTagFilter() {
        val filter = NostrFilter.geohashEphemeral("9q8yvgb")
        val element = json.encodeToJsonElement(filter)
        val obj = element.jsonObject

        assertTrue(obj.containsKey("#g"))
        val gValues = obj["#g"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("9q8yvgb"), gValues)
        assertFalse(obj.containsKey("tagFilters"))
    }
}
