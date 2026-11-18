package com.auditoryworks.nearcast.discovery

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.auditoryworks.nearcast.R
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.*

/** Owned DNS-SD + self-signed receiver fixture. No release source or real pairing credentials. */
class DiscoveryFixtureActivity : Activity() {
    companion object { var active = WeakReference<DiscoveryFixtureActivity>(null) }
    private lateinit var nsd: NsdManager
    private lateinit var server: SSLServerSocket
    private lateinit var label: TextView
    private val worker = Executors.newFixedThreadPool(6)
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private var webcast = false
    @Volatile private var keyChoice = "a"
    @Volatile private var status = "shown"
    private var registration: NsdManager.RegistrationListener? = null
    private lateinit var keyManagers: Map<String, X509KeyManager>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); active = WeakReference(this)
        webcast = intent.getBooleanExtra("webcast", false)
        label = TextView(this).apply { textSize = 17f; setPadding(32, 180, 32, 32) }; setContentView(label)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        keyManagers = mapOf("a" to manager(R.raw.discovery_fixture_a), "b" to manager(R.raw.discovery_fixture_b))
        val rotating = object : X509KeyManager {
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? =
                keyChoice.let { choice -> keyManagers.getValue(choice).chooseServerAlias(type, issuers, socket)?.let { "$choice:$it" } }
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = keyManagers.getValue(keyChoice).getServerAliases(type, issuers)?.map { "$keyChoice:$it" }?.toTypedArray()
            override fun getPrivateKey(alias: String): PrivateKey = keyManagers.getValue(alias.substringBefore(':')).getPrivateKey(alias.substringAfter(':'))
            override fun getCertificateChain(alias: String): Array<X509Certificate> = keyManagers.getValue(alias.substringBefore(':')).getCertificateChain(alias.substringAfter(':'))
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
        }
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(rotating), null, null) }
        server = tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        register()
        worker.execute {
            while (!server.isClosed) try {
                val socket = server.accept() as SSLSocket
                if (connections.size >= 4) socket.close() else {
                    connections += socket
                    worker.execute { try { socket.use(::respond) } catch (_: Exception) { } finally { connections -= socket } }
                }
            }
            catch (_: Exception) { /* observation-only sockets close before sending HTTP */ }
        }
        render("Owned fixture ready")
    }
    private fun manager(resource: Int): X509KeyManager {
        val keys = KeyStore.getInstance("PKCS12").apply { resources.openRawResource(resource).use { load(it, "fixture-only".toCharArray()) } }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }.keyManagers.filterIsInstance<X509KeyManager>().first()
    }
    private fun render(message: String, fingerprint: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { render(message, fingerprint) }; return }
        val key = keyManagers.getValue(keyChoice).let { it.getCertificateChain(it.getServerAliases("RSA", null).first()).first() }
        val pin = fingerprint ?: MessageDigest.getInstance("SHA-256").digest(key.publicKey.encoded).joinToString("") { "%02X".format(it) }
        label.text = "NearCast owned discovery fixture\n$message\n\n$pin\n\nPair code: TEST\nStatus: $status\nPort: ${server.localPort}"
        Log.i("NearCastDiscoveryFixture", JSONObject().put("event", message).put("fingerprint", pin).put("port", server.localPort).toString())
    }
    private fun register() {
        if (registration != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { render("Registered ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { render("Registration failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) { render("Withdrawn") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "NearCast Android Owned Fixture"; serviceType = ReceiverDiscovery.SERVICE_TYPE; port = server.localPort
            setAttribute("id", "android-owned-fixture"); setAttribute("display", "NearCast 测试接收端")
            setAttribute("capability", "webcast,trust-display-v1,code-display-v1,touchback-v1")
            if (webcast) {
                setAttribute("capability", "webcast,trust-display-v1,code-display-v1,touchback-v1,signaling-select-v1,code-display-v2")
                setAttribute("runtime", "android-owned-fixture-runtime")
                setAttribute("local-state", "ready"); setAttribute("cloud-state", "unavailable")
            }
            setAttribute("endpoint", "https://unrelated.invalid:1234")
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    private fun respond(socket: SSLSocket) {
        socket.soTimeout = 5000; socket.startHandshake()
        val input = socket.inputStream
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n") && header.length < 8192) { val next = input.read(); if (next < 0) return; header.append(next.toChar()) }
        if (webcast && header.startsWith("GET /?role=sender HTTP/1.1\r\n") && header.contains("Upgrade: websocket", ignoreCase = true)) {
            // Debug-only TLS termination forwards the owned WebCast fixture over adb reverse.
            // Production local casting uses the receiver directly and never this proxy.
            java.net.Socket("127.0.0.1", 18888).use { upstream ->
                connections += upstream
                socket.soTimeout = 0
                upstream.outputStream.apply { write(header.toString().toByteArray(Charsets.US_ASCII)); flush() }
                worker.execute {
                    try { input.copyTo(upstream.outputStream) } catch (_: Exception) { }
                    finally { runCatching { upstream.close() }; runCatching { socket.close() } }
                }
                try { upstream.inputStream.copyTo(socket.outputStream) }
                finally { connections -= upstream }
            }
            return
        }
        val length = Regex("(?i)Content-Length: ([0-9]+)").find(header)?.groupValues?.get(1)?.toInt() ?: return
        if (length !in 1..1024) return
        val body = ByteArray(length); var offset = 0
        while (offset < length) { val n = input.read(body, offset, length - offset); if (n < 1) return; offset += n }
        val request = JSONObject(String(body, Charsets.UTF_8))
        val operation = DisplayOperation.entries.firstOrNull { header.startsWith("POST ${it.path} HTTP/1.1\r\n") } ?: return
        check(request.getString("type") == "nearcast.${operation.wireName}.request" && request.getString("deviceId") == "android-owned-fixture")
        val pin = MessageDigest.getInstance("SHA-256").digest((socket.session.localCertificates.first() as X509Certificate).publicKey.encoded).joinToString("") { "%02X".format(it) }
        Log.i("NearCastDiscoveryFixture", JSONObject().put("operation", operation.name).put("requestId", request.getString("requestId")).put("status", status).toString())
        val resultObject = JSONObject().put("type", "nearcast.${operation.wireName}.result").put("version", 1).put("requestId", request.getString("requestId")).put("status", status)
        if (request.optInt("version") == 2) {
            check(webcast && operation == DisplayOperation.CODE && request.getString("backend") == "local" && request.getString("runtimeInstanceId") == "android-owned-fixture-runtime")
            resultObject.put("version", 2).put("backend", "local").put("deviceId", "android-owned-fixture").put("runtimeInstanceId", "android-owned-fixture-runtime")
        }
        val result = resultObject.toString().toByteArray()
        socket.outputStream.apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${result.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(result); flush() }
        runOnUiThread { render("${operation.name} $status", pin) }
    }
    fun command(action: String?) { when (action) {
        "withdraw" -> { registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null }
        "restore" -> register()
        "rotate-key" -> { keyChoice = if (keyChoice == "a") "b" else "a"; render("Key rotated") }
        "rate_limited", "unavailable", "shown" -> { status = action; render("Status changed") }
        "input-probe" -> startActivity(Intent(this, com.auditoryworks.nearcast.touchback.InputProbeActivity::class.java))
        "stop" -> finish()
    } }
    override fun onDestroy() {
        active.clear(); registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        if (::server.isInitialized) server.close()
        connections.forEach { runCatching { it.close() } }; connections.clear()
        worker.shutdownNow(); super.onDestroy()
    }
}
class DiscoveryFixtureControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { DiscoveryFixtureActivity.active.get()?.command(intent.getStringExtra("action")) }
}
