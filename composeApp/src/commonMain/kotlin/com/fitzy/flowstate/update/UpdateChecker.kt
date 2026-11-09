package com.fitzy.flowstate.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer) =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        /** "v1.2.3", "1.2.3" and "1.2.3-beta" all read as 1.2.3. Anything else is null. */
        fun parse(text: String): SemVer? {
            val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val p = core.split(".")
            if (p.size != 3) return null
            return SemVer(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
        }
    }
}

@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val sizeBytes: Long,
    /** URL of the `<file>.sha256` asset. Updates without one are refused. */
    val checksumUrl: String?,
)

sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val info: UpdateInfo) : CheckResult
    data object Failed : CheckResult
}

/**
 * Reads the latest published (non-draft, non-prerelease) GitHub release and compares it with [currentVersion].
 * [assetSuffix] picks the installer for this platform (".apk" or ".msi"). Anonymous API calls are limited to
 * 60 an hour per IP, which the launch throttle keeps well under.
 */
class UpdateChecker(
    private val http: HttpClient,
    private val currentVersion: String,
    private val assetSuffix: String,
    private val repo: String = "fitzy1590/flowstate",
    private val baseUrl: String = "https://api.github.com",
) {
    suspend fun check(): CheckResult = runCatching {
        val resp = http.get("$baseUrl/repos/$repo/releases/latest") { header("Accept", "application/vnd.github+json") }
        if (resp.status.value == 404) return CheckResult.UpToDate // no release published yet
        if (!resp.status.isSuccess()) return CheckResult.Failed
        val release: ReleaseDto = resp.body()
        if (release.draft || release.prerelease) return CheckResult.UpToDate
        val latest = SemVer.parse(release.tag) ?: return CheckResult.Failed
        val current = SemVer.parse(currentVersion) ?: return CheckResult.Failed
        if (latest <= current) return CheckResult.UpToDate
        val asset = release.assets.firstOrNull { it.name.endsWith(assetSuffix, ignoreCase = true) } ?: return CheckResult.Failed
        val checksum = release.assets.firstOrNull { it.name == asset.name + ".sha256" }
        CheckResult.Available(UpdateInfo(release.tag.removePrefix("v"), release.body.orEmpty().trim(), asset.url, asset.size, checksum?.url))
    }.getOrDefault(CheckResult.Failed)
}

enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

/** Platform side of updating: download with a checksum check, then hand the file to the OS installer. */
interface UpdateInstaller {
    /** ".apk" on Android, ".msi" on Windows. */
    val assetSuffix: String

    /** Returns the local file path, or null if the download failed or did not match its checksum. */
    suspend fun downloadAndVerify(info: UpdateInfo, onProgress: (Float) -> Unit): String?

    fun install(path: String): InstallResult
}
