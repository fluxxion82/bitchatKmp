package com.bitchat.client

import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url

actual fun torSocksProxy(host: String, port: Int): ProxyConfig =
    ProxyConfig(Url("socks5h://$host:$port"))
