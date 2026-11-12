package com.auravox.separation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps the separation models on disk, out of the APK.
 *
 * Thirty-eight megabytes of weights in the installer would be thirty-eight
 * megabytes everyone downloads, including everyone who only ever imports
 * instrumentals. So they are fetched the first time separation is asked for
 * and then kept. Nothing about them goes in the repository either: they are
 * published to a release tag the same way the app itself is, which is what
 * keeps a clone of the source small.
 *
 * Each file is checked against its hash before it is used. A model is a few
 * million multiplications the app performs on the user's behalf, and a
 * truncated download would not fail loudly — it would just separate badly.
 *
 * The weights are Spleeter's, by Deezer, stated MIT in the authors' paper, and
 * converted to ONNX by the sherpa-onnx project under Apache 2.0.
 */
object ModelStore {

    data class Model(val name: String, val sha256: String, val bytes: Long)

    val VOCALS = Model(
        "vocals.fp16.onnx",
        "24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668",
        19_681_017L
    )
    val ACCOMPANIMENT = Model(
        "accompaniment.fp16.onnx",
        "d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0",
        19_681_024L
    )

    private const val BASE =
        "https://github.com/tenoriodouglas/auravox/releases/download/models-latest"

    val totalBytes: Long get() = VOCALS.bytes + ACCOMPANIMENT.bytes

    fun directory(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, model: Model): File = File(directory(context), model.name)

    /** True when both models are present and intact. */
    fun ready(context: Context): Boolean =
        listOf(VOCALS, ACCOMPANIMENT).all { present(context, it) }

    private fun present(context: Context, model: Model): Boolean {
        val f = fileFor(context, model)
        return f.exists() && f.length() == model.bytes
    }

    /**
     * Fetches whatever is missing.
     *
     * [onProgress] reports 0..1 across both files together, so the caller can
     * show one bar rather than two. Returns null on success, or a reason.
     */
    suspend fun ensure(
        context: Context,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val wanted = listOf(VOCALS, ACCOMPANIMENT).filterNot { present(context, it) }
        if (wanted.isEmpty()) return@withContext null

        val total = wanted.sumOf { it.bytes }
        var done = 0L

        for (model in wanted) {
            val target = fileFor(context, model)
            val partial = File(target.parentFile, "${model.name}.part")
            val error = download("$BASE/${model.name}", partial) { written ->
                onProgress(((done + written).toFloat() / total).coerceIn(0f, 1f))
            }
            if (error != null) {
                partial.delete()
                return@withContext error
            }

            val digest = sha256(partial)
            if (!digest.equals(model.sha256, ignoreCase = true)) {
                partial.delete()
                return@withContext "O modelo baixado não confere. Tente de novo."
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext "Não foi possível guardar o modelo."
            }
            done += model.bytes
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        null
    }

    /** Frees the space. The models are fetched again when next needed. */
    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun download(url: String, target: File, onBytes: (Long) -> Unit): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return "O servidor respondeu ${connection.responseCode}."
            }
            connection.inputStream.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
            }
            return null
        } catch (e: Exception) {
            return e.message ?: "Falha ao baixar o modelo."
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
