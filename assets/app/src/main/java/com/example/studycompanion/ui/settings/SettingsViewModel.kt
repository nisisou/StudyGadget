package com.example.studycompanion.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycompanion.core.NetworkUtils
import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.data.SubjectRepository
import com.example.studycompanion.domain.models.Subject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val subjectRepository: SubjectRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val token = settingsStore.tokenFlow
    val port = settingsStore.portFlow
    val autoStart = settingsStore.autoStartFlow
    val studyplusEnabled = settingsStore.studyplusEnabledFlow
    val darkThemeOption = settingsStore.darkThemeOptionFlow
    private val _localEndpoints = MutableStateFlow<List<NetworkUtils.LocalEndpoint>>(emptyList())
    val localEndpoints: StateFlow<List<NetworkUtils.LocalEndpoint>> = _localEndpoints.asStateFlow()

    val allSubjects: StateFlow<List<Subject>> = subjectRepository.allSubjectsFlow.let { flow ->
        val state = MutableStateFlow<List<Subject>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    init {
        viewModelScope.launch {
            settingsStore.getOrCreateToken()
        }
        viewModelScope.launch {
            while (isActive) {
                _localEndpoints.value = withContext(Dispatchers.IO) { NetworkUtils.getLocalEndpoints() }
                delay(3000)
            }
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            settingsStore.regenerateToken()
        }
    }

    fun setPort(newPort: Int) {
        viewModelScope.launch {
            settingsStore.setPort(newPort)
        }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoStart(enabled)
        }
    }

    fun setStudyplusEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setStudyplusEnabled(enabled)
        }
    }

    fun setDarkThemeOption(option: String) {
        viewModelScope.launch {
            settingsStore.setDarkThemeOption(option)
        }
    }

    fun saveSubject(id: String, label: String, colorHex: String, sortOrder: Int, archived: Boolean = false) {
        viewModelScope.launch {
            val subject = Subject(
                id = id.lowercase().trim(),
                label = label.trim(),
                colorHex = colorHex,
                sortOrder = sortOrder,
                archived = archived
            )
            subjectRepository.saveSubject(subject)
        }
    }

    fun updateSubject(subject: Subject) {
        viewModelScope.launch {
            subjectRepository.updateSubject(subject)
        }
    }

    fun toggleArchiveSubject(subject: Subject) {
        viewModelScope.launch {
            val updated = subject.copy(archived = !subject.archived)
            subjectRepository.updateSubject(updated)
        }
    }

    fun deleteSubject(subject: Subject) {
        viewModelScope.launch {
            subjectRepository.deleteSubject(subject)
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            sessionRepository.deleteAllSessions()
            subjectRepository.deleteAllSubjects()
            subjectRepository.seedDefaultsIfEmpty()
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label をコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun exportCsvAndShare(context: Context) {
        viewModelScope.launch {
            val sessions = sessionRepository.getAllSessionsList()
            val sb = java.lang.StringBuilder()
            sb.append("id,subject,start,end,duration,device\n")
            for (s in sessions) {
                sb.append("${s.id},${s.subject},${s.start},${s.end},${s.durationSec},${s.device}\n")
            }

            try {
                val csvFile = File(context.cacheDir, "study_records.csv")
                csvFile.writeText(sb.toString())

                val contentUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    csvFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(shareIntent, "CSVを共有"))
            } catch (e: Exception) {
                Toast.makeText(context, "CSV共有エラー: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
