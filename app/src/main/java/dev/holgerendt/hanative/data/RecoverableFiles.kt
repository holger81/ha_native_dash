package dev.holgerendt.hanative.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Files under public Documents that remain after uninstall (unlike app-specific
 * dirs and EncryptedSharedPreferences / Keystore). Readable by other apps; keep
 * contents off logs and rely on the management PIN for the admin surface.
 */
internal object RecoverableFiles {
    const val DIR_NAME = "HA Native"
    const val CREDENTIALS_NAME = "credentials.json"
    const val TLS_NAME = "management.p12"

    private val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$DIR_NAME"

    @Suppress("DEPRECATION")
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)

    fun write(context: Context, name: String, mime: String, bytes: ByteArray) {
        val viaFile = runCatching {
            val dir = publicDir()
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.isFile && file.length() == bytes.size.toLong()
        }.getOrDefault(false)
        if (!viaFile) writeMediaStore(context, name, mime, bytes)
    }

    fun read(context: Context, name: String): ByteArray? {
        runCatching {
            val file = File(publicDir(), name)
            if (file.isFile && file.canRead() && file.length() > 0L) return file.readBytes()
        }
        return readMediaStore(context, name)
    }

    fun exists(context: Context, name: String): Boolean {
        val onDisk = runCatching {
            val file = File(publicDir(), name)
            file.isFile && file.length() > 0L
        }.getOrDefault(false)
        return onDisk || mediaUri(context, name) != null
    }

    fun delete(context: Context, name: String) {
        runCatching { File(publicDir(), name).takeIf { it.isFile }?.delete() }
        runCatching { deleteMediaStore(context, name) }
    }

    private fun writeMediaStore(context: Context, name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val existing = mediaUri(context, name)
        val uri = existing ?: resolver.insert(
            MediaStore.Files.getContentUri("external"),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: return
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            if (existing == null) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
    }

    private fun readMediaStore(context: Context, name: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = mediaUri(context, name) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun deleteMediaStore(context: Context, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val uri = mediaUri(context, name) ?: return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun mediaUri(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(name, "%$DIR_NAME%")
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        }
        return null
    }
}
