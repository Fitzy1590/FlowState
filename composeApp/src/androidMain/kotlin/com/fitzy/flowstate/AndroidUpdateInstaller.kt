package com.fitzy.flowstate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageInstaller
import android.widget.Toast
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

    /**
     * Installs through PackageInstaller. On Android 12+ it asks for no confirmation, which Android allows when this
     * app is the one that installed the current version (so the first update after a manual install still asks once).
     * Whenever Android does want a confirmation, [InstallStatusReceiver] shows it. If a session cannot be started,
     * it falls back to opening the APK with the system installer.
     */
    override fun install(path: String): InstallResult {
        // Android 8+ asks per app for permission to install packages; older versions use one global setting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.NEEDS_PERMISSION
        }
        return runCatching { commitSession(File(path)); InstallResult.STARTED }.getOrElse { openWithSystemInstaller(path) }
    }

    private fun commitSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("flowstate.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the system fills in the result, so it must be mutable
            )
            session.commit(status.intentSender)
        }
    }

    private fun openWithSystemInstaller(path: String): InstallResult = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        InstallResult.STARTED
    }.getOrDefault(InstallResult.FAILED)
}

/** Receives the installer's progress. When Android needs the user to confirm, it brings up that screen. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the new version replaces this one
            else -> Toast.makeText(context, "Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }
}
