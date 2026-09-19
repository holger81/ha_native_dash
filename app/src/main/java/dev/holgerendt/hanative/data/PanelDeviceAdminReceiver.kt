package dev.holgerendt.hanative.data

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Enables `adb shell dpm set-device-owner …/.data.PanelDeviceAdminReceiver`
 * so admin APK pushes can install without the on-screen Install confirm.
 */
class PanelDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // No-op: ownership alone is enough for silent PackageInstaller updates.
    }
}
