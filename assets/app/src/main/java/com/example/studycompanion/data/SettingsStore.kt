package com.example.studycompanion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.studycompanion.core.TokenGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val PORT = intPreferencesKey("port")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val STUDYPLUS_ENABLED = booleanPreferencesKey("studyplus_enabled")
        val DARK_THEME_OPTION = stringPreferencesKey("dark_theme_option") // "SYSTEM", "DARK", "LIGHT"
    }

    val tokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TOKEN] ?: ""
    }

    val portFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PORT] ?: 8765
    }

    val autoStartFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_START] ?: false
    }

    val studyplusEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STUDYPLUS_ENABLED] ?: false
    }

    val darkThemeOptionFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_THEME_OPTION] ?: "SYSTEM"
    }

    suspend fun getOrCreateToken(): String {
        val currentToken = tokenFlow.first()
        if (currentToken.isNotBlank()) {
            return currentToken
        }
        val newToken = TokenGenerator.generateToken()
        setToken(newToken)
        return newToken
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
        }
    }

    suspend fun regenerateToken(): String {
        val newToken = TokenGenerator.generateToken()
        setToken(newToken)
        return newToken
    }

    suspend fun setPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PORT] = port
        }
    }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_START] = enabled
        }
    }

    suspend fun setStudyplusEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STUDYPLUS_ENABLED] = enabled
        }
    }

    suspend fun setDarkThemeOption(option: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DARK_THEME_OPTION] = option
        }
    }
}
