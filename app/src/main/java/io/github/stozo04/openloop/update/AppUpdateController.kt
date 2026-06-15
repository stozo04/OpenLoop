package io.github.stozo04.openloop.update

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.github.stozo04.openloop.BuildConfig

/**
 * Default staleness gate for the FLEXIBLE in-app update prompt. Lets a same-week patch reach
 * users on day 0–2 without nagging, then nudges anyone still on a 3+ day-old build. Tunable —
 * see [shouldPromptForFlexibleUpdate].
 */
const val UPDATE_STALENESS_DAYS_THRESHOLD: Int = 3

/**
 * Pure decision: should we prompt the user with a FLEXIBLE in-app update right now?
 *
 * Extracted as a top-level pure function so it's covered by a plain JVM unit test (T1 in the
 * verification plan) without dragging the Play library into a Robolectric harness. Every Play
 * field that drives the decision is passed in as a primitive so the test has no need to construct
 * a real [com.google.android.play.core.appupdate.AppUpdateInfo].
 *
 * Logic: prompt iff Play reports an update is available, FLEXIBLE is allowed, and either the
 * staleness bypass is on (debug builds, for IAS verification) or the build has been stale for at
 * least [thresholdDays] days. A null [stalenessDays] is treated as "not stale enough" — Play
 * returns null when it hasn't yet computed the staleness, which is correct for our gate.
 */
fun shouldPromptForFlexibleUpdate(
    availability: Int,
    stalenessDays: Int?,
    flexibleAllowed: Boolean,
    thresholdDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD,
    bypassStaleness: Boolean = false,
): Boolean = availability == UpdateAvailability.UPDATE_AVAILABLE
    && flexibleAllowed
    && (bypassStaleness || (stalenessDays ?: -1) >= thresholdDays)

/**
 * Activity-bound wrapper around Google Play's [AppUpdateManager]. Drives the FLEXIBLE in-app
 * update flow: checks Play's update metadata on cold start, kicks off a background download when
 * the gate is open, and surfaces the "Update ready — restart to install" prompt via [onUpdateDownloaded]
 * once Play has the new APK staged locally.
 *
 * Designed for testability:
 *  - [appUpdateManager] is injectable so tests can pass a `FakeAppUpdateManager` (T2).
 *  - The decision logic lives in [shouldPromptForFlexibleUpdate] (T1).
 *  - The [ActivityResultLauncher] is **owned by the Activity** (only it can register one before
 *    `STARTED`) and handed to the controller via [attach]. The controller never reaches for an
 *    Activity instance itself.
 *
 * Lifecycle: [attach] in `onCreate` after the launcher is registered; [detach] in `onDestroy` to
 * release the [InstallStateUpdatedListener]. [checkOnStart] runs once per cold start; [checkOnResume]
 * runs on every `onResume` to re-surface a download that completed while the app was backgrounded
 * (Google's recommended stalled-update handling).
 *
 * Off-Play behavior: when the app is sideloaded (or running on a device without Play Services),
 * [AppUpdateManager.getAppUpdateInfo] returns `UPDATE_NOT_AVAILABLE` and the controller silently
 * no-ops. No exception is thrown and no UI is shown — the correct behavior for debug installs.
 */
class AppUpdateController(
    private val appUpdateManager: AppUpdateManager,
    private val bypassStaleness: Boolean = BuildConfig.UPDATE_BYPASS_STALENESS,
    private val stalenessThresholdDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD,
) {
    /**
     * Invoked when Play has finished downloading a newer APK and it's ready to install. The
     * Activity sets this to fire the "Update ready — restart to install" snackbar. Stays nullable
     * so the controller can be constructed before the SnackbarHostState exists (it's created
     * inside `setContent`).
     */
    var onUpdateDownloaded: (() -> Unit)? = null

    private var updateLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var installStateListener: InstallStateUpdatedListener? = null

    /**
     * Idempotent. Wires the [ActivityResultLauncher] (owned by the host Activity) and registers
     * the [InstallStateUpdatedListener] that routes `DOWNLOADED` transitions to [onUpdateDownloaded].
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun attach(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (updateLauncher != null) return
        updateLauncher = launcher
        installStateListener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                Log.i(TAG, "Update downloaded — prompting to restart")
                onUpdateDownloaded?.invoke()
            }
        }.also { appUpdateManager.registerListener(it) }
    }

    /**
     * One-shot check on cold start. If Play reports a newer build and the gate is open, kicks off
     * a FLEXIBLE update flow (Play's confirmation dialog → background download). If the gate is
     * closed for any reason, silently no-ops with a debug log.
     */
    fun checkOnStart() {
        val launcher = updateLauncher ?: run {
            Log.w(TAG, "checkOnStart called before attach(); skipping")
            return
        }
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                val shouldPrompt = shouldPromptForFlexibleUpdate(
                    availability = info.updateAvailability(),
                    stalenessDays = info.clientVersionStalenessDays(),
                    flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    thresholdDays = stalenessThresholdDays,
                    bypassStaleness = bypassStaleness,
                )
                if (shouldPrompt) {
                    Log.i(
                        TAG,
                        "UPDATE_AVAILABLE, starting FLEXIBLE flow " +
                            "(staleness=${info.clientVersionStalenessDays()}, bypass=$bypassStaleness)",
                    )
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                } else {
                    Log.d(
                        TAG,
                        "No flexible update prompted: availability=${info.updateAvailability()} " +
                            "staleness=${info.clientVersionStalenessDays()} " +
                            "flexibleAllowed=${info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}",
                    )
                }
            }
            .addOnFailureListener { e ->
                // Routine off-Play / no-network failures — log at WARN, no Crashlytics noise.
                Log.w(TAG, "appUpdateInfo check failed", e)
            }
    }

    /**
     * Re-surface the "Update ready" prompt when the user returns to the app after a download
     * completed in the background (Google's recommended stalled-update handling). Cheap — just a
     * single [AppUpdateManager.getAppUpdateInfo] poll.
     */
    fun checkOnResume() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                Log.i(TAG, "checkOnResume: update already DOWNLOADED — prompting")
                onUpdateDownloaded?.invoke()
            }
        }
    }

    /** Trigger Play to install the staged APK and restart the app. Called from the snackbar action. */
    fun completeUpdate() {
        Log.i(TAG, "completeUpdate: handing off to Play install")
        appUpdateManager.completeUpdate()
    }

    /**
     * Unregister the [InstallStateUpdatedListener]. Call from `onDestroy`. The
     * [ActivityResultLauncher] is owned by the Activity and unregisters with it automatically.
     */
    fun detach() {
        installStateListener?.let { appUpdateManager.unregisterListener(it) }
        installStateListener = null
    }

    companion object {
        const val TAG: String = "AppUpdate"

        /**
         * Result-code constant matching `Activity.RESULT_OK`. Exposed so the launcher callback in
         * the Activity can be a one-liner that doesn't pull in the full `android.app.Activity`
         * surface just for this comparison.
         */
        const val RESULT_OK: Int = Activity.RESULT_OK
    }
}
