package com.fitzy.flowstate.app

import app.cash.sqldelight.db.SqlDriver
import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.SampleCatalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.presenter.DiscoverPresenter
import com.fitzy.flowstate.presenter.HomePresenter
import com.fitzy.flowstate.reco.InterestPrompter
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope

/** Wires the shared layer. Each platform entry point supplies a driver, a clock and a scope. */
class AppContainer(driver: SqlDriver, scope: CoroutineScope, clock: () -> Long, catalog: Catalog = SampleCatalog()) {
    val repository = FlowRepository(FlowDatabase(driver), clock)
    private val recommender = Recommender(currentYear = yearOf(clock()))
    val home = HomePresenter(repository, catalog, recommender, InterestPrompter(), scope, clock)
    val discover = DiscoverPresenter(repository, catalog, recommender, scope)
}

/** Gregorian year from epoch millis, using the mean year length. */
internal fun yearOf(millis: Long): Int = 1970 + (millis / 31_556_952_000L).toInt()
