package com.example.studycompanion.integrations

import com.example.studycompanion.core.Logger
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.domain.ext.SessionSink
import com.example.studycompanion.domain.models.Session
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudyplusSink @Inject constructor(
    private val settingsStore: SettingsStore
) : SessionSink {

    override val id: String = "StudyplusSink"

    override suspend fun onSessionSaved(session: Session) {
        val enabled = settingsStore.studyplusEnabledFlow.first()
        if (!enabled) {
            Logger.d(id, "Studyplus integration disabled. Skipping session ${session.id}.")
            return
        }
        // Stub implementation for future official Studyplus API integration
        Logger.i(id, "[Stub] Would sync session ${session.id} (${session.subject}, ${session.durationSec}s) to Studyplus.")
    }
}
