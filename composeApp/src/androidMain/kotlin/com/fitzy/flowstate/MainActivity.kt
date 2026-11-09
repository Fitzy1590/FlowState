package com.fitzy.flowstate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.data.tmdb.withTmdbJson
import com.fitzy.flowstate.db.FlowDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidSqliteDriver(FlowDatabase.Schema, applicationContext, "flowstate.db")
        val container = AppContainer(
            driver, scope, clock = System::currentTimeMillis,
            secrets = androidSecretStore(applicationContext),
            http = HttpClient(OkHttp).withTmdbJson(),
        )
        setContent { App(container) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
