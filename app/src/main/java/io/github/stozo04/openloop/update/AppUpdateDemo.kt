package io.github.stozo04.openloop.update

import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import io.github.stozo04.openloop.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Intent extra that swaps in [demoAppUpdateManager]. Debug builds only — see that function. */
const val EXTRA_DEMO_UPDATE: String = "openloop.demoUpdate"

/** Pause between demo steps, long enough to watch each one land. */
private const val DEMO_STEP_MS = 2_000L

/**
 * Debug-only stand-in for Play so the **"Update ready — restart to install"** snackbar can be seen
 * on an emulator, with no Play Console round-trip. Launch it with:
 *
 * ```
 * adb shell am start -n io.github.stozo04.openloop/.MainActivity --ez openloop.demoUpdate true
 * ```
 *
 * Play's [FakeAppUpdateManager] runs the real update state machine but renders **no UI of its own**,
 * so this stands in for the taps Play would collect: it reports a newer build that is stale enough
 * to pass the gate, then accepts and downloads it on a timer. Roughly six seconds after launch the
 * snackbar appears; tapping **Restart** calls `completeUpdate()`, which the fake acknowledges
 * without actually reinstalling anything.
 *
 * **What this cannot show:** Play's own confirmation dialog (step 1 of the real flow). It is Play's
 * UI, not ours, and the fake does not draw it. Seeing that requires a real Play install — Internal
 * App Sharing, per the originating PR's T3 step.
 *
 * The only call site is guarded by `BuildConfig.DEBUG`, so R8 strips this from release builds.
 */
fun demoAppUpdateManager(context: Context, scope: CoroutineScope): AppUpdateManager =
    FakeAppUpdateManager(context).also { fake ->
        fake.setUpdateAvailable(BuildConfig.VERSION_CODE + 1)
        fake.setClientVersionStalenessDays(UPDATE_STALENESS_DAYS_THRESHOLD)
        Log.i(TAG, "demo: pretending Play has v${BuildConfig.VERSION_CODE + 1}, stale enough to prompt")
        scope.launch {
            delay(DEMO_STEP_MS) // let the Activity's check() open Play's flow first
            if (!fake.isConfirmationDialogVisible()) {
                Log.w(TAG, "demo: update flow never started — is the staleness gate open?")
                return@launch
            }
            fake.userAcceptsUpdate()
            Log.i(TAG, "demo: user accepted")
            delay(DEMO_STEP_MS)
            fake.downloadStarts()
            Log.i(TAG, "demo: downloading…")
            delay(DEMO_STEP_MS)
            fake.downloadCompletes()
            Log.i(TAG, "demo: download complete — the snackbar should be on screen now")
        }
    }

private const val TAG = AppUpdateController.TAG
