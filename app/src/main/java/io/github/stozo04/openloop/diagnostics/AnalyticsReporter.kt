package io.github.stozo04.openloop.diagnostics

/**
 * Thin contract for emitting analytics events. Mirrors the
 * [io.github.stozo04.openloop.data.UserPreferencesRepository] pattern: the ViewModel depends on this
 * interface — never on the [com.google.firebase.analytics.FirebaseAnalytics] SDK directly — so unit
 * tests can swap in a fake without touching Firebase.
 *
 * See [docs/play-store/data-safety.md] for the analytics disclosure schema. Staged rollout: option 1
 * (abstraction only) is shipped; screen tracking and custom events are not wired yet.
 *
 * Firebase Analytics has hard constraints on names and params:
 *  - Event/property names: ≤40 chars, alphanumeric + underscore, must start with a letter.
 *  - Param values are restricted to [Long], [Double], [String], or [Boolean] at the wire level.
 *  - String param values are truncated server-side at 100 chars (500 for some recommended events).
 *
 * The [FirebaseAnalyticsReporterImpl] translates the [Map] into the SDK's Bundle; callers should
 * therefore pass only those four primitive types in [params]. Numeric param values that aren't
 * already [Long]/[Double] are coerced (Int → Long, Float → Double) and other types are skipped with
 * a logcat warning — no silent stringification.
 */
interface AnalyticsReporter {

    /**
     * Emit a `screen_view` event for the named screen. [screenClass] is optional context for the
     * console (typically the Composable's enclosing state class). Called from the navhost's
     * per-state side-effect — see option 2 of the rollout PRD.
     */
    fun screenView(screenName: String, screenClass: String? = null)

    /**
     * Emit a custom event. [name] follows Firebase's naming rules (see KDoc above); [params] must
     * carry only [Long], [Double], [String], or [Boolean] values.
     */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap())

    /**
     * Set a user property (sticky across events until cleared). Passing `null` clears it. Useful
     * for `onboarding_completed`, `default_lens`, etc. — *never* PII.
     */
    fun setUserProperty(name: String, value: String?)
}

/**
 * No-op fallback used in three places:
 *  - Unit tests that don't care about analytics calls.
 *  - CI / fresh-clone builds where `app/google-services.json` is absent — see
 *    `ReverseCrashlytics.crashlyticsOrNull()` for the parallel Crashlytics no-op pattern.
 *  - As the constructor fallback inside [FirebaseAnalyticsReporterImpl] if `FirebaseApp` failed to
 *    initialize (no `google-services.json` present at runtime).
 *
 * Drops every call silently. Auto-collected Firebase events (`first_open`, `session_start`, …) are
 * unaffected — they ship from the SDK's own `ContentProvider`, not through this interface.
 */
object NoOpAnalyticsReporter : AnalyticsReporter {
    override fun screenView(screenName: String, screenClass: String?) = Unit
    override fun logEvent(name: String, params: Map<String, Any>) = Unit
    override fun setUserProperty(name: String, value: String?) = Unit
}
