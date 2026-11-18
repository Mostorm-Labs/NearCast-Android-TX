package com.auditoryworks.nearcast.discovery

class ReceiverDirectory {
    private data class Entry(val token: Long, var device: ReceiverDevice? = null)
    private var sequence = 0L
    private val entries = linkedMapOf<String, Entry>()
    fun found(instance: String): Long? = entries[instance]?.token ?: run {
        if (entries.size >= 64) null else (++sequence).also { entries[instance] = Entry(it) }
    }
    fun resolved(instance: String, token: Long, device: ReceiverDevice): Boolean {
        val entry = entries[instance] ?: return false
        if (entry.token != token || device.instance != instance) return false
        entry.device = device; return true
    }
    fun matches(instance: String, token: Long) = entries[instance]?.token == token
    fun unresolved(instance: String, token: Long): Boolean {
        if (!matches(instance, token)) return false
        entries.getValue(instance).device = null; return true
    }
    fun lost(instance: String) { entries.remove(instance) }
    fun clear() { sequence++; entries.clear() }
    fun list(): List<ReceiverDevice> = entries.values.mapNotNull { it.device }.sortedWith(compareBy({ it.name }, { it.instance }))
}
