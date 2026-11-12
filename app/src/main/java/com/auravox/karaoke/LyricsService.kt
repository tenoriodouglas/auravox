package com.auravox.karaoke

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds a song's synchronised lyric, from LRCLIB.
 *
 * LRCLIB is the only source that is free, needs no key for reading, carries
 * word timings as well as line timings, and is already what open-source music
 * players use. The alternatives are each disqualified: Musixmatch's synced
 * lyrics are behind a negotiated commercial plan, Genius does not license
 * lyrics through its API at all, and the endpoints inside NetEase and QQ Music
 * are undocumented and nobody's to grant.
 *
 * What is fetched here is a third party's copyrighted text and no one in the
 * chain grants rights to it, which is why this only ever asks on the user's
 * behalf, one song at a time, and never ships or redistributes a copy of the
 * database.
 */
class LyricsService(private val appVersion: String) {

    /** Identifies the client, which the service requires and rate-limits by. */
    private val userAgent = "AuraVox/$appVersion (https://github.com/tenoriodouglas/auravox)"

    sealed interface Outcome {
        /** Found, with [lines] ready to display. */
        data class Found(
            val id: Long,
            val trackName: String,
            val artistName: String,
            val lines: List<LyricLine>,
            val wordSynced: Boolean
        ) : Outcome

        /** The record says the track has no vocal to follow. */
        data object Instrumental : Outcome

        /** Nothing matched. The service may still index it later. */
        data object NotFound : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * Resolves a lyric, trying the narrowest query first.
     *
     * The exact lookup wants all four fields and matches the duration within a
     * couple of seconds, which is the query that gives the right take of a
     * song. Dropping the album widens it. The search is last because its
     * ranking is fuzzy and will put a live version or a B-side first, so its
     * results are re-ranked here against the duration before any is accepted.
     */
    suspend fun resolve(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long
    ): Outcome = withContext(Dispatchers.IO) {
        val cleanTitle = LyricsMatch.cleanTitle(title)
        val cleanArtist = LyricsMatch.cleanArtist(artist)
        if (cleanTitle.isBlank()) return@withContext Outcome.NotFound

        val seconds = if (durationMs > 0) durationMs / 1000.0 else 0.0

        if (cleanArtist.isNotBlank()) {
            exact(cleanTitle, cleanArtist, album, seconds)?.let { return@withContext it }
            if (!album.isNullOrBlank()) {
                exact(cleanTitle, cleanArtist, null, seconds)?.let { return@withContext it }
            }
        }

        search(cleanTitle, cleanArtist, seconds)
    }

    // --- the two queries ---

    private suspend fun exact(
        title: String, artist: String, album: String?, seconds: Double
    ): Outcome? {
        val params = buildString {
            append("track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (seconds >= 1.0) append("&duration=").append(seconds.toInt())
        }
        val body = get("$kBase/api/get?$params") ?: return null
        val record = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return record.toOutcome()
    }

    private suspend fun search(title: String, artist: String, seconds: Double): Outcome {
        val params = buildString {
            append("track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
        }
        val body = get("$kBase/api/search?$params") ?: return Outcome.NotFound
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return Outcome.NotFound
        if (array.length() == 0) return Outcome.NotFound

        var best: JSONObject? = null
        var bestScore = -Float.MAX_VALUE
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val s = LyricsMatch.score(
                wantTitle = title,
                wantArtist = artist,
                wantDurationSec = seconds,
                gotTitle = o.optString("trackName"),
                gotArtist = o.optString("artistName"),
                gotDurationSec = o.optDouble("duration", 0.0),
                hasSynced = !o.optString("syncedLyrics").isNullOrBlank() ||
                            o.optBoolean("hasSyncedLyrics"),
                hasWordSync = o.optBoolean("hasWordSync")
            )
            if (s > bestScore) { bestScore = s; best = o }
        }

        // A bad best is worse than none: an unrelated lyric scrolling under the
        // pitch lane reads as a broken app, not as a near miss
        if (best == null || bestScore < kMinScore) return Outcome.NotFound
        return best.toOutcome() ?: Outcome.NotFound
    }

    /**
     * Turns a record into something displayable, best format first.
     *
     * Word timings come from the Lyricsfile; the LRC in the same record only
     * carries line timings. Both are present on most records, so the ladder
     * costs nothing and gains the word-by-word fill when it is there.
     */
    private fun JSONObject.toOutcome(): Outcome? {
        if (optBoolean("instrumental")) return Outcome.Instrumental

        val id = optLong("id")
        val track = optString("trackName")
        val artist = optString("artistName")

        val lyricsfile = optString("lyricsfile").takeIf { it.isNotBlank() }
        if (lyricsfile != null) {
            LyricsFile.parse(lyricsfile)?.let {
                return Outcome.Found(id, track, artist, it, wordSynced = true)
            }
        }

        val synced = optString("syncedLyrics").takeIf { it.isNotBlank() }
        if (synced != null) {
            val parsed = Lrc.parse(synced).lines
            if (parsed.isNotEmpty()) {
                return Outcome.Found(
                    id, track, artist, parsed,
                    wordSynced = parsed.any { it.words.isNotEmpty() }
                )
            }
        }

        // Plain text with no timings cannot drive a karaoke screen
        return null
    }

    // --- transport ---

    /**
     * One GET, with the two failure modes this service actually has.
     *
     * A rate limit arrives as 429 with a Retry-After, and the docs warn that
     * ignoring it earns a ban, so it waits — with jitter, so a library being
     * scanned does not come back as a thundering herd. And the body is only
     * parsed as JSON when the service says it is JSON: a malformed query or an
     * edge-level rate limit comes back as plain text, which would otherwise
     * throw somewhere far from here.
     */
    private suspend fun get(url: String, attempt: Int = 0): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 12_000
            readTimeout = 12_000
        }
        try {
            val code = connection.responseCode

            if (code == 429 && attempt < kMaxRetries) {
                val after = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
                val jitter = (after * 300L * (1 + attempt)).coerceAtMost(4_000L)
                delay(after * 1000L + jitter)
                connection.disconnect()
                return get(url, attempt + 1)
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val type = connection.contentType ?: ""
            if (!type.contains("json", ignoreCase = true)) return null

            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val kBase = "https://lrclib.net"
        const val kMaxRetries = 2
        /** Below this the best candidate is not credibly the same song. */
        const val kMinScore = 3.0f
    }
}
