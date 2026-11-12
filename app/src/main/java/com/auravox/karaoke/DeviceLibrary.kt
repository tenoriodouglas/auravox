package com.auravox.karaoke

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The music already on the phone.
 *
 * Importing used to mean the system file picker, one file at a time, with the
 * user hunting through folders for something they could already see in their
 * music app. This reads the same index the music app reads, so a song is
 * picked by its title.
 *
 * It only ever lists. Nothing here reads, copies or modifies a file: the song
 * is imported by its content URI, which is what the decoder already takes.
 */
object DeviceLibrary {

    data class Entry(
        val uri: Uri,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    ) {
        /** How a row reads when a file has no tags worth showing. */
        val subtitle: String
            get() = listOf(artist, album).filter { it.isNotBlank() && it != "<unknown>" }
                .joinToString(" · ")
    }

    /** Permission needed to read the index, which differs by version. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val columns = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
    )

    /**
     * Lists music, newest first, optionally filtered by a query.
     *
     * Ringtones, notifications and voice recordings are excluded: a karaoke
     * app offering a thirty-second alarm tone to sing over is noise in the
     * list. Anything under half a minute goes too, which catches the rest.
     */
    suspend fun list(
        context: Context,
        query: String = "",
        limit: Int = 500
    ): List<Entry> = withContext(Dispatchers.IO) {
        val selection = StringBuilder("${MediaStore.Audio.Media.IS_MUSIC} != 0")
        selection.append(" AND ${MediaStore.Audio.Media.DURATION} > 30000")
        val args = mutableListOf<String>()

        if (query.isNotBlank()) {
            val folded = "%${query.trim()}%"
            selection.append(
                " AND (${MediaStore.Audio.Media.TITLE} LIKE ?" +
                    " OR ${MediaStore.Audio.Media.ARTIST} LIKE ?" +
                    " OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)"
            )
            args += listOf(folded, folded, folded)
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val out = mutableListOf<Entry>()
        runCatching {
            context.contentResolver.query(
                collection, columns, selection.toString(), args.toTypedArray(),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext() && out.size < limit) {
                    out += Entry(
                        uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString()),
                        title = cursor.getString(title) ?: "",
                        artist = cursor.getString(artist).orEmpty(),
                        album = cursor.getString(album).orEmpty(),
                        durationMs = cursor.getLong(duration)
                    )
                }
            }
        }
        out
    }
}
