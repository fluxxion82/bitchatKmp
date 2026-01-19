package com.bitchat.client

import com.bitchat.tor.TorManager
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

actual fun getEngine(isDebug: Boolean, torManager: TorManager?): HttpClientEngineFactory<*> {
    println("getEngine: torManager: $torManager")
    return if (torManager != null) {
        OkHttp.config {
            config {
                proxySelector(object : ProxySelector() {
                    override fun select(uri: URI?): List<Proxy> {
                        val socksAddr = torManager.getSocksProxyAddress()
                        val uriString = uri?.toString() ?: "unknown"

                        return if (socksAddr != null) {
                            println("🔒 ProxySelector: Using Tor SOCKS proxy ${socksAddr.first}:${socksAddr.second} for $uriString")
                            listOf(
                                Proxy(
                                    Proxy.Type.SOCKS,
                                    InetSocketAddress(socksAddr.first, socksAddr.second)
                                )
                            )
                        } else {
                            println("⚠️  ProxySelector: Direct connection (Tor not ready) for $uriString")
                            listOf(Proxy.NO_PROXY)
                        }
                    }

                    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                        println("❌ ProxySelector: Connection failed to ${uri?.toString()} via $sa - ${ioe?.message}")
                        ioe?.printStackTrace()
                    }
                })
            }
        }
    } else {
        // No TorManager available (JS/WASM platforms) - use plain OkHttp
        OkHttp
    }
}

private class TrustAllCerts : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    companion object {
        fun createSSLSocketFactory(): SSLSocketFactory {
            return SSLContext.getInstance("SSL").apply {
                init(null, arrayOf(TrustAllCerts()), SecureRandom())
            }.socketFactory
        }
    }
}
