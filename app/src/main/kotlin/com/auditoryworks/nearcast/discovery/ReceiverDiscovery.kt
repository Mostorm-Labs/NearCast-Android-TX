package com.auditoryworks.nearcast.discovery

import java.net.InetAddress
import java.net.URI

data class ReceiverAdvertisement(val instance: String, val serviceType: String, val addresses: List<String>,
    val port: Int, val txt: Map<String, String>)
data class ReceiverDevice(val instance: String, val deviceId: String?, val name: String,
    val addresses: List<String>, val port: Int, val model: String, val version: String, val capabilities: Set<String>,
    val runtimeInstanceId: String? = null, val localReady: Boolean? = null) {
    val selectable get() = "webcast" in capabilities
    val supportsCodeDisplay get() = selectable && "code-display-v1" in capabilities
    val supportsTrustDisplay get() = selectable && "trust-display-v1" in capabilities
    val codeSelection get() = if ("signaling-select-v1" in capabilities && "code-display-v2" in capabilities && runtimeInstanceId != null) CodeSelection(runtimeInstanceId) else null
    val routeKey get() = "$instance|$deviceId|${addresses.sorted()}|$port|$runtimeInstanceId"
}
object ReceiverDiscovery {
    const val SERVICE_TYPE = "_nearcast._tcp."
    fun normalize(value: ReceiverAdvertisement): ReceiverDevice? = runCatching {
        require(value.serviceType.lowercase().trimEnd('.').removeSuffix(".local") == "_nearcast._tcp")
        require(value.instance.isNotBlank() && value.instance.length <= 256 && value.port in 1..65535)
        require(value.txt.size <= 64 && value.txt.keys.none { it.lowercase() in setOf("token", "room", "roomcode", "paircode", "password", "secret") })
        val caps = value.txt["capability"].orEmpty().lowercase().split(Regex("[\\s,;]+"))
            .filter { it.isNotBlank() }.take(32).toSet().let { if ("webcast" in it) it else emptySet() }
        if ("webcast" in caps) {
            val endpoint = listOf("endpoint", "url", "wss", "https").firstNotNullOfOrNull { value.txt[it]?.trim()?.takeIf(String::isNotEmpty) }
            if (endpoint != null) {
                val uri = URI(endpoint)
                require(uri.scheme in setOf("https", "wss") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            }
        }
        val addresses = value.addresses.distinct().take(8).filter { literalAddress(it) != null }
        require(addresses.isNotEmpty())
        val id = listOf("deviceId", "device_id", "id", "deviceid").firstNotNullOfOrNull { value.txt[it]?.trim()?.takeIf(String::isNotEmpty) }
        require(id == null || validIdentity(id))
        val runtime = value.txt["runtime"]?.takeIf(String::isNotEmpty)
        require(runtime == null || validIdentity(runtime))
        val localReady = when (value.txt["local-state"]) { "ready" -> true; "unavailable" -> false; else -> null }
        ReceiverDevice(value.instance, id, (value.txt["display"]?.trim()?.takeIf(String::isNotEmpty) ?: value.instance).take(128),
            addresses, value.port, value.txt["model"].orEmpty().take(128), value.txt["version"].orEmpty().take(64), caps, runtime, localReady)
    }.getOrNull()
}

internal fun validIdentity(value: String) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128 && value.none { it.isISOControl() }
internal fun literalAddress(value: String): InetAddress? = runCatching {
    require(value.length <= 128)
    if (':' in value) require(value.matches(Regex("[0-9a-fA-F:.]+(%[a-zA-Z0-9_.-]+)?")))
    else require(value.split('.').let { it.size == 4 && it.all { part -> part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 } })
    // Only numeric A/AAAA answers reach this call; never resolve an advertised TXT hostname.
    InetAddress.getByName(value).also { require(!it.isAnyLocalAddress && !it.isMulticastAddress) }
}.getOrNull()
