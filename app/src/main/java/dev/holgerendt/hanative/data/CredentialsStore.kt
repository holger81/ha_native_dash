package dev.holgerendt.hanative.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class CredentialsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var persistEnabled = false

    var baseUrl: String = readPref(KEY_URL)
        set(value) {
            field = value.trim().trimEnd('/')
            persist()
        }

    var token: String = readPref(KEY_TOKEN)
        set(value) {
            field = value.trim()
            persist()
        }

    var managementPin: String = readPref(KEY_PIN)
        set(value) {
            field = value.trim()
            persist()
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    init {
        migrateFromLegacy()
        restoreFromDocuments()
        persistEnabled = true
        if (isConfigured || managementPin.isNotBlank()) persist()
    }

    fun reloadFromExternal() {
        persistEnabled = false
        restoreFromDocuments()
        persistEnabled = true
        if (isConfigured || managementPin.isNotBlank()) persist()
    }

    fun clear() {
        persistEnabled = false
        baseUrl = ""
        token = ""
        managementPin = ""
        persistEnabled = true
        prefs.edit().clear().apply()
        runCatching { RecoverableFiles.delete(app, RecoverableFiles.CREDENTIALS_NAME) }
    }

    private fun readPref(key: String): String = prefs.getString(key, "")?.trim().orEmpty()

    private fun persist() {
        if (!persistEnabled) return
        prefs.edit()
            .putString(KEY_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_PIN, managementPin)
            .apply()
        persistRecoverable()
    }

    private fun persistRecoverable() {
        if (baseUrl.isBlank() && token.isBlank() && managementPin.isBlank()) return
        val body = JSONObject().apply {
            put("ha_url", baseUrl)
            put("ha_token", token)
            put("management_pin", managementPin)
        }.toString()
        runCatching {
            RecoverableFiles.write(
                app,
                RecoverableFiles.CREDENTIALS_NAME,
                "application/json",
                body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun restoreFromDocuments() {
        val raw = runCatching {
            RecoverableFiles.read(app, RecoverableFiles.CREDENTIALS_NAME)?.toString(Charsets.UTF_8)
        }.getOrNull() ?: return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (baseUrl.isBlank()) {
            val url = obj.optString("ha_url").trim().trimEnd('/')
            if (url.isNotBlank()) baseUrl = url
        }
        if (token.isBlank()) {
            val value = obj.optString("ha_token").trim()
            if (value.isNotBlank()) token = value
        }
        if (managementPin.isBlank()) {
            val pin = obj.optString("management_pin").trim()
            if (pin.isNotBlank()) managementPin = pin
        }
    }

    private fun migrateFromLegacy() {
        if (isConfigured) return
        readEncryptedLegacy()?.let { (url, accessToken) ->
            if (baseUrl.isBlank() && url.isNotBlank()) baseUrl = url
            if (token.isBlank() && accessToken.isNotBlank()) token = accessToken
        }
        if (isConfigured) return
        val legacy = app.getSharedPreferences(LEGACY_PLAIN_PREFS, Context.MODE_PRIVATE)
        if (baseUrl.isBlank()) baseUrl = legacy.getString(KEY_URL, "")?.trim().orEmpty()
        if (token.isBlank()) token = legacy.getString(KEY_TOKEN, "")?.trim().orEmpty()
    }

    private fun readEncryptedLegacy(): Pair<String, String>? {
        val encrypted = runCatching {
            EncryptedSharedPreferences.create(
                app,
                LEGACY_ENCRYPTED_PREFS,
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull() ?: return null
        val url = encrypted.getString(KEY_URL, "")?.trim().orEmpty()
        val accessToken = encrypted.getString(KEY_TOKEN, "")?.trim().orEmpty()
        if (url.isBlank() && accessToken.isBlank()) return null
        return url to accessToken
    }

    companion object {
        private const val PREFS_NAME = "ha_native_setup"
        private const val LEGACY_ENCRYPTED_PREFS = "ha_native_credentials"
        private const val LEGACY_PLAIN_PREFS = "ha_native_credentials_plain"
        private const val KEY_URL = "ha_url"
        private const val KEY_TOKEN = "ha_token"
        private const val KEY_PIN = "management_pin"

        val PIN_PATTERN = Regex("^\\d{4,8}$")

        fun pinError(pin: String): String? {
            val normalized = pin.trim()
            return when {
                normalized.isEmpty() -> "PIN cannot be empty"
                !PIN_PATTERN.matches(normalized) -> "PIN must be 4 to 8 digits"
                else -> null
            }
        }
    }
}
