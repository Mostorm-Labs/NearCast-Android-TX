package com.auditoryworks.nearcast.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import kotlin.concurrent.thread

class ReceiverTlsTest {
    private fun keys(name: String) = KeyStore.getInstance("PKCS12").apply {
        ReceiverTlsTest::class.java.getResourceAsStream("/discovery/$name.p12")!!.use { load(it, "fixture-only".toCharArray()) }
    }
    private fun pin(name: String): String = MessageDigest.getInstance("SHA-256").digest(keys(name).getCertificate("receiver").publicKey.encoded)
        .joinToString("") { "%02X".format(it) }
    private class Server(val socket: SSLServerSocket, val device: ReceiverDevice, val bytes: AtomicInteger, val worker: Thread) : AutoCloseable {
        override fun close() { socket.close(); worker.join(3500); assertFalse("TLS fixture must finish", worker.isAlive) }
    }
    private fun server(name: String = "receiver-a", response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}") : Server {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys(name), "fixture-only".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = ssl.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        socket.soTimeout = 3000
        val bytes = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try { (socket.accept() as SSLSocket).use { client ->
                client.soTimeout = 3000; client.startHandshake()
                val input = client.inputStream
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n") && header.length < 8192) {
                    val next = input.read(); if (next < 0) return@thread
                    bytes.incrementAndGet(); header.append(next.toChar())
                }
                val length = Regex("(?i)Content-Length: ([0-9]+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                repeat(length) { if (input.read() >= 0) bytes.incrementAndGet() }
                client.outputStream.write(response.toByteArray(Charsets.US_ASCII)); client.outputStream.flush()
            } } catch (_: java.io.IOException) { /* rejected/observation-only handshakes close without HTTP */ }
        }
        val device = ReceiverDevice("fixture", "receiver", "Fixture", listOf("127.0.0.1"), socket.localPort, "", "", setOf("webcast", "trust-display-v1", "code-display-v1"))
        return Server(socket, device, bytes, worker)
    }
    @Test fun observationReadsSpkiWithoutSendingHttp() = runBlocking {
        server().use { fixture ->
            assertEquals(pin("receiver-a"), ReceiverTls().observe(fixture.device))
            fixture.worker.join(3000)
            assertEquals(0, fixture.bytes.get())
        }
    }
    @Test fun changedKeyIsRejectedBeforeAnyApplicationBytes() = runBlocking {
        server("receiver-b").use { fixture ->
            var rejected = false
            try { ReceiverTls().post(fixture.device, DisplayOperation.CODE, "{}".toByteArray(), pin("receiver-a")) }
            catch (_: Exception) { rejected = true }
            assertTrue("An unrelated receiver key must be rejected", rejected)
            fixture.worker.join(3000); assertEquals(0, fixture.bytes.get())
        }
    }
    @Test fun certificateReissueWithTheSameKeyRetainsTrust() = runBlocking {
        server("receiver-a-reissued").use { fixture ->
            assertArrayEquals("{}".toByteArray(), ReceiverTls().post(fixture.device, DisplayOperation.CODE, "{}".toByteArray(), pin("receiver-a")))
            assertTrue(fixture.bytes.get() > 0)
        }
    }
    @Test fun redirectsAmbiguousLengthAndOversizedResponsesAreRejected() = runBlocking {
        for (response in listOf("HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: https://other.invalid\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}")) {
            server(response = response).use { fixture ->
                var rejected = false
                try { ReceiverTls().post(fixture.device, DisplayOperation.CODE, "{}".toByteArray(), pin("receiver-a")) }
                catch (_: Exception) { rejected = true }
                assertTrue("Unbounded or redirected HTTP response must be rejected", rejected)
            }
        }
    }
}
