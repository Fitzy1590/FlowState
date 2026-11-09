package com.fitzy.flowstate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
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
import kotlinx.coroutines.cancel
import okio.Path.Companion.toOkioPath
import java.io.File

private const val POSTER_CACHE_BYTES = 100L * 1024 * 1024

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidSqliteDriver(FlowDatabase.Schema, applicationContext, "flowstate.db")
        val http = HttpClient(OkHttp).withTmdbJson()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        val container = AppContainer(
            driver, clock = System::currentTimeMillis,
            secrets = androidSecretStore(applicationContext),
            http = http,
            launcher = AndroidLinkLauncher(this),
            scope = scope,
            currentVersion = version,
            updateInstaller = AndroidUpdateInstaller(applicationContext, http),
            voice = AndroidVoiceInput(this),
        )
        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .diskCache { DiskCache.Builder().directory(File(cacheDir, "poster-cache").toOkioPath()).maxSizeBytes(POSTER_CACHE_BYTES).build() }
                    .build()
            }
            App(container)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
