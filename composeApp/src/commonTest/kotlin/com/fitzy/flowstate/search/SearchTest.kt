package com.fitzy.flowstate.search

import com.fitzy.flowstate.data.Catalog
import com.fitzy.flowstate.data.tmdb.DiscoverQuery
import com.fitzy.flowstate.data.tmdb.EpisodeInfo
import com.fitzy.flowstate.data.tmdb.TmdbDetails
import com.fitzy.flowstate.domain.Candidate
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import com.fitzy.flowstate.reco.UserProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalQueryInterpreterTest {
    private fun q(t: String) = LocalQueryInterpreter.parse(t)!!

    @Test fun oldPopularWarMovies() {
        val r = q("Show me a list of old popular war movies")
        assertEquals(setOf(MediaType.MOVIE), r.mediaTypes)
        assertEquals(setOf("war"), r.genres)
        assertEquals(1999, r.yearTo); assertNull(r.yearFrom)
        assertTrue(r.popular)
        assertTrue(r.seedTitles.isEmpty())
    }

    @Test fun likedBandOfBrothersAndSavingPrivateRyan() {
        val r = q("I liked Band of Brothers and Saving Private Ryan, can you show me some similar shows and movies and let me pick something to watch now?")
        assertEquals(listOf("Band of Brothers", "Saving Private Ryan"), r.seedTitles)
        assertEquals(setOf(MediaType.MOVIE, MediaType.SHOW), r.mediaTypes)
    }

    @Test fun moodsBecomeGenres() {
        assertEquals(setOf("comedy", "family"), q("something cozy").genres)
        assertEquals(setOf("horror"), q("a scary show").genres)
    }

    @Test fun decades() {
        val r = q("80s comedy movies")
        assertEquals(1980, r.yearFrom); assertEquals(1989, r.yearTo); assertEquals(setOf("comedy"), r.genres)
    }

    @Test fun plainTitleFallsBackToTitleSearch() {
        assertEquals("Fargo", q("Fargo").titleSearch)
    }

    @Test fun blankIsNull() = assertNull(LocalQueryInterpreter.parse("   "))
}

class ClaudeQueryParseTest {
    @Test fun readsJsonInsideACodeFence() {
        val q = ClaudeQueryInterpreter.parse("""
            ```json
            {"mediaTypes":["movie"],"genres":["War"],"yearFrom":null,"yearTo":1999,"popular":true,"seedTitles":[],"keywords":[]}
            ```""")!!
        assertEquals(setOf(MediaType.MOVIE), q.mediaTypes); assertEquals(setOf("war"), q.genres)
        assertEquals(1999, q.yearTo); assertTrue(q.popular)
    }

    @Test fun seedTitlesAreKeptAsWritten() {
        val q = ClaudeQueryInterpreter.parse("""{"mediaTypes":["movie","tv"],"genres":[],"seedTitles":["Band of Brothers","Saving Private Ryan"],"popular":false}""")!!
        assertEquals(listOf("Band of Brothers", "Saving Private Ryan"), q.seedTitles)
    }

    @Test fun unknownGenresAndAbsurdYearsAreDropped() {
        val q = ClaudeQueryInterpreter.parse("""{"genres":["Hyperdrama","Sci-Fi"],"yearFrom":5,"yearTo":99999}""")!!
        assertEquals(setOf("science fiction"), q.genres)
        assertNull(q.yearFrom); assertNull(q.yearTo)
    }

    @Test fun keywordsAddMoodGenresAndMissingTypesMeanBoth() {
        val q = ClaudeQueryInterpreter.parse("""{"keywords":["cozy"]}""")!!
        assertEquals(setOf("comedy", "family"), q.genres)
        assertEquals(2, q.mediaTypes.size)
    }

    @Test fun garbageIsNull() {
        assertNull(ClaudeQueryInterpreter.parse("Sure! Here you go."))
        assertNull(ClaudeQueryInterpreter.parse("{not json}"))
    }
}

class SearchServiceTest {
    private fun m(n: Long, vararg g: Int) = Candidate(TitleId(MediaType.MOVIE, n), "M$n", 1990, g.toSet(), voteAverage = 7.0)
    private fun s(n: Long) = Candidate(TitleId(MediaType.SHOW, n), "S$n", 2001, setOf(10768), voteAverage = 8.0)

