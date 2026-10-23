package com.example.studycompanion.domain

import com.example.studycompanion.domain.models.Session
import com.example.studycompanion.domain.models.Subject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DayStat(
    val d: String,   // "MM-dd"
    val date: String,// "yyyy-MM-dd"
    val sec: Int
)

data class SubjectStat(
    val id: String,
    val sec: Int
)

data class ConfigSubjectDto(
    val id: String,
    val label: String,
    val color: String
)

data class StatsResult(
    val today: Int,
    val week: List<DayStat>,
    val subjects: List<SubjectStat>,
    val config: List<ConfigSubjectDto>,
    val streakDays: Int = 0,
    val dailyAverageSec: Int = 0
)

object StatsCalculator {

    private val YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MM_DD = DateTimeFormatter.ofPattern("MM-dd")

    fun calculateStats(
        sessions: List<Session>,
        activeSubjects: List<Subject>,
        today: LocalDate = LocalDate.now()
    ): StatsResult {
        val todayStr = today.format(YYYY_MM_DD)
        
        // 7 days range: (today - 6 days) to today
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val days7AgoStr = days.first().format(YYYY_MM_DD)

        // Filter sessions starting from 7 days ago
        val sessionsByDate = sessions.groupBy { it.startDate }

        // 1. today total
        val todayTotalSec = sessionsByDate[todayStr]?.sumOf { it.durationSec } ?: 0

        // 2. week 7 days
        val weekStats = days.map { date ->
            val dateStr = date.format(YYYY_MM_DD)
            val sec = sessionsByDate[dateStr]?.sumOf { it.durationSec } ?: 0
            DayStat(
                d = date.format(MM_DD),
                date = dateStr,
                sec = sec
            )
        }

        // 3. subjects in the last 7 days
        val recent7DaysSessions = sessions.filter { it.startDate >= days7AgoStr }
        val subjectTotals = recent7DaysSessions
            .groupBy { it.subject }
            .map { (subjId, list) ->
                SubjectStat(id = subjId, sec = list.sumOf { it.durationSec })
            }
            .sortedByDescending { it.sec }

        // 4. config active subjects
        val configDtos = activeSubjects.map {
            ConfigSubjectDto(
                id = it.id,
                label = it.label,
                color = it.colorHex.removePrefix("#")
            )
        }

        // 5. Streak calculation
        val streak = calculateStreak(sessionsByDate, today)

        // 6. Daily average in last 7 days
        val total7DaysSec = weekStats.sumOf { it.sec }
        val avgSec = total7DaysSec / 7

        return StatsResult(
            today = todayTotalSec,
            week = weekStats,
            subjects = subjectTotals,
            config = configDtos,
            streakDays = streak,
            dailyAverageSec = avgSec
        )
    }

    fun calculateCustomPeriodStats(
        sessions: List<Session>,
        daysCount: Int,
        today: LocalDate = LocalDate.now()
    ): Pair<List<DayStat>, List<SubjectStat>> {
        val startDate = today.minusDays((daysCount - 1).toLong())
        val startDateStr = startDate.format(YYYY_MM_DD)

        val days = (daysCount - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val sessionsByDate = sessions.groupBy { it.startDate }

        val dayStats = days.map { date ->
            val dateStr = date.format(YYYY_MM_DD)
            val sec = sessionsByDate[dateStr]?.sumOf { it.durationSec } ?: 0
            DayStat(
                d = date.format(MM_DD),
                date = dateStr,
                sec = sec
            )
        }

        val periodSessions = sessions.filter { it.startDate >= startDateStr }
        val subjectStats = periodSessions
            .groupBy { it.subject }
            .map { (subjId, list) ->
                SubjectStat(id = subjId, sec = list.sumOf { it.durationSec })
            }
            .sortedByDescending { it.sec }

        return Pair(dayStats, subjectStats)
    }

    private fun calculateStreak(sessionsByDate: Map<String, List<Session>>, today: LocalDate): Int {
        var count = 0
        var checkDate = today

        // Check if studied today
        val studiedToday = (sessionsByDate[checkDate.format(YYYY_MM_DD)]?.sumOf { it.durationSec } ?: 0) > 0
        if (!studiedToday) {
            // Check if studied yesterday; if not, streak is 0
            checkDate = today.minusDays(1)
            val studiedYesterday = (sessionsByDate[checkDate.format(YYYY_MM_DD)]?.sumOf { it.durationSec } ?: 0) > 0
            if (!studiedYesterday) return 0
        }

        while (true) {
            val dateStr = checkDate.format(YYYY_MM_DD)
            val totalSec = sessionsByDate[dateStr]?.sumOf { it.durationSec } ?: 0
            if (totalSec > 0) {
                count++
                checkDate = checkDate.minusDays(1)
            } else {
                break
            }
        }
        return count
    }
}
