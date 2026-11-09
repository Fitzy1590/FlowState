package com.fitzy.flowstate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fitzy.flowstate.update.InstallResult
import com.fitzy.flowstate.update.UpdateInfo
import com.fitzy.flowstate.update.UpdateInstaller
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Downloads the APK to the cache, checks its SHA-256 against the release's `.sha256` file, then opens the system installer. */
class AndroidUpdateInstaller(private val context: Context, private val http: HttpClient) : UpdateInstaller {
    override val assetSuffix = ".apk"

    override suspend fun downloadAndVerify(info: UpdateInfo, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        runCatching {
            val expected = info.checksumUrl?.let { http.get(it).bodyAsText().trim().split(Regex("\\s+")).first().lowercase() } ?: return@runCatching null
            val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, "FlowState-${info.version}.apk")
            http.prepareGet(info.url).execute { resp ->
                val total = resp.contentLength() ?: info.sizeBytes
                val channel = resp.bodyAsChannel()
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) { file.delete(); null } else file.absolutePath
        }.getOrNull()
    }

    override fun install(path: String): InstallResult {
        val pm = context.packageManager
        // Android 8+ asks per app for permission to install packages; older versions use one global setting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.NEEDS_PERMISSION
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            InstallResult.STARTED
        }.getOrDefault(InstallResult.FAILED)
    }
}
