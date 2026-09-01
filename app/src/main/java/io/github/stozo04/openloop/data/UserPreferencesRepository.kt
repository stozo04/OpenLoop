package io.github.stozo04.openloop.data

import kotlinx.coroutines.flow.Flow

/** Contract for reading and writing user preferences (onboarding, flags, saved-loop count). */
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
