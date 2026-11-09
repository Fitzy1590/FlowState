package com.fitzy.flowstate.update

import com.fitzy.flowstate.data.tmdb.withTmdbJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UpdateCheckerTest {
    private var url = ""
    private fun checker(body: String, status: HttpStatusCode = HttpStatusCode.OK, current: String = "0.1.0", suffix: String = ".apk") = UpdateChecker(
        HttpClient(MockEngine { url = it.url.toString(); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }).withTmdbJson(),
        current, suffix,
    )

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","body":"Notes here","draft":$draft,"prerelease":$pre,"assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://x/$it","size":1234}""" }}]}"""

    @Test fun semverParsingAndOrder() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3-beta.1"))
        assertNull(SemVer.parse("1.2")); assertNull(SemVer.parse("latest")); assertNull(SemVer.parse("1.x.3"))
        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9)); assertTrue(SemVer(1, 0, 0) > SemVer(0, 99, 99))
    }

    @Test fun newerReleaseIsAvailableWithApkAndChecksum() = runTest {
        val r = checker(release("v0.2.0", "FlowState-v0.2.0.apk", "FlowState-v0.2.0.apk.sha256", "FlowState-v0.2.0.msi")).check()
        val info = (r as CheckResult.Available).info
        assertEquals("0.2.0", info.version)
        assertEquals("https://x/FlowState-v0.2.0.apk", info.url)
        assertEquals("https://x/FlowState-v0.2.0.apk.sha256", info.checksumUrl)
        assertEquals("Notes here", info.notes)
        assertTrue("/repos/fitzy1590/flowstate/releases/latest" in url, url)
    }

    @Test fun windowsPicksTheMsi() = runTest {
        val r = checker(release("v0.2.0", "a.apk", "FlowState-v0.2.0.msi", "FlowState-v0.2.0.msi.sha256"), suffix = ".msi").check()
        assertEquals("https://x/FlowState-v0.2.0.msi", (r as CheckResult.Available).info.url)
    }

    @Test fun sameOrOlderIsUpToDate() = runTest {
        assertEquals(CheckResult.UpToDate, checker(release("v0.1.0", "a.apk"), current = "0.1.0").check())
        assertEquals(CheckResult.UpToDate, checker(release("v0.1.0", "a.apk"), current = "0.2.0").check())
    }

    @Test fun noReleaseDraftOrPrereleaseIsUpToDate() = runTest {
        assertEquals(CheckResult.UpToDate, checker("{}", HttpStatusCode.NotFound).check())
        assertEquals(CheckResult.UpToDate, checker(release("v9.0.0", "a.apk", draft = true)).check())
        assertEquals(CheckResult.UpToDate, checker(release("v9.0.0", "a.apk", pre = true)).check())
    }

    @Test fun failuresAreReportedNotThrown() = runTest {
        assertEquals(CheckResult.Failed, checker("{}", HttpStatusCode.Forbidden).check()) // rate limited
        assertEquals(CheckResult.Failed, checker("not json").check())
        assertEquals(CheckResult.Failed, checker(release("nightly", "a.apk")).check()) // tag is not a version
        assertEquals(CheckResult.Failed, checker(release("v0.2.0", "only.msi")).check()) // no installer for this platform
    }

    @Test fun missingChecksumAssetIsReportedAsNull() = runTest {
        val r = checker(release("v0.2.0", "a.apk")).check() as CheckResult.Available
        assertNull(r.info.checksumUrl)
    }
}
