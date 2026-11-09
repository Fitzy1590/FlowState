package com.fitzy.flowstate

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.db.FlowDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/** Owns the shared container so the screen and the background job use the same database and settings. */
class FlowStateApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val container: AppContainer by lazy {
        val http = HttpClient(OkHttp).withTmdbJson()
        AppContainer(
            AndroidSqliteDriver(FlowDatabase.Schema, applicationContext, "flowstate.db"),
            clock = System::currentTimeMillis,
            secrets = androidSecretStore(applicationContext),
            http = http,
            launcher = AndroidLinkLauncher(applicationContext),
            scope = scope,
            currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0",
            updateInstaller = AndroidUpdateInstaller(applicationContext, http),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        // About hourly, only with a network connection. The job does nothing when there is nothing to sync or notify.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "flowstate-background",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
