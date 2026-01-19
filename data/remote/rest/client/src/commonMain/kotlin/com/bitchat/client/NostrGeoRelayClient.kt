package com.bitchat.client

import com.bitchat.domain.base.model.Outcome

class NostrGeoRelayClient(
    private val baseApiClient: BaseApiClient,
) {
    // "https://raw.githubusercontent.com/permissionlesstech/georelays/refs/heads/main/nostr_relays.csv"
    suspend fun downloadToFile(): Outcome<ByteArray> {
        return baseApiClient.get("refs/heads/main/nostr_relays.csv")
    }

}
