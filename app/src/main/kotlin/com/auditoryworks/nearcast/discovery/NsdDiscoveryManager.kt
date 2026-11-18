package com.auditoryworks.nearcast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.coroutines.resume

enum class DiscoveryIssue { START_FAILED, RESOLVE_FAILED, PERMISSION, STOPPED }
data class DiscoverySnapshot(val devices: List<ReceiverDevice> = emptyList(), val scanning: Boolean = false, val issue: DiscoveryIssue? = null)

/** Foreground-only system DNS-SD; no TXT address or fixed UI TTL becomes a native route. */
class NsdDiscoveryManager(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong()
    private val native = mutableMapOf<String, Pair<ReceiverDevice, SocketFactory>>()
    @Synchronized fun current(instance: String): ReceiverDevice? = native[instance]?.first
    @Synchronized fun socketFactory(device: ReceiverDevice): SocketFactory = native[device.instance]
        ?.takeIf { it.first.routeKey == device.routeKey }?.second ?: throw IllegalStateException("Receiver offline")

    @Suppress("DEPRECATION")
    fun discoverServices(): Flow<DiscoverySnapshot> = callbackFlow {
        val generation = epoch.incrementAndGet()
        synchronized(this@NsdDiscoveryManager) { native.clear() }
        val live = AtomicBoolean(true)
        var issue: DiscoveryIssue? = null
        var scanning = true
        val directory = ReceiverDirectory()
        val known = mutableMapOf<String, NsdServiceInfo>()
        val subscriptions = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        val requests = Channel<Pair<NsdServiceInfo, Long>>(64)
        val queued = mutableSetOf<String>()
        val lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("nearcast-discovery")?.apply { setReferenceCounted(false) }
        fun active() = live.get() && epoch.get() == generation
        fun emit() { if (active()) trySend(DiscoverySnapshot(directory.list(), scanning, issue)) }
        fun dispatch(block: () -> Unit) { handler.post { if (active()) block() } }
        fun remove(instance: String, token: Long? = null) {
            if (token == null) directory.lost(instance) else if (!directory.unresolved(instance, token)) return
            synchronized(this@NsdDiscoveryManager) { if (epoch.get() == generation) native.remove(instance) }
            emit()
        }
        fun resolved(info: NsdServiceInfo, token: Long) {
            if (!active()) return
            val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            val receiver = ReceiverDiscovery.normalize(ReceiverAdvertisement(info.serviceName, info.serviceType,
                addresses.mapNotNull { it.hostAddress }, info.port,
                info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }))
            if (receiver == null) { remove(info.serviceName, token); return }
            if (directory.resolved(info.serviceName, token, receiver)) {
                val factory = if (Build.VERSION.SDK_INT >= 33) info.network?.socketFactory ?: SocketFactory.getDefault() else SocketFactory.getDefault()
                synchronized(this@NsdDiscoveryManager) { if (epoch.get() == generation) native[receiver.instance] = receiver to factory }
                emit()
            }
        }
        fun enqueue(info: NsdServiceInfo, token: Long) {
            if (queued.add(info.serviceName) && !requests.trySend(info to token).isSuccess) queued.remove(info.serviceName)
        }
        val worker = launch(Dispatchers.Main.immediate) {
            for ((info, token) in requests) {
                try {
                    val result = withTimeoutOrNull(6000) {
                        suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                            try { nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { dispatch { if (continuation.isActive) continuation.resume(null) } }
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { dispatch { if (continuation.isActive) continuation.resume(serviceInfo) } }
                            }) } catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
                        }
                    }
                    if (result != null) resolved(result, token)
                } finally { queued.remove(info.serviceName) }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = dispatch { scanning = true; issue = null; emit() }
            override fun onServiceFound(info: NsdServiceInfo) = dispatch {
                val token = directory.found(info.serviceName) ?: return@dispatch
                known[info.serviceName] = info
                if (Build.VERSION.SDK_INT >= 34 && subscriptions.size < 32) {
                    if (info.serviceName in subscriptions) return@dispatch
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = dispatch registrationFailed@{
                            if (!directory.matches(info.serviceName, token)) return@registrationFailed
                            subscriptions.remove(info.serviceName); issue = DiscoveryIssue.RESOLVE_FAILED; enqueue(info, token); emit()
                        }
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = dispatch { resolved(serviceInfo, token) }
                        override fun onServiceLost() = dispatch { remove(info.serviceName, token) }
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    subscriptions[info.serviceName] = callback
                    try { nsd.registerServiceInfoCallback(info, { command -> handler.post(command) }, callback) }
                    catch (_: RuntimeException) { subscriptions.remove(info.serviceName); enqueue(info, token) }
                } else if (info.serviceName !in subscriptions) enqueue(info, token)
            }
            override fun onServiceLost(info: NsdServiceInfo) = dispatch {
                known.remove(info.serviceName)
                if (Build.VERSION.SDK_INT >= 34) subscriptions.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                remove(info.serviceName)
            }
            override fun onDiscoveryStopped(serviceType: String) = dispatch { scanning = false; issue = DiscoveryIssue.STOPPED; directory.clear(); synchronized(this@NsdDiscoveryManager) { native.clear() }; emit() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = dispatch { scanning = false; issue = DiscoveryIssue.START_FAILED; emit() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        val refresh = launch(Dispatchers.Main.immediate) {
            while (isActive) { delay(30000); known.values.filter { it.serviceName !in subscriptions }.forEach { info -> directory.found(info.serviceName)?.let { enqueue(info, it) } } }
        }
        dispatch {
            try { lock?.acquire(); nsd.discoverServices(ReceiverDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: SecurityException) { scanning = false; issue = DiscoveryIssue.PERMISSION; emit() }
            catch (_: RuntimeException) { scanning = false; issue = DiscoveryIssue.START_FAILED; emit() }
        }
        emit()
        awaitClose {
            live.set(false); refresh.cancel(); worker.cancel(); requests.close()
            synchronized(this@NsdDiscoveryManager) { if (epoch.get() == generation) native.clear() }
            handler.post {
                runCatching { nsd.stopServiceDiscovery(listener) }
                if (Build.VERSION.SDK_INT >= 34) subscriptions.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                subscriptions.clear(); if (lock?.isHeld == true) runCatching { lock.release() }
            }
        }
    }
}
