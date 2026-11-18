package com.auditoryworks.nearcast.discovery

/** Consume a completed code request only while the verified route and saved pin still match. */
fun ReceiverActionState.localCodeReceiver(current: ReceiverDevice?, savedPin: String?): ReceiverDevice? {
    val requested = device ?: return null
    val live = current ?: return null
    if (phase != ReceiverPhase.SHOWN || candidate != null || fingerprint == null ||
        !validFingerprint(fingerprint) || savedPin != fingerprint ||
        live.routeKey != requested.routeKey || live.capabilities != requested.capabilities ||
        !live.selectable || live.localReady == false) return null
    return live
}
