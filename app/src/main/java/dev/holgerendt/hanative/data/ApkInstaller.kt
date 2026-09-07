package dev.holgerendt.hanative.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.holgerendt.hanative.HaNativeApp
import java.io.File
import java.security.MessageDigest

data class ApkInstallState(
    val status: String = "idle",
    val message: String? = null,
    val incomingVersion: String? = null,
)

class ApkInstaller(private val app: Context) {
    @Volatile
    var state: ApkInstallState = ApkInstallState()
        private set

    fun install(apkFile: File): Result<Unit> {
        synchronized(this) {
            if (state.status == "installing" || state.status == "needs_confirm") {
                return Result.failure(IllegalStateException("An install is already in progress"))
            }
            state = ApkInstallState("installing", "Checking APK…")
        }
        return runCatching {
            val pm = app.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                Handler(Looper.getMainLooper()).post { app.startActivity(settings) }
                error("Allow “Install unknown apps” for this panel, then upload again.")
            }
            val incoming = packageInfo(apkFile) ?: error("Not a valid APK")
            if (incoming.packageName != app.packageName) {
                error("APK package ${incoming.packageName} does not match this panel (${app.packageName})")
            }
            val incomingCode = versionCodeOf(incoming)
            val installed = pm.getPackageInfo(app.packageName, 0)
            val installedCode = versionCodeOf(installed)
            if (incomingCode <= installedCode) {
                error("versionCode $incomingCode is not higher than installed $installedCode")
            }
            val incomingSigs = signingFingerprints(incoming)
            val installedSigs = signingFingerprints(installedInfo(pm))
            if (incomingSigs.isEmpty() || installedSigs.isEmpty() || incomingSigs.intersect(installedSigs).isEmpty()) {
                error("APK is not signed with the same key as the installed app")
            }
            val versionLabel = incoming.versionName?.let { "$it ($incomingCode)" } ?: incomingCode.toString()
            state = ApkInstallState("installing", "Installing $versionLabel…", versionLabel)
            commitSession(apkFile)
        }.onFailure { error ->
            state = ApkInstallState("error", error.message ?: "Install failed")
        }
    }

    fun markNeedsConfirm() {
        val current = state
        state = ApkInstallState(
            status = "needs_confirm",
            message = "Confirm Install on the tablet",
            incomingVersion = current.incomingVersion,
        )
    }

    fun onSessionStatus(status: Int, message: String?) {
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                ApkInstallState("success", "Installed. The app will reopen.", state.incomingVersion)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                ApkInstallState("error", "Install cancelled")
            else ->
                ApkInstallState("error", message?.takeIf { it.isNotBlank() } ?: "Install failed")
        }
    }

    private fun commitSession(apkFile: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("app.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(app, ApkInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, sessionId, callback, flags)
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            runCatching { session.abandon() }
            throw error
        } finally {
            runCatching { session.close() }
        }
    }

    private fun packageInfo(apk: File): PackageInfo? {
        val path = apk.absolutePath
        val info = app.packageManager.getPackageArchiveInfo(path, signingFlags()) ?: return null
        info.applicationInfo?.apply {
            sourceDir = path
            publicSourceDir = path
        }
        return info
    }

    private fun installedInfo(pm: PackageManager): PackageInfo =
        pm.getPackageInfo(app.packageName, signingFlags())

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int {
        var flags = PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun signingFingerprints(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: info.signatures
        } else {
            info.signatures
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            digest.reset()
            digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    companion object {
        const val ACTION_INSTALL_STATUS = "dev.holgerendt.hanative.INSTALL_STATUS"
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val installer = (context.applicationContext as? HaNativeApp)?.apkInstaller ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) runCatching { context.startActivity(confirm) }
            installer.markNeedsConfirm()
        } else {
            installer.onSessionStatus(status, message)
        }
    }
}
