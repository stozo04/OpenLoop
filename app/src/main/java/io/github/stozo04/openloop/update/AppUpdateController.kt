package io.github.stozo04.openloop.update

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

/** Days a build must be stale before we nudge — same-week patches reach users without nagging. */
const val UPDATE_STALENESS_DAYS_THRESHOLD: Int = 3

/**
 * Pure gate: prompt iff Play has a newer build, FLEXIBLE is allowed, and it's been stale long
 * enough. Null [stalenessDays] (Play hasn't computed it) counts as "not stale enough".
 *
 * Top-level and pure so a plain JVM test covers it without a real `AppUpdateInfo`.
 */
fun shouldPromptForFlexibleUpdate(
    availability: Int,
    stalenessDays: Int?,
    flexibleAllowed: Boolean,
    thresholdDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD,
    bypassStaleness: Boolean = false,
): Boolean = availability == UpdateAvailability.UPDATE_AVAILABLE &&
    flexibleAllowed &&
    (bypassStaleness || (stalenessDays ?: -1) >= thresholdDays)

/**
 * Play FLEXIBLE in-app updates: download in the background, then let the user restart when they
 * want. [check] is called from `onResume` and once from composition; it starts the flow at most
 * once per process and fires [onUpdateDownloaded] whenever Play has an APK staged.
 *
 * The [launcher] is owned by the Activity (only it can register one before `STARTED`). Call
 * [detach] from `onDestroy` to release the installation listener.
 *
 * Off Play (sideloaded debug builds) Play reports `UPDATE_NOT_AVAILABLE` — silent no-op, no UI.
 *
 * [bypassStaleness] defaults to debug builds because `clientVersionStalenessDays()` is always null
 * over Internal App Sharing, which would otherwise make the prompt unverifiable before release.
 */
class AppUpdateController(
    private val appUpdateManager: AppUpdateManager,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
    private val bypassStaleness: Boolean = BuildConfig.DEBUG,
    private val stalenessThresholdDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD,
) {
    /** Fires when Play has a newer APK staged locally. Set once the SnackbarHostState exists. */
    var onUpdateDownloaded: (() -> Unit)? = null

    /** Play's confirmation dialog is a once-per-process ask — declining must not re-nag on resume. */
    private var flowStarted = false

    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            Log.i(TAG, "Update downloaded — prompting to restart")
            onUpdateDownloaded?.invoke()
        }
    }.also { appUpdateManager.registerListener(it) }

    /**
     * Poll Play: prompt if an update is already staged, otherwise start the FLEXIBLE flow when the
     * gate is open. Safe to call every time the Activity resumes — Google's recommended stalled-update handling.
     */
    fun check() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    onUpdateDownloaded?.invoke()
                    return@addOnSuccessListener
                }
                val shouldPrompt = !flowStarted && shouldPromptForFlexibleUpdate(
                    availability = info.updateAvailability(),
                    stalenessDays = info.clientVersionStalenessDays(),
                    flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    thresholdDays = stalenessThresholdDays,
                    bypassStaleness = bypassStaleness,
                )
                if (shouldPrompt) {
                    Log.i(TAG, "Starting FLEXIBLE flow (staleness=${info.clientVersionStalenessDays()})")
                    flowStarted = true
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                }
            }
            // Routine off-Play / no-network failures — log at WARN, no Crashlytics noise.
            .addOnFailureListener { e -> Log.w(TAG, "appUpdateInfo check failed", e) }
    }

    /** Hand the staged APK to Play to install and relaunch. Called from the snackbar action. */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    /** Release the installation listener. The Activity's launcher unregisters with the Activity. */
    fun detach() {
        appUpdateManager.unregisterListener(installStateListener)
    }

    companion object {
        const val TAG: String = "AppUpdate"
    }
}
