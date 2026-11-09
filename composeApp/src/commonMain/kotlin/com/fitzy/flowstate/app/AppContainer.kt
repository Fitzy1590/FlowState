package com.fitzy.flowstate.app

import app.cash.sqldelight.db.SqlDriver
import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.SecretAwareCatalog
import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.data.ai.ClaudeClient
import com.fitzy.flowstate.data.ai.ClaudeSummarizer
import com.fitzy.flowstate.data.ai.Summarizer
import com.fitzy.flowstate.data.youtube.YoutubeClient
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.data.tmdb.TmdbClient
import com.fitzy.flowstate.presenter.EpisodesPresenter
import com.fitzy.flowstate.presenter.LibraryPresenter
import com.fitzy.flowstate.presenter.SearchPresenter
import com.fitzy.flowstate.presenter.SyncPresenter
import com.fitzy.flowstate.search.ClaudeQueryInterpreter
import com.fitzy.flowstate.voice.NoVoiceInput
import com.fitzy.flowstate.voice.VoiceInput
import com.fitzy.flowstate.presenter.UpdatePresenter
import com.fitzy.flowstate.update.UpdateChecker
import com.fitzy.flowstate.update.UpdateInstaller
import com.fitzy.flowstate.sync.StremioApi
import com.fitzy.flowstate.sync.StremioSync
import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.presenter.DiscoverPresenter
import com.fitzy.flowstate.presenter.HomePresenter
import com.fitzy.flowstate.presenter.SettingsPresenter
import com.fitzy.flowstate.secrets.SecretStore
import io.ktor.client.HttpClient
import com.fitzy.flowstate.reco.InterestPrompter
import com.fitzy.flowstate.reco.Recommender
import kotlinx.coroutines.CoroutineScope

/** Wires the shared layer. Each platform entry point supplies a driver, a clock and a scope. */
class AppContainer(
    driver: SqlDriver,
    clock: () -> Long,
    val secrets: SecretStore,
    http: HttpClient,
    private val launcher: LinkLauncher,
    private val scope: CoroutineScope,
    currentVersion: String,
    updateInstaller: UpdateInstaller,
    voice: VoiceInput = NoVoiceInput,
) {
    val repository = FlowRepository(FlowDatabase(driver), clock)
    private val catalog: Catalog = SecretAwareCatalog(secrets, http, seeds = { repository.profile().likedSeeds }, clock = clock)
    private val recommender = Recommender(currentYear = yearOf(clock()))
    val home = HomePresenter(repository, catalog, recommender, InterestPrompter(), scope, clock)
    val discover = DiscoverPresenter(repository, catalog, recommender, scope)
    private val claude = ClaudeClient(http, apiKey = { secrets.get(SecretNames.CLAUDE) })
    private val summarizer: Summarizer = ClaudeSummarizer(claude)
    private val youtube = YoutubeClient(http, apiKey = { secrets.get(SecretNames.YOUTUBE) })

    private val stremioSync = StremioSync(
        StremioApi(http), repository,
        resolver = { imdb -> secrets.get(SecretNames.TMDB)?.let { t -> runCatching { TmdbClient(http, apiKey = { t }).findByImdb(imdb) }.getOrNull() } },
        authKey = { secrets.get(SecretNames.STREMIO_AUTH) },
        clock = clock,
    )
    val sync = SyncPresenter(secrets, StremioApi(http), stremioSync, repository, scope) {
        home.load()
        discover.load()
    }

    val updates = UpdatePresenter(
        UpdateChecker(http, currentVersion, updateInstaller.assetSuffix), updateInstaller, repository, scope, clock, currentVersion,
    )

    private val queryInterpreter = ClaudeQueryInterpreter(claude)
    val search = SearchPresenter(repository, catalog, queryInterpreter, aiAvailable = { claude.hasKey }, voice = voice, scope = scope)
    val library = LibraryPresenter(repository)

    fun episodesFor(c: Candidate) = EpisodesPresenter(c, repository, catalog, summarizer, youtube, launcher, scope)

    fun detailFor(c: Candidate) = DetailPresenter(c, repository, catalog, launcher, scope)

    val settings = SettingsPresenter(secrets) {
        home.load()
        discover.load()
    }
}

/** Gregorian year from epoch millis, using the mean year length. */
internal fun yearOf(millis: Long): Int = 1970 + (millis / 31_556_952_000L).toInt()
