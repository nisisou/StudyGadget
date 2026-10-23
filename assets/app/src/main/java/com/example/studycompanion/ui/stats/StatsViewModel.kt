package com.example.studycompanion.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.SubjectRepository
import com.example.studycompanion.domain.ConfigSubjectDto
import com.example.studycompanion.domain.DayStat
import com.example.studycompanion.domain.StatsCalculator
import com.example.studycompanion.domain.SubjectStat
import com.example.studycompanion.domain.models.Session
import com.example.studycompanion.domain.models.Subject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class StatsPeriod(val label: String, val days: Int) {
    SEVEN_DAYS("7日間", 7),
    THIRTY_DAYS("30日間", 30),
    THIS_MONTH("今月", LocalDate.now().dayOfMonth)
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val subjectRepository: SubjectRepository
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow(StatsPeriod.SEVEN_DAYS)
    val selectedPeriod: StateFlow<StatsPeriod> = _selectedPeriod.asStateFlow()

    private val _dayStats = MutableStateFlow<List<DayStat>>(emptyList())
    val dayStats: StateFlow<List<DayStat>> = _dayStats.asStateFlow()

    private val _subjectStats = MutableStateFlow<List<SubjectStat>>(emptyList())
    val subjectStats: StateFlow<List<SubjectStat>> = _subjectStats.asStateFlow()

    private val _configSubjects = MutableStateFlow<List<ConfigSubjectDto>>(emptyList())
    val configSubjects: StateFlow<List<ConfigSubjectDto>> = _configSubjects.asStateFlow()

    private val _streakDays = MutableStateFlow(0)
    val streakDays: StateFlow<Int> = _streakDays.asStateFlow()

    private val _dailyAverageSec = MutableStateFlow(0)
    val dailyAverageSec: StateFlow<Int> = _dailyAverageSec.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessionRepository.allSessionsFlow,
                subjectRepository.activeSubjectsFlow,
                _selectedPeriod
            ) { sessions, activeSubjects, period ->
                calculateData(sessions, activeSubjects, period)
            }.collect {}
        }
    }

    fun setPeriod(period: StatsPeriod) {
        _selectedPeriod.value = period
    }

    private fun calculateData(
        sessions: List<Session>,
        activeSubjects: List<Subject>,
        period: StatsPeriod
    ) {
        val configDtos = activeSubjects.map {
            ConfigSubjectDto(id = it.id, label = it.label, color = it.colorHex)
        }
        _configSubjects.value = configDtos

        val fullStats = StatsCalculator.calculateStats(sessions, activeSubjects)
        _streakDays.value = fullStats.streakDays

        if (period == StatsPeriod.SEVEN_DAYS) {
            _dayStats.value = fullStats.week
            _subjectStats.value = fullStats.subjects
            _dailyAverageSec.value = fullStats.dailyAverageSec
        } else {
            val (days, subjs) = StatsCalculator.calculateCustomPeriodStats(sessions, period.days)
            _dayStats.value = days
            _subjectStats.value = subjs
            val totalSec = days.sumOf { it.sec }
            _dailyAverageSec.value = if (period.days > 0) totalSec / period.days else 0
        }
    }
}
