package com.fitzy.flowstate

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.db.FlowDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import okio.Path.Companion.toOkioPath
import java.io.File

private const val BACKGROUND_INTERVAL_MS = 60L * 60 * 1000
private const val POSTER_CACHE_BYTES = 200L * 1024 * 1024

/** %APPDATA%\FlowState on Windows, ~/.flowstate elsewhere. */
private fun dataDir(): File {
    val base = System.getenv("APPDATA")?.let { File(it, "FlowState") } ?: File(System.getProperty("user.home"), ".flowstate")
    return base.also { it.mkdirs() }
}

private fun createDriver(): JdbcSqliteDriver {
    val file = File(dataDir(), "flowstate.db")
    val isNew = !file.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    if (isNew) FlowDatabase.Schema.create(driver)
    return driver
}

fun main() = application {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .diskCache { DiskCache.Builder().directory(File(dataDir(), "poster-cache").toOkioPath()).maxSizeBytes(POSTER_CACHE_BYTES).build() }
            .build()
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val http = HttpClient(OkHttp).withTmdbJson()
    val container = AppContainer(
        createDriver(), clock = System::currentTimeMillis, secrets = desktopSecretStore(dataDir()), http = http,
        launcher = DesktopLinkLauncher(), scope = scope,
        currentVersion = System.getProperty("flowstate.version") ?: "0.1.0",
        updateInstaller = DesktopUpdateInstaller(http, File(dataDir(), "updates")),
    )
    // About hourly while the app is open: sync with Stremio. (Notifications are Android only for now.)
    LaunchedEffect(container) {
        while (true) {
            delay(BACKGROUND_INTERVAL_MS)
            runCatching { container.background.run() }
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "FlowState",
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(900, 600)
        App(container)
    }
}
