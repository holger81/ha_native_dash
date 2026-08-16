package dev.holgerendt.hanative.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ha_native_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String
        get() = prefs.getString(KEY_URL, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_URL = "ha_url"
        private const val KEY_TOKEN = "ha_token"
    }
}
