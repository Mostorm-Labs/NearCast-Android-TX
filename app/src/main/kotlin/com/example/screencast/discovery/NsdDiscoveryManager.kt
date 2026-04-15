package com.example.screencast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "NsdDiscoveryManager"

class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolvedServices = mutableMapOf<String, NsdServiceInfo>()

    fun discoverServices(vararg serviceTypes: String): Flow<List<DiscoveredService>> = callbackFlow {
        // Reset cached resolved services for each new scan session.
        // Otherwise, same-name services discovered in previous sessions are skipped.
        resolvedServices.clear()
        val devices = mutableListOf<DiscoveredService>()
        val pendingResolves = mutableSetOf<String>()
        var currentServiceIndex = 0

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} (type: ${serviceInfo.serviceType})")
                val key = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                if (!resolvedServices.containsKey(key) && !pendingResolves.contains(key)) {
                    pendingResolves.add(key)
                    resolveService(serviceInfo) { resolved ->
                        pendingResolves.remove(key)
                        if (resolved != null) {
                            resolvedServices[key] = resolved
                            val device = nsdServiceInfoToDiscoveredService(resolved)
                            val existing = devices.indexOfFirst {
                                it.name == device.name && it.serviceType == device.serviceType
                            }
                            if (existing >= 0) {
                                devices[existing] = device
                            } else {
                                devices.add(device)
                            }
                            Log.d(TAG, "Emitting ${devices.size} devices: ${devices.map { "${it.name}(${it.serviceType})" }}")
                            trySend(devices.toList())
                        }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val key = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                resolvedServices.remove(key)
                devices.removeAll { "${it.serviceType}:${it.name}" == key }
                trySend(devices.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $serviceType: errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed for $serviceType: errorCode=$errorCode")
            }
        }

        // Start discovery for first service type only (Android NsdManager only allows one active browse)
        mainHandler.post {
            try {
                val serviceType = serviceTypes[currentServiceIndex]
                Log.d(TAG, "Starting discovery for $serviceType")
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery", e)
            }
        }

        // Periodically switch between service types if no devices found
        var isDiscoveryActive = true
        val switchRunnable = object : Runnable {
            override fun run() {
                if (devices.isEmpty() && isDiscoveryActive) {
                    // Stop current discovery first (this is async but necessary)
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (_: Exception) {}

                    // Wait a bit then start next discovery
                    mainHandler.postDelayed({
                        if (isDiscoveryActive) {
                            currentServiceIndex = (currentServiceIndex + 1) % serviceTypes.size
                            val nextServiceType = serviceTypes[currentServiceIndex]
                            Log.d(TAG, "Switching to $nextServiceType")
                            try {
                                nsdManager.discoverServices(nextServiceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start discovery for $nextServiceType", e)
                            }
                            // Schedule next switch
                            mainHandler.postDelayed(this, 5000) // Switch every 5 seconds
                        }
                    }, 1000) // Wait 1 second for stop to complete
                }
            }
        }
        mainHandler.postDelayed(switchRunnable, 5000)

        awaitClose {
            Log.d(TAG, "Stopping discovery...")
            isDiscoveryActive = false
            mainHandler.removeCallbacks(switchRunnable)
            mainHandler.post {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {
                    Log.w(TAG, "Error stopping discovery", null)
                }
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (NsdServiceInfo?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                onResolved(null)
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} -> ${resolvedInfo.host}:${resolvedInfo.port}")
                onResolved(resolvedInfo)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Resolve exception", e)
            onResolved(null)
        }
    }

    private fun nsdServiceInfoToDiscoveredService(info: NsdServiceInfo): DiscoveredService {
        val txtRecord = parseTxtRecord(info)
        return DiscoveredService(
            name = info.serviceName,
            host = info.host?.hostAddress ?: "",
            port = info.port,
            serviceType = info.serviceType,
            txtRecord = txtRecord
        )
    }

    private fun parseTxtRecord(info: NsdServiceInfo): Map<String, String> {
        return try {
            val rawTxtRecord = info.attributes
            if (rawTxtRecord == null) {
                emptyMap()
            } else {
                rawTxtRecord.mapNotNull { (key, value) ->
                    val keyStr = key
                    val valueStr = if (value != null) String(value, Charsets.UTF_8) else ""
                    keyStr to valueStr
                }.toMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse TXT record", e)
            emptyMap()
        }
    }
}
