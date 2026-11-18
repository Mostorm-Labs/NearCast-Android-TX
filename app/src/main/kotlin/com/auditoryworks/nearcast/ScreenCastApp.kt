package com.auditoryworks.nearcast

import android.app.Application

class ScreenCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.auditoryworks.nearcast.touchback.RemoteControlAccess.initialize(this)
    }
}
