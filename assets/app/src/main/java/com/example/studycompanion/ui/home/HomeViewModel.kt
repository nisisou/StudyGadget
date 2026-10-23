package com.example.studycompanion.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycompanion.core.NetworkUtils
import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.data.SubjectRepository
import com.example.studycompanion.domain.LiveSessionState
import com.example.studycompanion.domain.models.LiveSession
import com.example.studycompanion.domain.models.Session
import com.example.studycompanion.domain.models.Subject
import com.example.studycompanion.server.KtorServer
import com.example.studycompanion.server.SyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val ktorServer: KtorServer,
    private val settingsStore: SettingsStore,
    private val sessionRepository: SessionRepository,
    private val subjectRepository: SubjectRepository,
    private val liveSessionState: LiveSessionState
) : ViewModel() {

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()
    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    private val _ipList = MutableStateFlow<List<String>>(emptyList())
    val ipList: StateFlow<List<String>> = _ipList.asStateFlow()

    val port = settingsStore.portFlow
    val token = settingsStore.tokenFlow

    val liveSession: StateFlow<LiveSession?> = liveSessionState.live

    private val _hasRecentAccess = MutableStateFlow(false)
    val hasRecentAccess: StateFlow<Boolean> = _hasRecentAccess.asStateFlow()

    private val _todayTotalSec = MutableStateFlow(0)
    val todayTotalSec: StateFlow<Int> = _todayTotalSec.asStateFlow()

    private val _recentSessions = MutableStateFlow<List<Session>>(emptyList())
    val recentSessions: StateFlow<List<Session>> = _recentSessions.asStateFlow()

    val subjectsMap: StateFlow<Map<String, Subject>> = subjectRepository.allSubjectsFlow.combine(
        subjectRepository.activeSubjectsFlow
    ) { all, active ->
        (all + active).associateBy { it.id }
    }.let { flow ->
        val state = MutableStateFlow<Map<String, Subject>>(emptyMap())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    init {
        viewModelScope.launch {
            settingsStore.getOrCreateToken()
        }
        startStatusPolling()
        observeSessions()
    }

    private fun startStatusPolling() {
        viewModelScope.launch {
            while (true) {
                _isServerRunning.value = ktorServer.isRunning()
                _serverError.value = ktorServer.lastError
                _ipList.value = NetworkUtils.getLocalIpAddresses()
                
                val lastAccess = liveSessionState.lastApiAccessTime.value
                val now = System.currentTimeMillis()
                _hasRecentAccess.value = (now - lastAccess) < 60000

                delay(2000)
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            sessionRepository.allSessionsFlow.collect { list ->
                val todayStr = LocalDate.now().toString()
                _todayTotalSec.value = list.filter { it.startDate == todayStr }.sumOf { it.durationSec }
                _recentSessions.value = list.take(3)
            }
        }
    }

    fun toggleServer(context: Context) {
        try {
            if (_isServerRunning.value) {
                SyncService.stop(context)
                _isServerRunning.value = false
            } else {
                SyncService.start(context)
            }
        } catch (e: Exception) {
            ktorServer.lastError = "サーバー操作に失敗しました: ${e.message ?: e.javaClass.simpleName}"
            _serverError.value = ktorServer.lastError
            _isServerRunning.value = false
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label をコピーしました", Toast.LENGTH_SHORT).show()
    }
}
