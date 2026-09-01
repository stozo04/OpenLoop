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

/** Production implementation of [UserPreferencesRepository]. */
class UserPreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    private object PreferencesKeys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val HAS_SEEN_SPEED_CURVE_INTRO = booleanPreferencesKey("has_seen_speed_curve_intro")
        val SAVED_LOOP_COUNT = intPreferencesKey("saved_loop_count")
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

    override val hasSeenSpeedCurveIntro: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                // Safe fallback: show the explainer again rather than crash (Lesson 003).
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.HAS_SEEN_SPEED_CURVE_INTRO] ?: false
        }

    override suspend fun setSpeedCurveIntroSeen(seen: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_SEEN_SPEED_CURVE_INTRO] = seen
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
