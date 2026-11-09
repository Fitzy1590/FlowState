package com.fitzy.flowstate

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.db.FlowDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val container = AppContainer(createDriver(), scope, clock = System::currentTimeMillis, secrets = desktopSecretStore(dataDir()), http = HttpClient(OkHttp).withTmdbJson())
    Window(
        onCloseRequest = ::exitApplication,
        title = "FlowState",
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(900, 600)
        App(container)
    }
}
