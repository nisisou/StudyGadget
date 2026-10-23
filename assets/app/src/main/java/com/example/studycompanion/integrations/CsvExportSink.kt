package com.example.studycompanion.integrations

import com.example.studycompanion.core.Logger
import com.example.studycompanion.domain.ext.SessionSink
import com.example.studycompanion.domain.models.Session
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CsvExportSink @Inject constructor() : SessionSink {

    override val id: String = "CsvExportSink"

    override suspend fun onSessionSaved(session: Session) {
        Logger.d(id, "Session saved and available for CSV export: id=${session.id}, subject=${session.subject}, duration=${session.durationSec}s")
    }
}
