package com.lansync.app

import android.app.Application
import com.lansync.app.data.FileLogger

class LanSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}