    private class FakeCatalog(
        val searchResults: Map<String, List<Candidate>> = emptyMap(),
        val related: Map<TitleId, List<Candidate>> = emptyMap(),
        val discovered: Map<MediaType, List<Candidate>> = emptyMap(),
    ) : Catalog {
        val discoverCalls = mutableListOf<Pair<MediaType, DiscoverQuery>>()
        override suspend fun pool() = emptyList<Candidate>()
        override suspend fun details(id: TitleId): TmdbDetails? = null
        override suspend fun seasonEpisodes(show: TitleId, season: Int) = emptyList<EpisodeInfo>()
        override suspend fun seasonTrailerKey(show: TitleId, season: Int): String? = null
        override suspend fun search(query: String) = searchResults[query].orEmpty()
        override suspend fun related(id: TitleId) = related[id].orEmpty()
        override suspend fun discover(type: MediaType, query: DiscoverQuery): List<Candidate> {
            discoverCalls += type to query; return discovered[type].orEmpty()
        }
    }

    @Test fun oldPopularWarMoviesBuildsTheRightDiscoverQuery() = runTest {
        val fx = FakeCatalog(discovered = mapOf(MediaType.MOVIE to listOf(m(1, 10752), m(2, 10752))))
        val q = LocalQueryInterpreter.parse("Show me a list of old popular war movies")!!
        val out = SearchService(fx).run(q, UserProfile.Empty)
        assertEquals(listOf(1L, 2L), out.map { it.id.tmdbId })
        val (type, d) = fx.discoverCalls.single()
        assertEquals(MediaType.MOVIE, type)
        assertEquals(setOf(10752), d.genres); assertEquals(1999, d.yearTo); assertEquals(1000, d.minVotes)
    }

    @Test fun showsUseTheTvGenreId() = runTest {
        val fx = FakeCatalog()
        SearchService(fx).run(SearchQuery(mediaTypes = setOf(MediaType.SHOW), genres = setOf("war")), UserProfile.Empty)
        assertEquals(setOf(10768), fx.discoverCalls.single().second.genres)
    }

    @Test fun moviesAndShowsAreInterleaved() = runTest {
        val fx = FakeCatalog(discovered = mapOf(MediaType.MOVIE to listOf(m(1), m(2)), MediaType.SHOW to listOf(s(9))))
        val out = SearchService(fx).run(SearchQuery(genres = setOf("war")), UserProfile.Empty)
        assertEquals(listOf("M1", "S9", "M2"), out.map { it.name })
    }

    @Test fun seedQueryRanksTitlesRelatedToBothSeedsFirstAndDropsSeeds() = runTest {
        val bob = s(1); val spr = m(2); val both = m(10); val onlyBob = s(11); val onlySpr = m(12)
        val fx = FakeCatalog(
            searchResults = mapOf("Band of Brothers" to listOf(bob), "Saving Private Ryan" to listOf(spr)),
            related = mapOf(
                bob.id to listOf(onlyBob, both, spr), // the other seed must not be suggested
                spr.id to listOf(both, onlySpr, bob),
            ),
        )
        val q = LocalQueryInterpreter.parse("I liked Band of Brothers and Saving Private Ryan, show me similar shows and movies")!!
        val out = SearchService(fx).run(q, UserProfile.Empty)
        assertEquals(10L, out.first().id.tmdbId) // related to both
        assertEquals(setOf(10L, 11L, 12L), out.map { it.id.tmdbId }.toSet())
        assertTrue(bob.id !in out.map { it.id } && spr.id !in out.map { it.id })
    }

    @Test fun watchedAndNeverTitlesAreHidden() = runTest {
        val fx = FakeCatalog(discovered = mapOf(MediaType.MOVIE to listOf(m(1), m(2))))
        val profile = UserProfile.Empty.copy(excluded = setOf(TitleId(MediaType.MOVIE, 1)))
        val out = SearchService(fx).run(SearchQuery(mediaTypes = setOf(MediaType.MOVIE), genres = setOf("war")), profile)
        assertEquals(listOf(2L), out.map { it.id.tmdbId })
    }

    @Test fun titleSearchRespectsMediaType() = runTest {
        val fx = FakeCatalog(searchResults = mapOf("Fargo" to listOf(m(1), s(2))))
        val out = SearchService(fx).run(SearchQuery(mediaTypes = setOf(MediaType.SHOW), titleSearch = "Fargo"), UserProfile.Empty)
        assertEquals(listOf(2L), out.map { it.id.tmdbId })
    }

    @Test fun unresolvableSeedGivesNoResultsNotACrash() = runTest {
        assertTrue(SearchService(FakeCatalog()).run(SearchQuery(seedTitles = listOf("Nope")), UserProfile.Empty).isEmpty())
    }
}
