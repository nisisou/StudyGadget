package com.example.studycompanion.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.SubjectRepository
import com.example.studycompanion.domain.models.Session
import com.example.studycompanion.domain.models.Subject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val subjectRepository: SubjectRepository
) : ViewModel() {

    val activeSubjects: StateFlow<List<Subject>> = subjectRepository.activeSubjectsFlow.let { flow ->
        val state = MutableStateFlow<List<Subject>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    val subjectsMap: StateFlow<Map<String, Subject>> = subjectRepository.allSubjectsFlow.let { flow ->
        val state = MutableStateFlow<Map<String, Subject>>(emptyMap())
        viewModelScope.launch {
            flow.collect { list -> state.value = list.associateBy { it.id } }
        }
        state.asStateFlow()
    }

    val groupedSessions: StateFlow<Map<String, List<Session>>> = sessionRepository.allSessionsFlow.let { flow ->
        val state = MutableStateFlow<Map<String, List<Session>>>(emptyMap())
        viewModelScope.launch {
            flow.collect { list ->
                state.value = list.groupBy { it.startDate }
            }
        }
        state.asStateFlow()
    }

    private var recentlyDeletedSession: Session? = null

    fun updateSessionSubject(session: Session, newSubjectId: String) {
        viewModelScope.launch {
            val updated = session.copy(subject = newSubjectId)
            sessionRepository.updateSession(updated)
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            recentlyDeletedSession = session
            sessionRepository.deleteSessionById(session.id)
        }
    }

    fun undoDelete() {
        val deleted = recentlyDeletedSession ?: return
        viewModelScope.launch {
            sessionRepository.saveSession(deleted)
            recentlyDeletedSession = null
        }
    }

    fun addManualSession(subjectId: String, startDateTime: LocalDateTime, durationMinutes: Int) {
        viewModelScope.launch {
            val startSec = startDateTime.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
            val endDateTime = startDateTime.plusMinutes(durationMinutes.toLong())
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

            val session = Session(
                id = startSec,
                subject = subjectId,
                start = startDateTime.format(formatter),
                end = endDateTime.format(formatter),
                durationSec = durationMinutes * 60,
                device = "manual",
                startDate = startDateTime.toLocalDate().toString()
            )
            sessionRepository.saveSession(session)
        }
    }
}
