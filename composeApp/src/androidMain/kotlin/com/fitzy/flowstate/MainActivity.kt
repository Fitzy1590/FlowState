package com.fitzy.flowstate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.fitzy.flowstate.app.AppContainer
import com.fitzy.flowstate.db.FlowDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidSqliteDriver(FlowDatabase.Schema, applicationContext, "flowstate.db")
        val container = AppContainer(driver, scope, clock = System::currentTimeMillis)
        setContent { App(container) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
