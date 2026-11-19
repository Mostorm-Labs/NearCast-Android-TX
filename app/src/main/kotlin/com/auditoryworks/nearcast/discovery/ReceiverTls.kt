package com.auditoryworks.nearcast.discovery

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.*

interface ReceiverTransport {
    suspend fun observe(device: ReceiverDevice): String
    suspend fun post(device: ReceiverDevice, operation: DisplayOperation, bytes: ByteArray, fingerprint: String): ByteArray
}
class ReceiverKeyChanged : IOException("NEARCAST_KEY_MISMATCH")
class ReceiverTls(private val sockets: (ReceiverDevice) -> SocketFactory = { SocketFactory.getDefault() }) : ReceiverTransport {
    override suspend fun observe(device: ReceiverDevice): String = exchange(device, null) { socket -> fingerprint(socket.session.peerCertificates.first() as X509Certificate) }
    override suspend fun post(device: ReceiverDevice, operation: DisplayOperation, bytes: ByteArray, fingerprint: String): ByteArray {
        require(bytes.size <= 1024 && validFingerprint(fingerprint))
        return exchange(device, fingerprint) { socket ->
            currentCoroutineContext().ensureActive()
            val host = socket.inetAddress.hostAddress!!.let { if (':' in it) "[$it]" else it }
            val headers = "POST ${operation.path} HTTP/1.1\r\nHost: $host:${device.port}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            socket.outputStream.apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            readResponse(socket)
        }
    }
    private suspend fun <T> exchange(device: ReceiverDevice, expected: String?, operation: suspend (SSLSocket) -> T): T {
        val live = AtomicReference<Socket?>()
        return withTimeout(6000) {
            coroutineScope {
                val work = async(Dispatchers.IO) {
                    val deadline = System.nanoTime() + 5_000_000_000L
                    var failure: Exception? = null
                    for (address in device.addresses.take(8)) {
                        currentCoroutineContext().ensureActive()
                        val ip = literalAddress(address) ?: continue
                        val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(5000).toInt()
                        if (remaining <= 0) break
                        var applicationPhase = false
                        try {
                            sockets(device).createSocket().use { tcp ->
                                live.set(tcp); currentCoroutineContext().ensureActive()
                                tcp.connect(InetSocketAddress(ip, device.port), minOf(1500, remaining)); tcp.soTimeout = remaining
                                // A fresh TLS context prevents session resumption from skipping pin verification.
                                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(verifier(expected)), null) }
                                (context.socketFactory.createSocket(tcp, ip.hostAddress, device.port, true) as SSLSocket).use { tls ->
                                    live.set(tls); currentCoroutineContext().ensureActive()
                                    tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                                    tls.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 5000).toInt()
                                    tls.startHandshake() // Certificate pin and TLS proof of key possession precede HTTP.
                                    currentCoroutineContext().ensureActive()
                                    applicationPhase = true
                                    return@async operation(tls)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (generateSequence<Throwable>(error) { it.cause }.any { it is ReceiverKeyChanged }) throw ReceiverKeyChanged()
                            if (applicationPhase) throw error // Never repeat an HTTP operation on another address.
                            failure = error
                        } finally { live.getAndSet(null)?.let { runCatching { it.close() } } }
                    }
                    throw failure ?: IOException("Receiver unavailable")
                }
                try { work.await() } finally { live.getAndSet(null)?.let { runCatching { it.close() } } }
            }
        }
    }
    private fun fingerprint(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        .joinToString("") { "%02X".format(it) }
    // Observation sends no HTTP. Every HTTP connection requires the exact observed or saved
    // SHA256(DER SPKI), matching Desktop's selected-device bootstrap and pinned requests.
    private fun verifier(expected: String?) = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("No client authentication") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw CertificateException("Receiver certificate required")
            if (expected != null && fingerprint(certificate) != expected) throw CertificateException("Receiver key changed", ReceiverKeyChanged())
        }
    }
    private fun readResponse(socket: SSLSocket): ByteArray {
        val input = socket.inputStream
        val header = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            require(header.size() < 8192) { "Receiver headers too large" }
            val next = input.read(); require(next >= 0) { "Incomplete receiver headers" }
            require(next in 9..13 || next in 32..126) { "Invalid receiver headers" }
            header.write(next); tail = (tail shl 8) or next
        }
        val lines = header.toString("US-ASCII").split("\r\n")
        require(lines.first().matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) { "Receiver HTTPS status" }
        val fields = lines.drop(1).filter { it.isNotEmpty() }.map {
            require(':' in it && !it.startsWith(' ') && !it.startsWith('\t'))
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }
        require(fields.none { it.first == "transfer-encoding" })
        val lengths = fields.filter { it.first == "content-length" }
        require(lengths.size == 1 && lengths.single().second.matches(Regex("[0-9]{1,4}")))
        val length = lengths.single().second.toInt(); require(length <= 4096)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) { val read = input.read(body, offset, length - offset); require(read > 0); offset += read }
        return body
    }
}
