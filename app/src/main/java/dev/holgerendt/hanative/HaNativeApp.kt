package dev.holgerendt.hanative

import android.app.Application
import dev.holgerendt.hanative.data.CrashLogger
import dev.holgerendt.hanative.data.ScreenCapture

class HaNativeApp : Application() {
    val screenCapture = ScreenCapture()

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
