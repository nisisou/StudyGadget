package com.example.studycompanion.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface AppClock {
    fun currentTimeMillis(): Long
    fun currentTimeSeconds(): Double = currentTimeMillis() / 1000.0
    fun nowIso8601(): String
}

class SystemClock : AppClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun nowIso8601(): String {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}
