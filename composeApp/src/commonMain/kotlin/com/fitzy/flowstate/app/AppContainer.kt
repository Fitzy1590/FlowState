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
import com.fitzy.flowstate.ratings.OmdbClient
import com.fitzy.flowstate.search.ClaudeQueryInterpreter
import com.fitzy.flowstate.backup.BackupService
import com.fitzy.flowstate.background.BackgroundWork
import com.fitzy.flowstate.presenter.BackupPresenter
import com.fitzy.flowstate.background.NOTIFICATIONS_KEY
import com.fitzy.flowstate.background.UpcomingService
import com.fitzy.flowstate.presenter.UpdatePresenter
import com.fitzy.flowstate.update.UpdateChecker
import com.fitzy.flowstate.update.UpdateInstaller
import com.fitzy.flowstate.sync.StremioApi
import com.fitzy.flowstate.sync.TitleResolver
import com.fitzy.flowstate.sync.nuvio.NuvioAccountStore
import com.fitzy.flowstate.sync.nuvio.NuvioApi
import com.fitzy.flowstate.sync.nuvio.NuvioSync
import com.fitzy.flowstate.presenter.NuvioPresenter
import com.fitzy.flowstate.sync.StremioSync
import com.fitzy.flowstate.secrets.SecretNames
import com.fitzy.flowstate.links.LinkLauncher
import com.fitzy.flowstate.presenter.DEFAULT_PLAYER_KEY
import com.fitzy.flowstate.presenter.DetailPresenter
import com.fitzy.flowstate.presenter.Player
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
    private val clock: () -> Long,
    val secrets: SecretStore,
    http: HttpClient,
    private val launcher: LinkLauncher,
    private val scope: CoroutineScope,
    currentVersion: String,
    updateInstaller: UpdateInstaller,
    val hooks: ActivityHooks = ActivityHooks(),
    onInstallStarted: () -> Unit = {},
) {
    val repository = FlowRepository(FlowDatabase(driver), clock)
    private val catalog: Catalog = SecretAwareCatalog(secrets, http, seeds = { repository.profile().likedSeeds }, clock = clock)
    private val recommender = Recommender(currentYear = yearOf(clock()))
    val home = HomePresenter(repository, catalog, recommender, InterestPrompter(), scope, clock)
    val discover = DiscoverPresenter(repository, catalog, recommender, scope)
    private val claude = ClaudeClient(http, apiKey = { secrets.get(SecretNames.CLAUDE) })
    private val summarizer: Summarizer = ClaudeSummarizer(claude)
    private val youtube = YoutubeClient(http, apiKey = { secrets.get(SecretNames.YOUTUBE) })

    private val tmdbResolver = TitleResolver { imdb ->
        secrets.get(SecretNames.TMDB)?.let { t -> runCatching { TmdbClient(http, apiKey = { t }).findByImdb(imdb) }.getOrNull() }
    }

    private val stremioSync = StremioSync(
        StremioApi(http), repository,
        resolver = tmdbResolver,
        authKey = { secrets.get(SecretNames.STREMIO_AUTH) },
        clock = clock,
    )

    private val nuvioStore = NuvioAccountStore(secrets)
    private val nuvioApi = NuvioApi(http, nuvioStore, clientId = ::nuvioClientId, clock = clock)
    private val nuvioSync = NuvioSync(nuvioApi, nuvioStore, repository, catalog, tmdbResolver, clock)
    val nuvio = NuvioPresenter(http, nuvioApi, nuvioStore, nuvioSync, repository, scope, hasTmdb = { secrets.get(SecretNames.TMDB) != null }) {
        home.load()
        discover.load()
    }

    /** Random id that tells the server which app made a change. Made once and kept. */
    private fun nuvioClientId(): String = repository.getSetting("nuvio.clientId") ?: run {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        ("flowstate-" + (1..32).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")).also { repository.putSetting("nuvio.clientId", it) }
    }

    val sync = SyncPresenter(secrets, StremioApi(http), stremioSync, repository, scope, hasTmdb = { secrets.get(SecretNames.TMDB) != null }) {
        home.load()
        discover.load()
    }

    val updates = UpdatePresenter(
        UpdateChecker(http, currentVersion, updateInstaller.assetSuffix), updateInstaller, repository, scope, clock, currentVersion, onInstallStarted,
    )

    private val omdb = OmdbClient(http, apiKey = { secrets.get(SecretNames.OMDB) })
    private val queryInterpreter = ClaudeQueryInterpreter(claude)
    val search = SearchPresenter(repository, catalog, queryInterpreter, aiAvailable = { claude.hasKey }, voice = HookedVoice(hooks), scope = scope)
    val backup = BackupPresenter(BackupService(repository, clock), hooks, clock) {
        home.load()
        discover.load()
    }

    private val upcomingService = UpcomingService(repository, catalog)
    val library = LibraryPresenter(repository, upcomingService, scope)
    val background = BackgroundWork(repository, stremioSync, upcomingService, clock, nuvioSync)

    fun episodesFor(c: Candidate) = EpisodesPresenter(c, repository, catalog, summarizer, youtube, launcher, scope)

    fun detailFor(c: Candidate) = DetailPresenter(c, repository, catalog, launcher, omdb, scope, clock)

    val settings = SettingsPresenter(
        secrets,
        getPlayer = { repository.getSetting(DEFAULT_PLAYER_KEY)?.let { runCatching { Player.valueOf(it) }.getOrNull() } ?: Player.STREMIO },
        setPlayer = { repository.putSetting(DEFAULT_PLAYER_KEY, it.name) },
        actors = { repository.actorRows() },
        clearActor = { repository.setActorPref(it, "", null) },
        getNotifications = { repository.getSetting(NOTIFICATIONS_KEY) == "true" },
        setNotifications = { repository.putSetting(NOTIFICATIONS_KEY, it.toString()) },
        requestNotificationPermission = { reply -> hooks.requestNotificationPermission(reply) },
        openLink = { launcher.open(it) },
    ) {
        home.load()
        discover.load()
    }
}

/** Gregorian year from epoch millis, using the mean year length. */
internal fun yearOf(millis: Long): Int = 1970 + (millis / 31_556_952_000L).toInt()
