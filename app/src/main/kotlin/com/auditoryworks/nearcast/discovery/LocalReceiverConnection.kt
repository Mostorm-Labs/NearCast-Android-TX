package com.auditoryworks.nearcast.discovery

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ReceiverTrustRevoked : IOException("NEARCAST_TRUST_REVOKED")

/** A single receiver's native DNS-SD route and explicitly confirmed SPKI pin.
 * The private hostname never enters DNS; every address comes from the native resolution.
 * This client is never shared with cloud, updates, or other receiver sessions.
 */
class LocalReceiverConnection(val device: ReceiverDevice, private val fingerprint: String,
    private val pins: ReceiverPins, sockets: SocketFactory = SocketFactory.getDefault()) : AutoCloseable {
    private val host = "nearcast-receiver.invalid"
    val endpoint = "https://$host:${device.port}/?role=sender"
    private val subscriptions = CopyOnWriteArrayList<AutoCloseable>()
    @Volatile private var closed = false
    val client: OkHttpClient
    init {
        require(device.selectable && device.deviceId != null && validFingerprint(fingerprint) && device.port in 1..65535)
        val addresses = device.addresses.mapNotNull(::literalAddress).distinct()
        require(addresses.isNotEmpty() && addresses.size <= 8)
        requireTrusted()
        val verifier = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificate unsupported") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                requireTrusted()
                if (chain?.firstOrNull()?.let(::spki) != fingerprint) throw CertificateException("Receiver key changed", ReceiverKeyChanged())
            }
        }
        // New context/pool per owner: no resumed TLS session can skip a changed pin.
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(verifier), null) }
        client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, verifier)
            .hostnameVerifier { hostname, session ->
                hostname == host && runCatching {
                    requireTrusted(); spki(session.peerCertificates.first() as X509Certificate) == fingerprint
                }.getOrDefault(false)
            }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (hostname != host) throw UnknownHostException("Only the selected receiver is allowed")
                    requireTrusted(); return addresses
                }
            })
            .socketFactory(sockets).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                requireTrusted()
                val url = chain.request().url
                require(url.isHttps && url.host == host && url.port == device.port && url.encodedPath == "/" && url.query == "role=sender")
                chain.proceed(chain.request())
            }.build()
    }
    fun requireTrusted() {
        if (closed || pins.get(device.deviceId!!) != fingerprint) throw ReceiverTrustRevoked()
    }
    fun onTrustChanged(invalidated: () -> Unit): AutoCloseable {
        val subscription = pins.subscribe(device.deviceId!!) {
            if (runCatching { requireTrusted() }.isFailure) invalidated()
        }
        subscriptions += subscription
        // Close the check/subscribe race; an earlier revocation must also invalidate.
        if (runCatching { requireTrusted() }.isFailure) invalidated()
        return AutoCloseable { subscriptions.remove(subscription); subscription.close() }
    }
    override fun close() {
        if (closed) return
        closed = true
        subscriptions.forEach { it.close() }; subscriptions.clear()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private fun spki(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        .joinToString("") { "%02X".format(it) }
}
