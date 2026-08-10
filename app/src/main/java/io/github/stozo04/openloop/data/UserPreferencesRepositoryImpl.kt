package io.github.stozo04.openloop.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    name = "openloop_preferences"
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
        val SAVED_LOOP_COUNT = intPreferencesKey("saved_loop_count")
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

    /**
     * `edit` is atomic and returns the resulting snapshot, so read-modify-write and read-back are
     * one transaction — two saves finishing close together can't both observe the same total.
     */
    override suspend fun incrementSavedLoopCount(): Int =
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVED_LOOP_COUNT] =
                (preferences[PreferencesKeys.SAVED_LOOP_COUNT] ?: 0) + 1
        }[PreferencesKeys.SAVED_LOOP_COUNT] ?: 0
}
