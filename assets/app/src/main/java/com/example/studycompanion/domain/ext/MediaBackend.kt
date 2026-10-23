package com.example.studycompanion.domain.ext

import com.example.studycompanion.domain.models.MediaCommand

interface MediaBackend {
    val priority: Int
    suspend fun handle(command: MediaCommand): Boolean
}
