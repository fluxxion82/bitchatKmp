package com.bitchat.client

import io.ktor.client.engine.ProxyConfig

expect fun torSocksProxy(host: String, port: Int): ProxyConfig
