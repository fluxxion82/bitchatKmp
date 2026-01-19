package com.bitchat.client.mapper

import com.bitchat.client.model.ClientType

fun ClientType.toBaseUrl(): String {
    return when (this) {
        ClientType.NOSTR -> "https://raw.githubusercontent.com/permissionlesstech/georelays"
    }
}

fun ClientType.addClientTypeParameters() {
    when (this) {
        ClientType.NOSTR -> {}
    }
}
