package com.openrang.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Top-level DataStore singleton — exactly one instance per file per process.
 * Google mandates this pattern to prevent file corruption.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "openrang_preferences"
)

/**
 * Production implementation of [UserPreferencesRepository].
 *
 * Reads are exposed as [Flow] (never blocking). Writes use [DataStore.edit]
 * which is atomic and transactional. IOException is caught on reads and
 * falls back to safe defaults (e.g. show onboarding again).
 */
class UserPreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    private object PreferencesKeys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        // Future keys:
        // val CAPTURE_DURATION_MS = longPreferencesKey("capture_duration_ms")
        // val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        // val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
    }

    override val hasCompletedOnboarding: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                // Safe fallback: show onboarding again rather than crash
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = completed
        }
    }
}
