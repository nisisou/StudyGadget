package com.example.studycompanion.domain.models

data class Session(
    val id: Long,
    val subject: String,
    val start: String,
    val end: String,
    val durationSec: Int,
    val device: String = "3ds",
    val startDate: String = if (start.length >= 10) start.substring(0, 10) else ""
)
