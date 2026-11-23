package com.techoship.webviewbridge

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** One file being written into the shared Downloads folder. Used from a single IO thread. */
internal interface DownloadSink {
    fun write(bytes: ByteArray)

    /** Closes the file and makes it visible. Returns the final name, which may differ. */
    fun finish(): Pair<String, Uri>

    /** Closes and deletes the partial file. Never throws. */
    fun abort()
}

internal object DownloadStore {
    private const val BUFFER_BYTES = 64 * 1024

    /** Opens a new file in Downloads. Call off the main thread. */
    fun open(context: Context, name: String, mimeType: String): DownloadSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(context, name, mimeType)
        } else {
            LegacySink(context, name, mimeType)
        }

    /** Whether saving to Downloads needs a permission this app has not been granted (Android 8–9). */
    fun missingLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Android 10+: a pending MediaStore row, published when the last byte is written. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class MediaStoreSink(context: Context, private val name: String, mimeType: String) : DownloadSink {
        private val resolver = context.contentResolver
        private val uri: Uri
        private val out: OutputStream

        init {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore did not create the download")
            out = try {
                BufferedOutputStream(resolver.openOutputStream(uri) ?: throw IOException("Could not open the download"), BUFFER_BYTES)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        override fun write(bytes: ByteArray) = out.write(bytes)

        override fun finish(): Pair<String, Uri> {
            out.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: name
            return finalName to uri
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** Android 8–9: a plain file in the public Downloads folder, then a media scan. */
    @Suppress("DEPRECATION")
    private class LegacySink(private val context: Context, name: String, private val mimeType: String) : DownloadSink {
        private val file: File
        private val out: OutputStream

        init {
            if (missingLegacyPermission(context)) {
                throw BridgeException(
                    BridgeException.PERMISSION_REQUIRED,
                    "Saving to Downloads on Android 8–9 needs the WRITE_EXTERNAL_STORAGE permission",
                )
            }
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads folder is unavailable")
            file = File(directory, FileNames.unique(name) { File(directory, it).exists() })
            out = BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)
        }

        override fun write(bytes: ByteArray) = out.write(bytes)

        override fun finish(): Pair<String, Uri> {
            out.close()
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            return file.name to Uri.fromFile(file)
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { file.delete() }
        }
    }
}
