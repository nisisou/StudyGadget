package com.example.studycompanion

import com.example.studycompanion.domain.StatsCalculator
import com.example.studycompanion.domain.models.Session
import com.example.studycompanion.domain.models.Subject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsCalculatorTest {

    @Test
    fun testCalculateStats() {
        val today = LocalDate.of(2026, 10, 7)
        val todayStr = "2026-10-07"
        val yesterdayStr = "2026-10-06"

        val sessions = listOf(
            Session(id = 1, subject = "math", start = "${todayStr}T10:00:00", end = "${todayStr}T10:30:00", durationSec = 1800, device = "3ds", startDate = todayStr),
            Session(id = 2, subject = "english", start = "${todayStr}T14:00:00", end = "${todayStr}T14:20:00", durationSec = 1200, device = "3ds", startDate = todayStr),
            Session(id = 3, subject = "math", start = "${yesterdayStr}T18:00:00", end = "${yesterdayStr}T18:40:00", durationSec = 2400, device = "3ds", startDate = yesterdayStr)
        )

        val subjects = listOf(
            Subject(id = "english", label = "ENGLISH", colorHex = "60A5FA", sortOrder = 0),
            Subject(id = "math", label = "MATH", colorHex = "F59E0B", sortOrder = 1)
        )

        val result = StatsCalculator.calculateStats(sessions, subjects, today)

        assertEquals(3000, result.today)
        assertEquals(7, result.week.size)
        assertEquals(2, result.streakDays)

        val mathStat = result.subjects.find { it.id == "math" }
        assertEquals(4200, mathStat?.sec)

        val englishStat = result.subjects.find { it.id == "english" }
        assertEquals(1200, englishStat?.sec)
    }
}
