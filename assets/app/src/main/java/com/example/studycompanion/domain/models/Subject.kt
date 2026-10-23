package com.example.studycompanion.domain.models

data class Subject(
    val id: String,
    val label: String,
    val colorHex: String,
    val sortOrder: Int,
    val archived: Boolean = false
)
