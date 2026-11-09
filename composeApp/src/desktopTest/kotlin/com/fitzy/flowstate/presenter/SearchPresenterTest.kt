package com.fitzy.flowstate.presenter

import com.fitzy.flowstate.data.SampleCatalog
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.Vote
import com.fitzy.flowstate.search.QueryInterpreter
import com.fitzy.flowstate.search.SearchQuery
import com.fitzy.flowstate.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class SearchPresenterTest {
    private val clock = Clock()
    private val repo = newRepo(clock)

    private class FakeVoice(override val available: Boolean = true) : VoiceInput {
        var onResult: ((String) -> Unit)? = null; var onError: ((String) -> Unit)? = null; var onPartial: ((String) -> Unit)? = null
        override fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
            this.onPartial = onPartial; this.onResult = onResult; this.onError = onError
        }
        override fun stop() = Unit
    }

    private class FakeAi(val answer: SearchQuery?) : QueryInterpreter {
        var calls = 0
        override suspend fun interpret(text: String): SearchQuery? { calls++; return answer }
    }

    private fun presenter(ai: QueryInterpreter = FakeAi(null), aiOn: Boolean = false, voice: VoiceInput = FakeVoice()) =
        SearchPresenter(repo, SampleCatalog(), ai, { aiOn }, voice, CoroutineScope(Dispatchers.Unconfined))

    @Test fun basicSearchWithoutAiFindsOldWarMovies() {
        val p = presenter(); p.setText("old popular war movies"); p.submit()
        val s = p.state.value
        assertFalse(s.usedAi)
        assertTrue(s.results.isNotEmpty())
        assertTrue(s.results.all { it.id.type == MediaType.MOVIE && (it.year ?: 9999) <= 1999 })
        assertTrue(s.chips.any { it.label == "War" } && s.chips.any { it.label == "Popular" })
    }

    @Test fun aiAnswerIsUsedWhenAvailable() {
        val ai = FakeAi(SearchQuery(seedTitles = listOf("Saving Private Ryan")))
        val p = presenter(ai, aiOn = true); p.setText("something like ryan"); p.submit()
        assertEquals(1, ai.calls)
        assertTrue(p.state.value.usedAi)
        assertTrue(p.state.value.results.isNotEmpty())
        assertTrue(p.state.value.results.none { it.name == "Saving Private Ryan" })
    }

    @Test fun aiFailureFallsBackToBasicSearchWithANote() {
        val p = presenter(FakeAi(null), aiOn = true); p.setText("war movies"); p.submit()
        assertFalse(p.state.value.usedAi)
        assertTrue(p.state.value.results.isNotEmpty())
        assertTrue("basic search" in p.state.value.message!!)
    }

    @Test fun removingAChipRerunsWithoutIt() {
        val p = presenter(); p.setText("old war movies"); p.submit()
        val before = p.state.value.results.size
        p.removeChip("years")
        assertTrue(p.state.value.chips.none { it.key == "years" })
        assertTrue(p.state.value.results.size >= before)
    }

    @Test fun nothingFoundGivesAHelpfulMessage() {
        val p = presenter(); p.setText("zzzzqqq"); p.submit()
        assertTrue(p.state.value.results.isEmpty())
        assertTrue("Nothing found" in p.state.value.message!!)
    }

    @Test fun addPutsTitleInLibraryAsYesFromSearch() {
        val p = presenter(); p.setText("Dune"); p.submit()
        val dune = p.state.value.results.first()
        p.add(dune)
        assertTrue(dune.id in repo.libraryIds())
        assertEquals(Vote.YES, repo.voteFor(dune.id)?.vote)
    }

    @Test fun voiceResultFillsTheBoxAndSearches() {
        val v = FakeVoice(); val p = presenter(voice = v)
        p.startVoice()
        assertTrue(p.state.value.listening)
        v.onPartial!!("war mov")
        assertEquals("war mov", p.state.value.text)
        v.onResult!!("war movies")
        assertFalse(p.state.value.listening)
        assertTrue(p.state.value.results.isNotEmpty())
    }

    @Test fun voiceErrorShowsMessageAndStopsListening() {
        val v = FakeVoice(); val p = presenter(voice = v)
        p.startVoice(); v.onError!!("Microphone permission was denied.")
        assertFalse(p.state.value.listening)
        assertEquals("Microphone permission was denied.", p.state.value.message)
    }

    @Test fun voiceDoesNothingWhereUnavailable() {
        val p = presenter(voice = FakeVoice(available = false)); p.startVoice()
        assertFalse(p.state.value.listening); assertFalse(p.state.value.voiceAvailable)
    }
}
