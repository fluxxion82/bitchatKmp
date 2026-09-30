package com.bitchat.client

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig

actual fun torSocksProxy(host: String, port: Int): ProxyConfig = ProxyBuilder.socks(host, port)
