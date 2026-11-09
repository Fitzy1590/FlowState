package com.fitzy.flowstate

import com.fitzy.flowstate.data.local.FlowRepository
import com.fitzy.flowstate.db.FlowDatabase
import com.fitzy.flowstate.domain.MediaType
import com.fitzy.flowstate.domain.TitleId
import java.io.File
import kotlin.test.*

class DesktopDatabaseTest {
    private val id = TitleId(MediaType.MOVIE, 857)

    @Test fun createsTheFileAndKeepsDataAcrossRestarts() {
        val file = File.createTempFile("flowstate", ".db").apply { delete(); deleteOnExit() }
        openDatabase(file).use { d -> FlowRepository(FlowDatabase(d)) { 1L }.setRating(id, 77) }
        openDatabase(file).use { d -> assertEquals(77, FlowRepository(FlowDatabase(d)) { 1L }.rating(id)) }
    }

    @Test fun anEmptyExistingFileIsInitialisedInsteadOfCrashing() {
        val file = File.createTempFile("flowstate", ".db").apply { deleteOnExit() } // zero-byte file, as a crashed first start leaves
        openDatabase(file).use { d -> assertTrue(FlowRepository(FlowDatabase(d)) { 1L }.libraryIds().isEmpty()) }
    }
}
