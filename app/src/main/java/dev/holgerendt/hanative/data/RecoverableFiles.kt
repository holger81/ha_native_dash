package dev.holgerendt.hanative.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import dev.holgerendt.hanative.PanelConfig
import java.io.File

/**
 * Files under public Documents that remain after uninstall (unlike app-specific
 * dirs and EncryptedSharedPreferences / Keystore). Readable by other apps; keep
 * contents off logs and rely on the management PIN for the admin surface.
 */
internal object RecoverableFiles {
    val DIR_NAME: String get() = PanelConfig.RECOVERY_DIR
    const val CREDENTIALS_NAME = "credentials.json"
    const val TLS_NAME = "management.p12"
    /** Album Flux/local pads — survives uninstall like credentials. */
    const val OUTPAINT_CACHE_SUBDIR = "outpaint_cache"

    private val relativePath: String
        get() = "${Environment.DIRECTORY_DOCUMENTS}/$DIR_NAME"

    @Suppress("DEPRECATION")
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)

    /**
     * Prefer Documents so outpaint JPEGs survive reinstall. Falls back to
     * [Context.getFilesDir] when public storage is unavailable.
     */
    fun outpaintCacheDir(context: Context): File {
        val public = File(publicDir(), OUTPAINT_CACHE_SUBDIR)
        val private = File(context.applicationContext.filesDir, OUTPAINT_CACHE_SUBDIR)
        val publicOk = runCatching {
            if (!public.exists()) public.mkdirs()
            public.isDirectory && (public.canWrite() || public.list() != null)
        }.getOrDefault(false)
        if (publicOk) {
            migrateOutpaintCache(from = private, to = public)
            return public
        }
        runCatching { private.mkdirs() }
        return private
    }

    private fun migrateOutpaintCache(from: File, to: File) {
        if (!from.isDirectory || from.absolutePath == to.absolutePath) return
        val sources = from.listFiles()?.filter { it.isFile } ?: return
        if (sources.isEmpty()) return
        runCatching { to.mkdirs() }
        for (src in sources) {
            val dest = File(to, src.name)
            if (dest.isFile && dest.length() > 0L) continue
            runCatching {
                src.copyTo(dest, overwrite = false)
            }
        }
        // Drop emptied private copies once public has the bytes.
        for (src in sources) {
            val dest = File(to, src.name)
            if (dest.isFile && dest.length() == src.length()) {
                runCatching { src.delete() }
            }
        }
        runCatching { if (from.list().isNullOrEmpty()) from.delete() }
    }

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
