package com.auditoryworks.nearcast.touchback

import org.json.JSONArray
import org.json.JSONObject

/** Product policy: consume right-button state for RX compatibility, inject only left. */
internal object AndroidMousePolicy {
    const val ACCEPTED_BUTTONS_MASK = 3L
    const val INJECTED_BUTTONS_MASK = 1L

    fun capability(): JSONObject = JSONObject().put("buttonsMask", ACCEPTED_BUTTONS_MASK)
        .put("modifierMask", 0).put("allowChords", false)
        .put("wheelUnits", JSONArray(listOf("detent120"))).put("continuousScroll", false)
}
