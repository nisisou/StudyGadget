package com.example.studycompanion.data

import com.example.studycompanion.core.Logger
import com.example.studycompanion.data.db.SessionDao
import com.example.studycompanion.data.db.SessionEntity
import com.example.studycompanion.domain.ext.SessionSink
import com.example.studycompanion.domain.models.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val sessionSinks: Set<@JvmSuppressWildcards SessionSink>
) {
    val allSessionsFlow: Flow<List<Session>> = sessionDao.getAllSessionsFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAllSessionsList(): List<Session> {
        return sessionDao.getAllSessionsList().map { it.toDomain() }
    }

    fun getSessionsFromDateFlow(fromDate: String): Flow<List<Session>> {
        return sessionDao.getSessionsFromDateFlow(fromDate).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getSessionsFromDateList(fromDate: String): List<Session> {
        return sessionDao.getSessionsFromDateList(fromDate).map { it.toDomain() }
    }

    suspend fun getSessionById(id: Long): Session? {
        return sessionDao.getSessionById(id)?.toDomain()
    }

    /**
     * Save session idempotently (if id already exists, ignored).
     * Invokes registered SessionSinks only when a new row was inserted.
     */
    suspend fun saveSession(session: Session): Boolean {
        val entity = SessionEntity.fromDomain(session)
        val rowId = sessionDao.insertSession(entity)
        val isNew = rowId > 0

        if (isNew) {
            val domainSession = sessionDao.getSessionById(session.id)?.toDomain() ?: session
            notifySinks(domainSession)
        }

        return isNew
    }

    suspend fun updateSession(session: Session) {
        val entity = SessionEntity.fromDomain(session)
        sessionDao.updateSession(entity)
    }

    suspend fun deleteSessionById(id: Long) {
        sessionDao.deleteSessionById(id)
    }

    suspend fun deleteAllSessions() {
        sessionDao.deleteAllSessions()
    }

    private suspend fun notifySinks(session: Session) {
        for (sink in sessionSinks) {
            try {
                sink.onSessionSaved(session)
            } catch (e: Exception) {
                Logger.e("SessionRepository", "Error in SessionSink '${sink.id}'", e)
            }
        }
    }
}
