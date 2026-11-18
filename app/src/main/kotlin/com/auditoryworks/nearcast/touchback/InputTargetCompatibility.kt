package com.auditoryworks.nearcast.touchback

import org.json.JSONObject

/** Product binding validation remains strict even when the media connection works. */
internal fun inputTargetFailure(data: JSONObject?): String? = when {
    data?.opt("version") != 1 -> "TARGET_DESCRIPTOR_INVALID"
    data.opt("contractFingerprint") != AxtpContract.FINGERPRINT -> "TARGET_CONTRACT_MISMATCH"
    !isText(data.opt("runtimeInstanceId")) || data.optJSONObject("target")?.let(::validTarget) != true -> "TARGET_DESCRIPTOR_INVALID"
    else -> null
}
