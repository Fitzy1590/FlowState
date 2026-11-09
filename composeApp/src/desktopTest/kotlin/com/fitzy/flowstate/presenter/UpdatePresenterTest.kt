package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.update.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class UpdatePresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)
    private var latestTag = "v0.2.0"
    private var checks = 0
    private var apiStatus = HttpStatusCode.OK

    private class FakeInstaller(var path: String? = "/tmp/x.apk", var result: InstallResult = InstallResult.STARTED) : UpdateInstaller {
        override val assetSuffix = ".apk"
        var installed: String? = null
        override suspend fun downloadAndVerify(info: UpdateInfo, onProgress: (Float) -> Unit): String? { onProgress(0.5f); return path }
        override fun install(path: String): InstallResult { installed = path; return result }
    }

    private val installer = FakeInstaller()
    private val http = HttpClient(MockEngine {
        checks++
        respond(
            """{"tag_name":"$latestTag","body":"Fixes","assets":[{"name":"F.apk","browser_download_url":"https://x/F.apk","size":9},{"name":"F.apk.sha256","browser_download_url":"https://x/F.apk.sha256"}]}""",
            apiStatus, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }).withTmdbJson()

    private var installStarted = 0

    private fun presenter(current: String = "0.1.0", inst: UpdateInstaller = installer) =
        UpdatePresenter(UpdateChecker(http, current, ".apk"), inst, repo, CoroutineScope(Dispatchers.Unconfined), clock, current) { installStarted++ }

    private fun await(cond: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!cond()) delay(10) } }

    @Test fun launchCheckFindsNewVersionAndPrompts() {
        repo.putSetting("update.auto", "false")
        val p = presenter(); p.checkOnLaunch()
        await { !p.state.value.checking }
        assertEquals("0.2.0", p.state.value.available?.version)
        assertTrue(p.state.value.prompt)
        assertNull(p.state.value.message) // silent
    }

    @Test fun launchCheckIsThrottledToOncePerSixHours() {
        presenter().also { it.checkOnLaunch(); await { !it.state.value.checking } }
        val before = checks
        presenter().checkOnLaunch()
        assertEquals(before, checks)
        clock.now += 6L * 60 * 60 * 1000 + 1
        presenter().also { it.checkOnLaunch(); await { checks > before } }
    }

    @Test fun skippedVersionStaysQuietAtLaunchButShowsOnManualCheck() {
        repo.putSetting("update.auto", "false")
        val p = presenter(); p.checkOnLaunch(); await { !p.state.value.checking }
        p.skipVersion()
        assertFalse(p.state.value.prompt)
        clock.now += 7L * 60 * 60 * 1000
        val again = presenter(); again.checkOnLaunch(); await { !again.state.value.checking }
        assertFalse(again.state.value.prompt)
        assertNotNull(again.state.value.available) // still offered in Settings
        again.checkNow(); await { !again.state.value.checking && again.state.value.prompt }
        assertTrue(again.state.value.prompt)
    }

    @Test fun skippingThenANewerVersionPromptsAgain() {
        repo.putSetting("update.auto", "false")
        val p = presenter(); p.checkOnLaunch(); await { !p.state.value.checking }
        p.skipVersion()
        latestTag = "v0.3.0"; clock.now += 7L * 60 * 60 * 1000
        val again = presenter(); again.checkOnLaunch(); await { !again.state.value.checking }
        assertTrue(again.state.value.prompt)
    }

    @Test fun manualCheckSaysWhenUpToDateOrFailed() {
        latestTag = "v0.1.0"
        val p = presenter(); p.checkNow(); await { !p.state.value.checking }
        assertTrue("up to date" in p.state.value.message!!)
        apiStatus = HttpStatusCode.Forbidden
        p.checkNow(); await { !p.state.value.checking }
        assertTrue("Could not check" in p.state.value.message!!)
    }

    @Test fun launchFailureIsSilentAndDoesNotCountAsAChecked() {
        apiStatus = HttpStatusCode.Forbidden
        val p = presenter(); p.checkOnLaunch(); await { !p.state.value.checking }
        assertNull(p.state.value.message)
        assertNull(repo.getSetting("update.lastCheckAt"))
    }

    @Test fun installDownloadsVerifiesAndHandsOverToTheSystem() {
        val p = presenter(); p.checkNow(); await { p.state.value.available != null }
        p.install()
        await { p.state.value.progress == null }
        assertEquals("/tmp/x.apk", installer.installed)
        assertNull(p.state.value.message)
    }

    @Test fun failedOrMismatchedDownloadInstallsNothing() {
        val bad = FakeInstaller(path = null)
        val p = presenter(inst = bad); p.checkNow(); await { p.state.value.available != null }
        p.install(); await { p.state.value.progress == null }
        assertNull(bad.installed)
        assertTrue("checksum" in p.state.value.message!!)
    }

    @Test fun missingInstallPermissionExplainsWhatToDo() {
        val inst = FakeInstaller(result = InstallResult.NEEDS_PERMISSION)
        val p = presenter(inst = inst); p.checkNow(); await { p.state.value.available != null }
        p.install(); await { p.state.value.progress == null }
        assertTrue("Allow" in p.state.value.message!!)
    }

    // ---- automatic updates ----

    @Test fun autoUpdateIsOnByDefault() = assertTrue(presenter().state.value.auto)

    @Test fun withAutoOnALaunchCheckInstallsWithoutAnyPrompt() {
        val p = presenter(); p.checkOnLaunch()
        await { installer.installed != null }
        assertEquals("/tmp/x.apk", installer.installed)
        assertFalse(p.state.value.prompt) // nothing was asked
        assertEquals(1, installStarted)
    }

    @Test fun withAutoOffALaunchCheckOnlyPrompts() {
        val p = presenter(); p.setAuto(false); p.checkOnLaunch()
        await { !p.state.value.checking }
        assertTrue(p.state.value.prompt)
        assertNull(installer.installed)
        assertEquals(0, installStarted)
    }

    @Test fun theChoiceIsRemembered() {
        presenter().setAuto(false)
        assertFalse(presenter().state.value.auto)
        presenter().setAuto(true)
        assertTrue(presenter().state.value.auto)
    }

    @Test fun aSkippedVersionIsNotInstalledAutomatically() {
        val first = presenter(); first.setAuto(false); first.checkOnLaunch(); await { !first.state.value.checking }
        first.skipVersion()
        clock.now += 7L * 60 * 60 * 1000
        val p = presenter(); p.setAuto(true); p.checkOnLaunch(); await { !p.state.value.checking }
        assertNull(installer.installed)
        assertFalse(p.state.value.prompt)
    }

    @Test fun aManualCheckStillAsksEvenWithAutoOn() {
        val p = presenter(); p.checkNow()
        await { !p.state.value.checking && p.state.value.available != null }
        assertTrue(p.state.value.prompt)
        assertNull(installer.installed)
    }

    @Test fun autoInstallThatFailsSaysSoAndDoesNotLoop() {
        val bad = FakeInstaller(path = null)
        val p = presenter(inst = bad); p.checkOnLaunch()
        await { !p.state.value.checking && p.state.value.progress == null && p.state.value.message?.contains("checksum") == true }
        assertNull(bad.installed)
        assertEquals(0, installStarted)
    }

    @Test fun upToDateWithAutoOnDoesNothing() {
        latestTag = "v0.1.0"
        val p = presenter(); p.checkOnLaunch(); await { !p.state.value.checking }
        assertNull(installer.installed); assertNull(p.state.value.available)
    }
}
