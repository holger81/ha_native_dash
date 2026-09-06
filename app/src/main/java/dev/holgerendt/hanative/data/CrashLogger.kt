package dev.holgerendt.hanative.data

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.DropBoxManager
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val PREFS_NAME = "crash_logger_prefs"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val DOCS_DIR = "Documents/HA Native"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var latestCrash: String? = null
        private set

    fun init(app: Application) {
        appContext = app.applicationContext
        latestCrash = loadSavedCrash(app) ?: checkDropBoxForCrash(app)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(app, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to record uncaught exception", t)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun clearCrash() {
        latestCrash = null
        val context = appContext ?: return
        runCatching { File(context.filesDir, CRASH_FILE_NAME).delete() }
        runCatching {
            val extDir = File(Environment.getExternalStorageDirectory(), DOCS_DIR)
            File(extDir, CRASH_FILE_NAME).delete()
        }
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }

    private fun recordCrash(app: Application, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val pInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val versionName = pInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: -1L
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toLong() ?: -1L
        }

        val stackTrace = Log.getStackTraceString(throwable)
        val report = buildString {
            appendLine("=== GREATROOM WALL CRASH REPORT ===")
            appendLine("Timestamp: $timestamp")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Thread: ${thread.name} (id=${thread.id}, isDaemon=${thread.isDaemon})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Exception: ${throwable::class.java.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("Stacktrace:")
            appendLine(stackTrace.trimEnd())
            var cause = throwable.cause
            while (cause != null) {
                appendLine()
                appendLine("Caused by: ${cause::class.java.name}: ${cause.message}")
                appendLine(Log.getStackTraceString(cause).trimEnd())
                cause = cause.cause
            }
            appendLine("====================================")
        }

        latestCrash = report
        Log.e(TAG, report)

        // 1. Write internal filesDir
        runCatching {
            File(app.filesDir, CRASH_FILE_NAME).writeText(report)
        }

        // 2. Write external Documents/HA Native if accessible
        runCatching {
            val extDir = File(Environment.getExternalStorageDirectory(), DOCS_DIR)
            if (extDir.exists() || extDir.mkdirs()) {
                File(extDir, CRASH_FILE_NAME).writeText(report)
            }
        }

        // 3. Write SharedPreferences as fallback
        runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, report)
                .commit()
        }
    }

    private fun loadSavedCrash(context: Context): String? {
        // Try internal file
        runCatching {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                return file.readText().takeIf { it.isNotBlank() }
            }
        }

        // Try external Documents/HA Native
        runCatching {
            val file = File(Environment.getExternalStorageDirectory(), "$DOCS_DIR/$CRASH_FILE_NAME")
            if (file.exists() && file.length() > 0) {
                return file.readText().takeIf { it.isNotBlank() }
            }
        }

        // Try SharedPreferences
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_LAST_CRASH, null)
            if (!saved.isNullOrBlank()) return saved
        }

        return null
    }

    private fun checkDropBoxForCrash(context: Context): String? {
        return runCatching {
            val dropbox = context.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager ?: return null
            // Check for any data_app_crash within the last 24 hours
            val since = System.currentTimeMillis() - 24 * 3600 * 1000L
            var entry = dropbox.getNextEntry("data_app_crash", since)
            var foundText: String? = null
            while (entry != null) {
                val text = entry.getText(4096)
                if (text != null && text.contains(context.packageName)) {
                    foundText = "=== DROPBOX CRASH LOG ===\nTime: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.timeMillis))}\n$text"
                }
                entry.close()
                entry = dropbox.getNextEntry("data_app_crash", entry.timeMillis)
            }
            foundText
        }.getOrNull()
    }
}
