package com.auravox.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.auravox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/**
 * Self-update for the APK distributed outside a store.
 *
 * CI publishes the build to a rolling GitHub release under a fixed tag, next
 * to a small manifest naming its version. Both live at permanent, login-free
 * URLs, and because the tag is reused, neither the repository nor the release
 * list grows with every build.
 *
 * Everything here is guarded by [BuildConfig.DEBUG]: the install permission and
 * the FileProvider it needs only exist in the debug manifest, so a store build
 * carries none of it.
 */
object GithubUpdater {

    private const val MANIFEST_URL =
        "https://github.com/tenoriodouglas/auravox/releases/download/apk-latest/latest.json"

    /** Newer release, or null when this build is already current. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext null

        val body = runCatching { fetch(MANIFEST_URL) }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null

        val versionCode = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl")
        if (versionCode <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) return@withContext null

        UpdateInfo(
            versionCode = versionCode,
            versionName = json.optString("versionName", "?"),
            apkUrl = apkUrl,
            notes = json.optString("notes")
        )
    }

    /** Android 8 and up gates APK installs per app. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Downloads the APK and hands it to the system installer.
     *
     * `onProgress` gets 0..1 when the server declares a length, and -1 when it
     * does not. Returns false when the download failed; the install prompt
     * itself belongs to the system.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val apk = withContext(Dispatchers.IO) {
            runCatching { download(context, info.apkUrl, onProgress) }.getOrNull()
        } ?: return false

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One fixed name: a half-finished download from last time is
        // overwritten instead of filling the cache with dead APKs
        val out = File(dir, "auravox-update.apk")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            val total = conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(if (total > 0) (read.toFloat() / total) else -1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }
}
