package com.example.studycompanion.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StartSessionRequest(
    val subject: String? = null,
    val timestamp: Long? = null
)

@Serializable
data class SyncSessionRequest(
    val id: Long,
    val subject: String,
    val start: String,
    val end: String,
    val duration: Int,
    val device: String = "3ds"
)

@Serializable
data class MediaRequest(
    val command: String
)

@Serializable
data class StatusResponse(
    val ok: Boolean = true,
    val live: JsonObject
)

@Serializable
data class WeekDayDto(
    val d: String,
    val sec: Int
)

@Serializable
data class SubjectTotalDto(
    val id: String,
    val sec: Int
)

@Serializable
data class ConfigSubjectResponseDto(
    val id: String,
    val label: String,
    val color: String
)

@Serializable
data class StatsResponse(
    val today: Int,
    val week: List<WeekDayDto>,
    val subjects: List<SubjectTotalDto>,
    val config: List<ConfigSubjectResponseDto>,
    val live: JsonObject,
    val now: Double
)
