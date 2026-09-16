package com.auravox.karaoke

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gets a finished take out of the app.
 *
 * Sharing goes through a FileProvider: handing another app a `file://` URI
 * throws FileUriExposedException on anything past Android 7, and the share
 * sheet dies with it.
 */
object Exporter {

    fun contentUri(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    fun shareIntent(context: Context, path: String, title: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, contentUri(context, path))
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Copies the take into Music/AuraVox so the phone's own music apps find it.
     *
     * Returns null when the copy failed. On Android 10 and up this needs no
     * permission at all. Below that the public folder needs a runtime grant
     * the app never asks for, so the copy may fail and the caller should point
     * the user at the share sheet instead.
     */
    fun exportToMusic(context: Context, path: String, displayName: String): Uri? {
        val source = File(path)
        if (!source.exists()) return null
        val name = sanitize(displayName)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, name)
        } else {
            exportToPublicDir(source, name)
        }
    }

    private fun exportViaMediaStore(context: Context, source: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AuraVox")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null

        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: return null
            // Clearing IS_PENDING is what makes the file visible to other apps
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun exportToPublicDir(source: File, name: String): Uri? = runCatching {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "AuraVox"
        ).apply { mkdirs() }
        val target = File(dir, "$name.wav")
        source.copyTo(target, overwrite = true)
        Uri.fromFile(target)
    }.getOrNull()

    /** Keeps the song title in the file name without letting it break the path. */
    private fun sanitize(raw: String): String {
        val cleaned = raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "-")
        return if (cleaned.isEmpty()) "auravox" else cleaned.take(60)
    }
}
