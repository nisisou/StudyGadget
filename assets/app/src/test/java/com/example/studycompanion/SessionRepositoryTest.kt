package com.example.studycompanion

import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.db.SessionDao
import com.example.studycompanion.data.db.SessionEntity
import com.example.studycompanion.domain.ext.SessionSink
import com.example.studycompanion.domain.models.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {

    @Test
    fun saveSession_notifiesSinksOnlyForNewRows() = runBlocking {
        val dao = FakeSessionDao()
        val sink = RecordingSink()
        val repository = SessionRepository(dao, setOf(sink))

        val original = Session(
            id = 1L,
            subject = "math",
            start = "2026-10-07T10:00:00",
            end = "2026-10-07T10:30:00",
            durationSec = 1800,
            device = "3ds",
            startDate = "2026-10-07"
        )

        assertTrue(repository.saveSession(original))
        assertEquals(listOf(1L), sink.savedIds)

        val duplicate = original.copy(durationSec = 2400)
        assertFalse(repository.saveSession(duplicate))
        assertEquals(listOf(1L), sink.savedIds)
    }

    private class RecordingSink : SessionSink {
        val savedIds = mutableListOf<Long>()

        override val id: String = "recording-sink"

        override suspend fun onSessionSaved(session: Session) {
            savedIds.add(session.id)
        }
    }

    private class FakeSessionDao : SessionDao {
        private val sessions = linkedMapOf<Long, SessionEntity>()

        override suspend fun insertSession(session: SessionEntity): Long {
            return if (sessions.containsKey(session.id)) {
                0L
            } else {
                sessions[session.id] = session
                1L
            }
        }

        override fun getAllSessionsFlow() = kotlinx.coroutines.flow.flow { emit(sessions.values.toList()) }

        override suspend fun getAllSessionsList(): List<SessionEntity> = sessions.values.toList()

        override fun getSessionsFromDateFlow(fromStartDate: String) =
            kotlinx.coroutines.flow.flow { emit(sessions.values.filter { it.startDate >= fromStartDate }) }

        override suspend fun getSessionsFromDateList(fromStartDate: String): List<SessionEntity> =
            sessions.values.filter { it.startDate >= fromStartDate }

        override suspend fun getSessionById(id: Long): SessionEntity? = sessions[id]

        override suspend fun updateSession(session: SessionEntity) {
            sessions[session.id] = session
        }

        override suspend fun deleteSessionById(id: Long): Int {
            return if (sessions.remove(id) != null) 1 else 0
        }

        override suspend fun deleteAllSessions(): Int {
            val deleted = sessions.size
            sessions.clear()
            return deleted
        }
    }
}
