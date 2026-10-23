package com.example.studycompanion.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects ORDER BY sortOrder ASC")
    fun getAllSubjectsFlow(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects ORDER BY sortOrder ASC")
    suspend fun getAllSubjectsList(): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE archived = 0 ORDER BY sortOrder ASC")
    fun getActiveSubjectsFlow(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE archived = 0 ORDER BY sortOrder ASC")
    suspend fun getActiveSubjectsList(): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun getSubjectById(id: String): SubjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubject(subject: SubjectEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubjects(subjects: List<SubjectEntity>)

    @Update
    suspend fun updateSubject(subject: SubjectEntity)

    @Delete
    suspend fun deleteSubject(subject: SubjectEntity)

    @Query("DELETE FROM subjects")
    suspend fun deleteAllSubjects(): Int
}
