package io.github.stozo04.openloop.diagnostics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Production [AnalyticsReporter] backed by Firebase Analytics.
 *
 * Constructed once in [io.github.stozo04.openloop.MainActivity]'s `viewModels { Factory(...) }`
 * block and threaded into [io.github.stozo04.openloop.ui.OpenLoopViewModel] (Lesson 004 — only the
 * MainActivity bridge ever sees a Context).
 *
 * **Degradation:** If `app/google-services.json` is absent at runtime (CI, fresh clone, debug builds
 * that didn't pull the config), [FirebaseAnalytics.getInstance] throws. We catch in the factory below
 * and downstream the caller gets [NoOpAnalyticsReporter]. This mirrors `ReverseCrashlytics`'s
 * `crashlyticsOrNull()` pattern in the same package.
 *
 * @see <a href="https://firebase.google.com/docs/analytics/android/get-started">Get started with Google Analytics for Android</a>
 */
internal class FirebaseAnalyticsReporterImpl(
    private val analytics: FirebaseAnalytics,
) : AnalyticsReporter {

    override fun screenView(screenName: String, screenClass: String?) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName.take(MAX_STRING_PARAM))
            if (screenClass != null) {
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass.take(MAX_STRING_PARAM))
            }
        }
        runCatching { analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle) }
            .onFailure { Log.w(TAG, "screenView failed: $screenName", it) }
    }

    override fun logEvent(name: String, params: Map<String, Any>) {
        val bundle = Bundle().apply {
            for ((key, value) in params) {
                when (value) {
                    is String -> putString(key, value.take(MAX_STRING_PARAM))
                    is Long -> putLong(key, value)
                    is Int -> putLong(key, value.toLong())
                    is Double -> putDouble(key, value)
                    is Float -> putDouble(key, value.toDouble())
                    is Boolean -> putBoolean(key, value)
                    else -> Log.w(TAG, "logEvent($name) dropped param '$key' of type ${value::class.simpleName}")
                }
            }
        }
        runCatching { analytics.logEvent(name, bundle) }
            .onFailure { Log.w(TAG, "logEvent failed: $name", it) }
    }

    override fun setUserProperty(name: String, value: String?) {
        runCatching { analytics.setUserProperty(name, value?.take(MAX_USER_PROPERTY)) }
            .onFailure { Log.w(TAG, "setUserProperty failed: $name", it) }
    }

    companion object {
        private const val TAG = "AnalyticsReporter"

        /** Firebase truncates most string params server-side at 100 chars; we mirror that here. */
        private const val MAX_STRING_PARAM = 100

        /** Firebase user-property value limit is 36 chars. */
        private const val MAX_USER_PROPERTY = 36

        /**
         * Build the right reporter for this runtime: a Firebase-backed one when `google-services.json`
         * has wired up a [FirebaseApp], otherwise a [NoOpAnalyticsReporter]. Same defensive shape as
         * [io.github.stozo04.openloop.diagnostics.ReverseCrashlytics]'s `crashlyticsOrNull()`.
         */
        fun create(applicationContext: Context): AnalyticsReporter =
            runCatching {
                FirebaseApp.getInstance() // throws if google-services.json wasn't applied
                FirebaseAnalyticsReporterImpl(FirebaseAnalytics.getInstance(applicationContext))
            }.getOrElse {
                Log.i(TAG, "Firebase not initialized; analytics disabled (NoOp)")
                NoOpAnalyticsReporter
            }
    }
}
