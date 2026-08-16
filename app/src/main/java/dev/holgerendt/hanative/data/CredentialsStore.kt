package dev.holgerendt.hanative.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class CredentialsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var persistEnabled = false
    private var generatedThisProcess = false

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

    private var pinValue: String = readPref(KEY_PIN)

    var managementPin: String
        get() = pinValue
        set(value) = writePin(value, generated = false)

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    init {
        migrateFromLegacy()
        restoreFromDocuments()
        persistEnabled = true
        if (isConfigured || managementPin.isNotBlank()) persist()
    }

    /**
     * Returns the stored PIN, restoring from Documents if needed.
     * Generates and persists one only when prefs and the recoverable file both lack a PIN.
     */
    fun adoptOrCreatePin(create: () -> String): String {
        if (managementPin.isNotBlank()) return managementPin
        restoreFromDocuments()
        if (managementPin.isNotBlank()) {
            persist()
            return managementPin
        }
        val created = create().trim()
        if (pinError(created) != null) return created
        writePin(created, generated = true)
        return managementPin
    }

    fun reloadFromExternal() {
        persistEnabled = false
        restoreFromDocuments(overwriteGeneratedPin = generatedThisProcess)
        persistEnabled = true
        if (isConfigured || managementPin.isNotBlank()) persist()
    }

    fun clear() {
        persistEnabled = false
        baseUrl = ""
        token = ""
        managementPin = ""
        generatedThisProcess = false
        persistEnabled = true
        prefs.edit().clear().apply()
        runCatching { RecoverableFiles.delete(app, RecoverableFiles.CREDENTIALS_NAME) }
    }

    private fun writePin(value: String, generated: Boolean) {
        pinValue = value.trim()
        generatedThisProcess = generated
        persist()
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
        val existing = readRecoverableObject()
        val url = baseUrl.ifBlank { existing?.optString("ha_url").orEmpty() }.trim().trimEnd('/')
        val accessToken = token.ifBlank { existing?.optString("ha_token").orEmpty() }.trim()
        val existingPin = existing?.optString("management_pin").orEmpty().trim()
        val pin = when {
            generatedThisProcess && PIN_PATTERN.matches(existingPin) -> existingPin
            managementPin.isNotBlank() -> managementPin
            else -> existingPin
        }
        if (url.isBlank() && accessToken.isBlank() && pin.isBlank()) return
        if (existing == null && url.isBlank() && accessToken.isBlank() &&
            RecoverableFiles.exists(app, RecoverableFiles.CREDENTIALS_NAME)
        ) {
            // PIN-only and credentials.json exists but was unread: do not overwrite.
            return
        }
        if (generatedThisProcess && PIN_PATTERN.matches(existingPin)) {
            pinValue = existingPin
            generatedThisProcess = false
            prefs.edit().putString(KEY_PIN, pinValue).apply()
        }
        val body = JSONObject().apply {
            put("ha_url", url)
            put("ha_token", accessToken)
            put("management_pin", pin)
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

    private fun readRecoverableObject(): JSONObject? {
        val raw = runCatching {
            RecoverableFiles.read(app, RecoverableFiles.CREDENTIALS_NAME)?.toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun restoreFromDocuments(overwriteGeneratedPin: Boolean = false) {
        val obj = readRecoverableObject() ?: return
        if (baseUrl.isBlank()) {
            val url = obj.optString("ha_url").trim().trimEnd('/')
            if (url.isNotBlank()) baseUrl = url
        }
        if (token.isBlank()) {
            val value = obj.optString("ha_token").trim()
            if (value.isNotBlank()) token = value
        }
        val pin = obj.optString("management_pin").trim()
        val takeRestoredPin = PIN_PATTERN.matches(pin) &&
            (managementPin.isBlank() || (overwriteGeneratedPin && generatedThisProcess))
        if (takeRestoredPin) {
            writePin(pin, generated = false)
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
