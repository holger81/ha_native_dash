package dev.holgerendt.hanative

import android.app.Application
import dev.holgerendt.hanative.data.ApkInstaller
import dev.holgerendt.hanative.data.CrashLogger
import dev.holgerendt.hanative.data.ScreenCapture

class HaNativeApp : Application() {
    val screenCapture = ScreenCapture()
    lateinit var apkInstaller: ApkInstaller
        private set

    override fun onCreate() {
        super.onCreate()
        apkInstaller = ApkInstaller(this)
        CrashLogger.init(this)
    }
}
