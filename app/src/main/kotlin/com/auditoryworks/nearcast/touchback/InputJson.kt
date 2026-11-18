package com.auditoryworks.nearcast.touchback

import org.json.JSONArray
import org.json.JSONObject

internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
internal fun isText(value: Any?) = value is String && value.isNotEmpty() && value.length <= 128
internal fun JSONObject.uint(name: String, min: Long = 0, max: Long = 0xffffffffL): Long {
    val value = opt(name)
    requireInput(value is Number, "Numeric $name required")
    val n = (value as Number).toLong()
    requireInput(n in min..max && value.toDouble() == n.toDouble(), "Integer $name range")
    return n
}
internal fun JSONObject.strings(name: String): List<String> {
    val array = opt(name)
    requireInput(array is JSONArray, "$name array required")
    return (0 until (array as JSONArray).length()).map { index ->
        val item = array.get(index)
        requireInput(item is String, "$name string required")
        item as String
    }
}
internal fun validTarget(target: JSONObject): Boolean {
    val fields = setOf("sessionId", "sessionGeneration", "slotId", "bindingGeneration", "lifecycleGeneration")
    return target.keys().asSequence().toSet() == fields && fields.all { isText(target.opt(it)) } &&
        listOf("sessionGeneration", "bindingGeneration", "lifecycleGeneration").all {
            val value = target.getString(it)
            value.matches(Regex("[1-9][0-9]*")) && value.toULongOrNull() != null
        }
}
