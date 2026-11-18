package com.auditoryworks.nearcast.touchback

enum class PermissionAction { NONE, SHOW_EXPLANATION, START_CAPTURE, SHOW_NOT_GRANTED }
class RemotePermissionFlow {
    private var explainedSession: String? = null
    private var settingsSession: String? = null
    private var resumeCapture = false
    fun start(session: String, enabled: Boolean, ready: Boolean): PermissionAction {
        if (enabled && !ready && explainedSession != session) {
            explainedSession = session
            return PermissionAction.SHOW_EXPLANATION
        }
        return PermissionAction.START_CAPTURE
    }
    fun skip(session: String): PermissionAction { explainedSession = session; cancel(); return PermissionAction.START_CAPTURE }
    fun openSettings(session: String?, resumeCapture: Boolean) { settingsSession = session; this.resumeCapture = resumeCapture }
    fun returnFromSettings(session: String?, ready: Boolean): PermissionAction {
        val shouldResume = resumeCapture && session != null && session == settingsSession
        cancel()
        return if (!shouldResume) PermissionAction.NONE else if (ready) PermissionAction.START_CAPTURE else PermissionAction.SHOW_NOT_GRANTED
    }
    fun cancel() { settingsSession = null; resumeCapture = false }
}
