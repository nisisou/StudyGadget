package com.example.studycompanion.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY start DESC")
    fun getAllSessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY start ASC")
    suspend fun getAllSessionsList(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE startDate >= :fromStartDate ORDER BY start ASC")
    fun getSessionsFromDateFlow(fromStartDate: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startDate >= :fromStartDate ORDER BY start ASC")
    suspend fun getSessionsFromDateList(fromStartDate: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionEntity?

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions(): Int
}
