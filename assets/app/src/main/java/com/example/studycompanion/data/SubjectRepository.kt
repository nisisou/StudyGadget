package com.example.studycompanion.data

import com.example.studycompanion.data.db.AppDatabase
import com.example.studycompanion.data.db.SubjectDao
import com.example.studycompanion.data.db.SubjectEntity
import com.example.studycompanion.domain.models.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubjectRepository @Inject constructor(
    private val subjectDao: SubjectDao
) {
    val allSubjectsFlow: Flow<List<Subject>> = subjectDao.getAllSubjectsFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    val activeSubjectsFlow: Flow<List<Subject>> = subjectDao.getActiveSubjectsFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAllSubjectsList(): List<Subject> {
        return subjectDao.getAllSubjectsList().map { it.toDomain() }
    }

    suspend fun getActiveSubjectsList(): List<Subject> {
        return subjectDao.getActiveSubjectsList().map { it.toDomain() }
    }

    suspend fun getSubjectById(id: String): Subject? {
        return subjectDao.getSubjectById(id)?.toDomain()
    }

    suspend fun saveSubject(subject: Subject) {
        val entity = SubjectEntity.fromDomain(subject)
        subjectDao.insertSubject(entity)
    }

    suspend fun updateSubject(subject: Subject) {
        val entity = SubjectEntity.fromDomain(subject)
        subjectDao.updateSubject(entity)
    }

    suspend fun deleteSubject(subject: Subject) {
        val entity = SubjectEntity.fromDomain(subject)
        subjectDao.deleteSubject(entity)
    }

    suspend fun seedDefaultsIfEmpty() {
        val list = subjectDao.getAllSubjectsList()
        if (list.isEmpty()) {
            subjectDao.insertSubjects(AppDatabase.DEFAULT_SUBJECTS)
        }
    }

    suspend fun deleteAllSubjects() {
        subjectDao.deleteAllSubjects()
    }
}
