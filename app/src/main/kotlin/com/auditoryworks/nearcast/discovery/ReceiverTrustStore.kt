package com.auditoryworks.nearcast.discovery

import android.content.Context
import org.json.JSONObject

class ReceiverTrustStore(context: Context) : ReceiverPins {
    private val preferences = context.applicationContext.getSharedPreferences("receiver-trust-v1", Context.MODE_PRIVATE)
    private companion object {
        @Volatile var failedWrite = false
        val lock = Any()
        val listeners = java.util.concurrent.CopyOnWriteArrayList<Pair<String, () -> Unit>>()
    }
    private fun load(): JSONObject = try {
        check(!failedWrite)
        val raw = preferences.getString("document", null) ?: """{"version":1,"devices":{}}"""
        require(raw.length <= 262144)
        val document = JSONObject(raw)
        require(document.keys().asSequence().toSet() == setOf("version", "devices") && document.opt("version") == 1)
        document.getJSONObject("devices").also { devices ->
            require(devices.length() <= 512 && devices.keys().asSequence().all { validIdentity(it) && validFingerprint(devices.getString(it)) })
        }
    } catch (error: Exception) { throw TrustStorageFailure(error) }
    override fun get(deviceId: String): String? = synchronized(lock) { load().opt(deviceId) as? String }
    override fun save(deviceId: String, fingerprint: String) = synchronized(lock) {
        require(validIdentity(deviceId) && validFingerprint(fingerprint))
        val devices = load(); if (!devices.has(deviceId) && devices.length() >= 512) throw TrustStorageFailure()
        devices.put(deviceId, fingerprint); persist(devices); changed(deviceId)
    }
    override fun revoke(deviceId: String) = synchronized(lock) { val devices = load(); devices.remove(deviceId); persist(devices); changed(deviceId) }
    override fun subscribe(deviceId: String, changed: () -> Unit): AutoCloseable {
        val entry = deviceId to changed; listeners += entry
        return AutoCloseable { listeners.remove(entry) }
    }
    private fun changed(id: String? = null) { listeners.filter { id == null || it.first == id }.forEach { runCatching { it.second() } } }
    private fun persist(devices: JSONObject) {
        try {
            if (!preferences.edit().putString("document", JSONObject().put("version", 1).put("devices", devices).toString()).commit()) {
                failedWrite = true; throw TrustStorageFailure()
            }
        } catch (error: Exception) { failedWrite = true; changed(); throw TrustStorageFailure(error) }
    }
}
