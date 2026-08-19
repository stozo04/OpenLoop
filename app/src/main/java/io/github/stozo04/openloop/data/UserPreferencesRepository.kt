package io.github.stozo04.openloop.data

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

    /**
     * Emits `true` once the user has seen the speed-curve explainer sheet.
     *
     * Same shape as [hasCompletedOnboarding] and for the same reason: the sheet has to fire exactly
     * once on the first tap of "Curve", and the app has no coach-mark framework to lean on.
     */
    val hasSeenSpeedCurveIntro: Flow<Boolean>

    /** Persist the speed-curve-explainer-seen flag. Called on "Got it". */
    suspend fun setSpeedCurveIntroSeen(seen: Boolean)

    /**
     * Record one successfully saved loop and return the new lifetime total.
     *
     * Gates the Play in-app review card (Issue #121): the ask fires on the *n*-th save, so the
     * caller needs the post-increment value, not a separate read that could race a second save.
     */
    suspend fun incrementSavedLoopCount(): Int
}
