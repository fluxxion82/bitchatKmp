package com.bitchat.domain.chat

import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationKeyTest {
    @Test fun channelNamesAreComparedWithoutTheirHashOrCase() {
        assertEquals("#test", normalizeChannelName("test"))
        assertEquals("#test", normalizeChannelName("#Test"))
        assertEquals("#test", normalizeChannelName("  #TEST  "))
        assertTrue(Channel.NamedChannel("test").isSameConversation(Channel.NamedChannel("#Test")))
    }

    @Test fun aConversationIsTheSameWhateverDescribesIt() {
        // A DM's display name and source geohash can be filled in later; it is still that DM.
        assertTrue(
            Channel.NostrDM("nostr_ab", "abcd", null, null)
                .isSameConversation(Channel.NostrDM("nostr_ab", "abcd", "9q8yy", "dora")),
        )
        assertTrue(Channel.MeshDM("b0b").isSameConversation(Channel.MeshDM("b0b", "bob")))
        assertFalse(Channel.MeshDM("b0b").isSameConversation(Channel.MeshDM("a11ce")))
        assertTrue(Channel.Meshtastic(7).isSameConversation(Channel.Meshtastic(7, "node")))
        assertFalse(Channel.Meshtastic(7).isSameConversation(Channel.Meshtastic(null)))
    }

    @Test fun differentKindsOfChatAreNeverTheSame() {
        val all = listOf(
            Channel.Mesh,
            Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            Channel.NamedChannel("#test"),
            Channel.MeshDM("b0b"),
            Channel.NostrDM("nostr_ab", "abcd", null, null),
            Channel.Meshtastic(null),
        )
        assertEquals(all.size, all.map { it.conversationKey() }.toSet().size)
    }
}
