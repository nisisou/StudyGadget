package com.example.studycompanion.widget

import java.time.LocalTime

/** -1 = automatic; the other values match the six SGMO states. */
object WidgetMotion {
    val labels = listOf("待機", "集中", "休憩", "達成", "応援", "睡眠")
    fun nextMode(mode: Int): Int = if (mode in -1..4) mode+1 else -1
    fun state(mode: Int, liveStart: Long, completedAt: Long, now: Long, hour: Int = LocalTime.now().hour): Int {
        if (mode in 0..5) return mode
        if (liveStart > 0 && now >= liveStart && now-liveStart < 12*60*60*1000L) return 1
        if (completedAt > 0 && now >= completedAt && now-completedAt < 30*60*1000L) return 3
        return if (hour in 0..6 || hour == 23) 5 else 0
    }
}
