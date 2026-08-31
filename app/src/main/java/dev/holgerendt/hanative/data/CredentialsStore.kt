package dev.holgerendt.hanative.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class CredentialsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = createSecurePrefs()

    private fun createSecurePrefs(): SharedPreferences {
        val secure = runCatching {
            EncryptedSharedPreferences.create(
                app,
                SECURE_PREFS_NAME,
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull() ?: return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migratePlaintextInto(secure)
        return secure
    }

    private fun migratePlaintextInto(secure: SharedPreferences) {
        val plain = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (plain.all.isEmpty()) return
        val edit = secure.edit()
        for ((key, value) in plain.all) {
            if (secure.contains(key)) continue
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                else -> continue
            }
        }
        edit.commit()
        runCatching { app.deleteSharedPreferences(PREFS_NAME) }
    }

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

    var go2rtcUrl: String = readPref(KEY_GO2RTC_URL)
        set(value) {
            field = value.trim().trimEnd('/')
            persist()
        }

    /** Null means follow Lovelace week-planner calendars; empty means none. */
    var subscribedCalendars: List<String>? = null
        set(value) {
            field = value?.map { it.trim() }?.filter { it.startsWith("calendar.") }?.distinct()
            persist()
        }

    /** Seconds of idle time before the panel blanks. 0 keeps the screen on. */
    var screenTimeoutSeconds: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_SCREEN_TIMEOUT_SECONDS)
            persist()
        }

    /** HA entity to turn off/on when the wall sleeps (e.g. switch.uc_display). Empty = app overlay only. */
    private var displayOffEntityBacking = readPref(KEY_DISPLAY_OFF)
    var displayOffEntity: String
        get() = displayOffEntityBacking
        set(value) {
            displayOffEntityBacking = normalizeEntityId(value)
            persist()
        }

    /** HA number/light entity for panel brightness (e.g. number.uc_display_brightness). */
    private var displayBrightnessEntityBacking = readPref(KEY_DISPLAY_BRIGHTNESS)
    var displayBrightnessEntity: String
        get() = displayBrightnessEntityBacking
        set(value) {
            displayBrightnessEntityBacking = normalizeEntityId(value)
            persist()
        }

    /** HA illuminance sensor that drives automatic panel brightness when awake. */
    private var displayIlluminanceEntityBacking = readPref(KEY_DISPLAY_ILLUMINANCE)
    var displayIlluminanceEntity: String
        get() = displayIlluminanceEntityBacking
        set(value) {
            displayIlluminanceEntityBacking = normalizeEntityId(value)
            persist()
        }

    /** Last Music Assistant media_player selected on the wall player. */
    private var musicPlayerEntityBacking = readPref(KEY_MUSIC_PLAYER)
    var musicPlayerEntity: String
        get() = musicPlayerEntityBacking
        set(value) {
            musicPlayerEntityBacking = normalizeEntityId(value)
            persist()
        }

    private var pinValue: String = readPref(KEY_PIN)

    var managementPin: String
        get() = pinValue
        set(value) = writePin(value, generated = false)

    val isGeneratedPin: Boolean
        get() = generatedThisProcess && managementPin.isNotBlank()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    private val timeoutFromPrefs = prefs.contains(KEY_TIMEOUT_SECONDS) || prefs.contains(KEY_TIMEOUT_MINUTES_LEGACY)

    init {
        screenTimeoutSeconds = when {
            prefs.contains(KEY_TIMEOUT_SECONDS) ->
                prefs.getInt(KEY_TIMEOUT_SECONDS, 0).coerceIn(0, MAX_SCREEN_TIMEOUT_SECONDS)
            prefs.contains(KEY_TIMEOUT_MINUTES_LEGACY) ->
                prefs.getInt(KEY_TIMEOUT_MINUTES_LEGACY, 0).coerceIn(0, 180) * 60
            else -> 0
        }
        migrateFromLegacy()
        restoreFromDocuments()
        if (!prefs.contains(KEY_DISPLAY_OFF) && displayOffEntityBacking.isBlank()) {
            displayOffEntityBacking = DEFAULT_DISPLAY_OFF_ENTITY
        }
        if (!prefs.contains(KEY_DISPLAY_BRIGHTNESS) && displayBrightnessEntityBacking.isBlank()) {
            displayBrightnessEntityBacking = DEFAULT_DISPLAY_BRIGHTNESS_ENTITY
        }
        if (!prefs.contains(KEY_DISPLAY_ILLUMINANCE) && displayIlluminanceEntityBacking.isBlank()) {
            displayIlluminanceEntityBacking = DEFAULT_DISPLAY_ILLUMINANCE_ENTITY
        }
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
        // Prefer a PIN already in Documents over a fresh random one (e.g. after storage grant).
        restoreFromDocuments(overwriteGeneratedPin = true)
        return managementPin
    }

    fun reloadFromExternal() {
        persistEnabled = false
        restoreFromDocuments(overwriteGeneratedPin = generatedThisProcess)
        persistEnabled = true
        if (isConfigured || managementPin.isNotBlank()) persist()
    }

    /** Drops a stale Documents PIN so a freshly saved or generated PIN wins. */
    fun commitPinToRecovery() {
        persist()
    }

    fun clear() {
        persistEnabled = false
        baseUrl = ""
        token = ""
        managementPin = ""
        generatedThisProcess = false
        persistEnabled = true
        prefs.edit().clear().apply()
        runCatching { app.deleteSharedPreferences(PREFS_NAME) }
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
            .putString(KEY_GO2RTC_URL, go2rtcUrl)
            .putString(KEY_PIN, managementPin)
            .putInt(KEY_TIMEOUT_SECONDS, screenTimeoutSeconds)
            .putString(KEY_DISPLAY_OFF, displayOffEntity)
            .putString(KEY_DISPLAY_BRIGHTNESS, displayBrightnessEntity)
            .putString(KEY_DISPLAY_ILLUMINANCE, displayIlluminanceEntity)
            .putString(KEY_MUSIC_PLAYER, musicPlayerEntity)
            .apply()
        persistRecoverable()
    }

    private fun persistRecoverable() {
        val existing = readRecoverableObject()
        val url = baseUrl.ifBlank { existing?.optString("ha_url").orEmpty() }.trim().trimEnd('/')
        val accessToken = token.ifBlank { existing?.optString("ha_token").orEmpty() }.trim()
        val existingPin = existing?.optString("management_pin").orEmpty().trim()
        val pin = managementPin.ifBlank { existingPin }
        if (url.isBlank() && accessToken.isBlank() && pin.isBlank()) return
        if (existing == null && url.isBlank() && accessToken.isBlank() &&
            RecoverableFiles.exists(app, RecoverableFiles.CREDENTIALS_NAME)
        ) {
            // PIN-only and credentials.json exists but was unread: do not overwrite.
            return
        }
        val body = JSONObject().apply {
            put("ha_url", url)
            put("ha_token", accessToken)
            put("go2rtc_url", go2rtcUrl)
            put("management_pin", pin)
            put("screen_timeout_seconds", screenTimeoutSeconds)
            if (displayOffEntity.isNotBlank()) put("display_off_entity", displayOffEntity)
            if (displayBrightnessEntity.isNotBlank()) put("display_brightness_entity", displayBrightnessEntity)
            if (displayIlluminanceEntity.isNotBlank()) put("display_illuminance_entity", displayIlluminanceEntity)
            if (musicPlayerEntity.isNotBlank()) put("music_player_entity", musicPlayerEntity)
            val calendars = subscribedCalendars ?: readCalendarList(existing)
            if (calendars != null) {
                put("subscribed_calendars", JSONArray(calendars))
            }
        }.toString()
        // Seal with ANDROID_ID-derived AES-GCM so other apps can't read Documents/HA Native.
        // Legacy plaintext files are still read and re-sealed on the next persist.
        runCatching {
            SecureRecovery.writeSealed(
                app,
                RecoverableFiles.CREDENTIALS_NAME,
                "application/json",
                SecureRecovery.CREDENTIALS_MAGIC,
                body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun readCalendarList(obj: JSONObject?): List<String>? {
        if (obj == null || !obj.has("subscribed_calendars") || obj.isNull("subscribed_calendars")) return null
        val arr = obj.optJSONArray("subscribed_calendars") ?: return null
        return (0 until arr.length()).mapNotNull { index ->
            arr.optString(index).trim().takeIf { it.startsWith("calendar.") }
        }.distinct()
    }

    private fun readRecoverableObject(): JSONObject? {
        val raw = runCatching {
            SecureRecovery.readRaw(app, RecoverableFiles.CREDENTIALS_NAME)
        }.getOrNull() ?: return null
        if (SecureRecovery.looksSealed(raw, SecureRecovery.CREDENTIALS_MAGIC)) {
            val decrypted = SecureRecovery.decryptIfSealed(app, SecureRecovery.CREDENTIALS_MAGIC, raw)
            if (decrypted != null) {
                return runCatching { JSONObject(decrypted.toString(Charsets.UTF_8)) }.getOrNull()
            }
            // Unreadable after UniFi re-sign / ANDROID_ID change — remove so setup can rewrite a new seal.
            runCatching { RecoverableFiles.delete(app, RecoverableFiles.CREDENTIALS_NAME) }
            return null
        }
        // Legacy plaintext recovery file (pre-seal or temporary revert).
        return runCatching { JSONObject(raw.toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun restoreFromDocuments(overwriteGeneratedPin: Boolean = false) {
        val obj = readRecoverableObject() ?: return
        if (baseUrl.isBlank()) {
            val url = obj.optString("ha_url").trim().trimEnd('/')
            if (url.isNotBlank()) baseUrl = url
        }
        if (go2rtcUrl.isBlank()) {
            val url = obj.optString("go2rtc_url").trim().trimEnd('/')
            if (url.isNotBlank()) go2rtcUrl = url
        }
        if (token.isBlank()) {
            val value = obj.optString("ha_token").trim()
            if (value.isNotBlank()) token = value
        }
        if (subscribedCalendars == null) {
            subscribedCalendars = readCalendarList(obj)
        }
        if (!timeoutFromPrefs) {
            when {
                obj.has("screen_timeout_seconds") ->
                    screenTimeoutSeconds = obj.optInt("screen_timeout_seconds", 0)
                        .coerceIn(0, MAX_SCREEN_TIMEOUT_SECONDS)
                obj.has("screen_timeout_minutes") ->
                    screenTimeoutSeconds = obj.optInt("screen_timeout_minutes", 0)
                        .coerceIn(0, 180) * 60
            }
        }
        if (!prefs.contains(KEY_DISPLAY_OFF) && obj.has("display_off_entity")) {
            displayOffEntity = normalizeEntityId(obj.optString("display_off_entity"))
        }
        if (!prefs.contains(KEY_DISPLAY_BRIGHTNESS) && obj.has("display_brightness_entity")) {
            displayBrightnessEntity = normalizeEntityId(obj.optString("display_brightness_entity"))
        }
        if (!prefs.contains(KEY_DISPLAY_ILLUMINANCE) && obj.has("display_illuminance_entity")) {
            displayIlluminanceEntity = normalizeEntityId(obj.optString("display_illuminance_entity"))
        }
        if (musicPlayerEntityBacking.isBlank() && obj.has("music_player_entity")) {
            musicPlayerEntity = normalizeEntityId(obj.optString("music_player_entity"))
        }
        val pin = obj.optString("management_pin").trim()
        val prefsPin = readPref(KEY_PIN)
        val takeRestoredPin = PIN_PATTERN.matches(pin) &&
            prefsPin.isBlank() &&
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
        private const val SECURE_PREFS_NAME = "ha_native_setup_secure"
        private const val LEGACY_ENCRYPTED_PREFS = "ha_native_credentials"
        private const val LEGACY_PLAIN_PREFS = "ha_native_credentials_plain"
        private const val KEY_URL = "ha_url"
        private const val KEY_TOKEN = "ha_token"
        private const val KEY_GO2RTC_URL = "go2rtc_url"
        private const val KEY_PIN = "management_pin"
        private const val KEY_TIMEOUT_SECONDS = "screen_timeout_seconds"
        private const val KEY_TIMEOUT_MINUTES_LEGACY = "screen_timeout_minutes"
        private const val KEY_DISPLAY_OFF = "display_off_entity"
        private const val KEY_DISPLAY_BRIGHTNESS = "display_brightness_entity"
        private const val KEY_DISPLAY_ILLUMINANCE = "display_illuminance_entity"
        private const val KEY_MUSIC_PLAYER = "music_player_entity"
        const val MAX_SCREEN_TIMEOUT_SECONDS = 86_400
        const val DEFAULT_DISPLAY_OFF_ENTITY = "switch.uc_display"
        const val DEFAULT_DISPLAY_BRIGHTNESS_ENTITY = "number.uc_display_brightness"
        const val DEFAULT_DISPLAY_ILLUMINANCE_ENTITY = "sensor.secondary_living_room_switch_illuminance"
        private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

        fun normalizeEntityId(raw: String): String = raw.trim().lowercase()

        fun entityIdError(raw: String): String? {
            val id = normalizeEntityId(raw)
            return when {
                id.isEmpty() -> null
                !ENTITY_ID.matches(id) -> "Use domain.name (e.g. switch.uc_display)"
                else -> null
            }
        }

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
