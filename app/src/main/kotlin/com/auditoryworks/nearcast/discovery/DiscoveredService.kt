package com.auditoryworks.nearcast.discovery

data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
    val serviceType: String,
    val txtRecord: Map<String, String> = emptyMap()
)
