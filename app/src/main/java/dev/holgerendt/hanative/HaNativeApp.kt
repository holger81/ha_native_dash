package dev.holgerendt.hanative

import android.app.Application
import dev.holgerendt.hanative.data.ScreenCapture

class HaNativeApp : Application() {
    val screenCapture = ScreenCapture()
}
