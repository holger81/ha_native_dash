package dev.holgerendt.hanative.data

import android.content.Context
import android.provider.Settings
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealing for the public recovery files in Documents/HA Native.
 *
 * The key is derived from Settings.Secure.ANDROID_ID, which is stable across
 * reinstalls of the same signed app (so restore-after-reinstall keeps working)
 * but differs per app, so other apps on the device cannot read the files.
 *
 * Blob layout: magic(4) || iv(12) || ciphertext+tag.
 */
internal object SecureRecovery {
    const val CREDENTIALS_MAGIC = "HNC1"
    const val TLS_MAGIC = "HNT1"

    private const val MAGIC_LENGTH = 4
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 100_000

    private val random = SecureRandom()

    fun readRaw(context: Context, name: String): ByteArray? =
        RecoverableFiles.read(context, name)

    fun writeSealed(context: Context, name: String, mime: String, magic: String, plaintext: ByteArray) {
        val sealed = seal(context, magic, plaintext) ?: return
        RecoverableFiles.write(context, name, mime, sealed)
    }

    /** Returns the plaintext if [raw] is a sealed blob for [magic]; null otherwise. */
    fun decryptIfSealed(context: Context, magic: String, raw: ByteArray): ByteArray? {
        if (raw.size < MAGIC_LENGTH + IV_LENGTH + TAG_BITS / 8) return null
        if (String(raw, 0, MAGIC_LENGTH, Charsets.US_ASCII) != magic) return null
        val key = keyFor(context, magic) ?: return null
        val iv = raw.copyOfRange(MAGIC_LENGTH, MAGIC_LENGTH + IV_LENGTH)
        val ciphertext = raw.copyOfRange(MAGIC_LENGTH + IV_LENGTH, raw.size)
        return runCatching {
            cipher("AES/GCM/NoPadding", Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext)
        }.getOrNull()
    }

    private fun seal(context: Context, magic: String, plaintext: ByteArray): ByteArray? {
        val key = keyFor(context, magic) ?: return null
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val ciphertext = runCatching {
            cipher("AES/GCM/NoPadding", Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)
        }.getOrNull() ?: return null
        return magic.toByteArray(Charsets.US_ASCII) + iv + ciphertext
    }

    private fun cipher(
        transformation: String,
        mode: Int,
        key: SecretKey,
        iv: ByteArray,
    ): Cipher = Cipher.getInstance(transformation).apply {
        init(mode, key, GCMParameterSpec(TAG_BITS, iv))
    }

    private fun keyFor(context: Context, purpose: String): SecretKey? {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: return null
        val salt = ("ha-native-dash/recovery/$purpose").toByteArray(Charsets.UTF_8)
        val spec: KeySpec = PBEKeySpec(deviceId.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return runCatching {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
                .let { SecretKeySpec(it.encoded, "AES") }
        }.getOrNull()
    }
}
