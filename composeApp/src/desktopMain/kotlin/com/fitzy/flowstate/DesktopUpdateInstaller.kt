package com.fitzy.flowstate

import com.fitzy.flowstate.update.InstallResult
import com.fitzy.flowstate.update.UpdateInfo
import com.fitzy.flowstate.update.UpdateInstaller
import io.ktor.client.HttpClient
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

/** Windows: downloads the .msi, checks its SHA-256, then starts `msiexec` on it. */
class DesktopUpdateInstaller(private val http: HttpClient, private val tempDir: File) : UpdateInstaller {
    override val assetSuffix = ".msi"

    override suspend fun downloadAndVerify(info: UpdateInfo, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        runCatching {
            val expected = info.checksumUrl?.let { http.get(it).bodyAsText().trim().split(Regex("\\s+")).first().lowercase() } ?: return@runCatching null
            tempDir.mkdirs()
            val file = File(tempDir, "FlowState-${info.version}.msi")
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
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return InstallResult.FAILED
        // /passive shows a progress bar and asks nothing; Windows still asks for administrator permission.
        return runCatching { ProcessBuilder("msiexec", "/i", path, "/passive", "/norestart").start(); InstallResult.STARTED }.getOrDefault(InstallResult.FAILED)
    }
}
