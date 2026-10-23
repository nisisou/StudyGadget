package com.example.studycompanion.domain.ext

import com.example.studycompanion.domain.models.Session

interface SessionSink {
    val id: String
    suspend fun onSessionSaved(session: Session)
}
