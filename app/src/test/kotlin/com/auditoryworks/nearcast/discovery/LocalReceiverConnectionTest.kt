package com.auditoryworks.nearcast.discovery

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class LocalReceiverConnectionTest {
    private class Pins(var pin: String?) : ReceiverPins {
        var listener: (() -> Unit)? = null
        override fun get(deviceId: String) = pin
        override fun save(deviceId: String, fingerprint: String) { pin = fingerprint; listener?.invoke() }
        override fun revoke(deviceId: String) { pin = null; listener?.invoke() }
        override fun subscribe(deviceId: String, changed: () -> Unit): AutoCloseable {
            listener = changed
            return AutoCloseable { listener = null }
        }
    }
    private fun keys(name: String) = KeyStore.getInstance("PKCS12").apply {
        LocalReceiverConnectionTest::class.java.getResourceAsStream("/discovery/$name.p12")!!.use { load(it, "fixture-only".toCharArray()) }
    }
    private fun pin(name: String) = MessageDigest.getInstance("SHA-256").digest(keys(name).getCertificate("receiver").publicKey.encoded)
        .joinToString("") { "%02X".format(it) }
    private fun server(name: String): MockWebServer {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys(name), "fixture-only".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        return MockWebServer().apply { useHttps(tls.socketFactory, false); start() }
    }
    private fun device(port: Int) = ReceiverDevice("fixture", "fixture-id", "Local receiver", listOf("127.0.0.1"), port, "", "1", setOf("webcast"))

    @Test fun untrustedReceiverCannotCreateConnection() {
        assertThrows(ReceiverTrustRevoked::class.java) { LocalReceiverConnection(device(443), pin("receiver-a"), Pins(null)) }
    }
    @Test fun pinnedWssUsesResolvedAddressAndRootSenderProtocol() {
        server("receiver-a").use { server ->
            val accepted = CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == "local-only") accepted.countDown()
                    webSocket.close(1000, "done")
                }
            }))
            LocalReceiverConnection(device(server.port), pin("receiver-a"), Pins(pin("receiver-a"))).use { connection ->
                val socket = connection.client.newWebSocket(Request.Builder().url(connection.endpoint).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("local-only") }
                })
                assertTrue("Pinned local WSS must upgrade and deliver input", accepted.await(5, TimeUnit.SECONDS))
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/?role=sender", request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                socket.cancel()
            }
        }
    }
    @Test fun changedKeyCannotReceiveHttpUpgradeOrPairingBytes() {
        server("receiver-b").use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            LocalReceiverConnection(device(server.port), pin("receiver-a"), Pins(pin("receiver-a"))).use { connection ->
                val failed = CountDownLatch(1)
                connection.client.newWebSocket(Request.Builder().url(connection.endpoint).build(), object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.countDown() }
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("must-not-leak") }
                })
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertNull("Key mismatch must fail before the HTTP upgrade", server.takeRequest(200, TimeUnit.MILLISECONDS))
            }
        }
    }
    @Test fun samePublicKeyReissuedCertificateStillConnects() {
        server("receiver-a-reissued").use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            LocalReceiverConnection(device(server.port), pin("receiver-a"), Pins(pin("receiver-a"))).use { connection ->
                val opened = CountDownLatch(1)
                val socket = connection.client.newWebSocket(Request.Builder().url(connection.endpoint).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                })
                assertTrue(opened.await(5, TimeUnit.SECONDS)); socket.cancel()
            }
        }
    }
    @Test fun revokedPinImmediatelyInvalidatesOwnerAndFutureRequests() {
        val pins = Pins(pin("receiver-a"))
        LocalReceiverConnection(device(443), pins.pin!!, pins).use { connection ->
            var revoked = false
            val subscription = connection.onTrustChanged { revoked = true }
            pins.revoke("fixture-id")
            assertTrue(revoked)
            assertThrows(ReceiverTrustRevoked::class.java) { connection.requireTrusted() }
            subscription.close()
        }
        assertNull(pins.listener)
    }
    @Test fun localConnectionRejectsNonWebcastAndInvalidPin() {
        assertThrows(IllegalArgumentException::class.java) { LocalReceiverConnection(device(443).copy(capabilities = emptySet()), pin("receiver-a"), Pins(pin("receiver-a"))) }
        assertThrows(IllegalArgumentException::class.java) { LocalReceiverConnection(device(443), "bad", Pins("bad")) }
    }
}
