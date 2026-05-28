package com.openrang.app.data

import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading and writing user preferences.
 *
 * Backed by Jetpack DataStore (Preferences). The ViewModel depends on
 * this interface — never on the implementation — so tests can swap in
 * a fake without touching the real DataStore.
 */
interface UserPreferencesRepository {

    /** Emits `true` once the user has completed the onboarding carousel. */
    val hasCompletedOnboarding: Flow<Boolean>

    /** Persist the onboarding-completed flag. Called once on "LET'S GO!" tap. */
    suspend fun setOnboardingCompleted(completed: Boolean)
}
