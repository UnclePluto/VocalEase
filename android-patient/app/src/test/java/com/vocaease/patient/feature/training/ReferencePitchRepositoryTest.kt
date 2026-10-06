package com.vocaease.patient.feature.training
import com.vocaease.patient.core.network.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReferencePitchRepositoryTest {
    @Test fun recordingUsesSessionReferenceNotLatestCache()=runBlocking {
        val requested=mutableListOf<String?>()
        val repository=ReferencePitchRepository { _,version -> requested+=version;ReferencePitchDto("ready",version ?: "latest",notes=listOf(ReferenceNoteDto(0,1000,57f,1f))) }
        repository.load("song",null)
        val bound=repository.load("song","bound") as ReferencePitchState.Ready
        assertEquals("bound",bound.version)
        assertEquals(listOf(null,"bound"),requested)
    }
    @Test fun rejectsVersionMismatch()=runBlocking {
        val repository=ReferencePitchRepository { _,_ -> ReferencePitchDto("ready","latest") }
        assertTrue(repository.load("song","bound") is ReferencePitchState.Failed)
    }
}
