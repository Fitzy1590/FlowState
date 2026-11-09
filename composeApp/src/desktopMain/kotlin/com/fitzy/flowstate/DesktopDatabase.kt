package com.fitzy.flowstate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.fitzy.flowstate.db.FlowDatabase
import java.io.File
import java.util.Properties

/**
 * Opens (or creates) the database file. The driver records the schema version in the file and runs the
 * `.sqm` migrations when the app has a newer schema, so updating the app keeps the user's data.
 */
fun openDatabase(file: File): JdbcSqliteDriver =
    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), FlowDatabase.Schema)
