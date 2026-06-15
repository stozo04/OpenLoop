# In-App Updates — Implementation Plan

**Branch (suggested):** `feature/in-app-updates`
**Status:** PRD draft — awaiting sign-off

## Problem statement

OpenLoop has no way to surface "you're on an old build" to users in-product. Today, getting a user
onto a newer `versionCode` depends entirely on them opening Play Store and noticing the update —
which most users won't do. As the app moves from concept spike toward shipping the core loop
feature, the cost of leaving users on stale builds rises: an old build can be missing a Crashlytics
fix, a codec hardening, or (eventually) the boomerang feature itself.

The user-facing ask: **on app startup, check whether the user is on the latest version; if not,
gently nudge them to update.**

## Scope

### In

- Detect "newer build available on Play" from the app process at cold start.
- Background-download the new build via Play (no user wait).
- Surface a dismissible "Update ready — restart to install" prompt in the app's existing
  `SnackbarHost` once the download completes.
- Re-prompt on `onResume` if a download from a prior session is sitting in `DOWNLOADED`.

### Out (explicit non-goals — defer)

- Immediate / blocking update flow (full-screen "must update"). Reserve for a future PR if/when a
  truly broken build ships; gated on Play Console's per-release update-priority field.
- Dedicated banner UI on Camera/Gallery. Existing snackbar carries the signal.
- Periodic WorkManager checks. Cold-start + onResume covers typical sessions.
- F-Droid / sideloaded distribution. Play-only by current product decision; the API silently
  no-ops off-Play, which is the correct behavior.
- Custom "what's new in this version" copy. Play handles release notes; we don't duplicate.

## Architecture

Reuses three existing surfaces — no new screens, no new state machine entries:

| Concern | Where it lives | Notes |
|---|---|---|
| Update check | `MainActivity.onCreate` (one-shot) + `MainActivity.onResume` (stalled-download check) | Mirrors Google's sample placement. Activity-bound because `startUpdateFlowForResult` needs an `ActivityResultLauncher`. |
| Snackbar surface | Existing `SnackbarHost` in `MainActivity.onCreate`'s `setContent` (the same one that shows Saved / Undo / SaveFailed) | Single host already wired; just one more `showSnackbar(...)` call site. |
| Strings | `app/src/main/res/values/strings.xml` | New: `update_ready_message`, `update_ready_action` (i.e. "Restart"). |

State stays out of `OpenLoopUiState` / `OpenLoopViewModel`. The update flow is a peripheral concern
of the Activity, not a routed app state — it must not block the camera, the editor, or any in-flight
render. Wrapping it in the sealed `when` would force every state to think about it.

### Dependency

```kotlin
// libs.versions.toml
play-app-update = "2.1.0"
play-app-update-ktx = { group = "com.google.android.play", name = "app-update-ktx", version.ref = "play-app-update" }

// app/build.gradle.kts
implementation(libs.play.app.update.ktx)
```

`app-update-ktx` pulls `app-update` transitively. Verified current via
`developer.android.com/guide/playcore/in-app-updates/kotlin-java` (2026-06-15). API levels 21+ —
well under our `minSdk 26`.

### Debug-only staleness bypass

`clientVersionStalenessDays()` only populates after Play has known about the new build for some
days, and **returns null/0 over Internal App Sharing** — so without a bypass, the snackbar UI is
impossible to verify manually before merging. Add a `BuildConfig` flag:

```kotlin
// app/build.gradle.kts
buildTypes {
    debug {
        buildConfigField("boolean", "UPDATE_BYPASS_STALENESS", "true")
    }
    release {
        buildConfigField("boolean", "UPDATE_BYPASS_STALENESS", "false")
        // … existing release config
    }
}
```

The gate function honors it:

```kotlin
fun shouldPromptForFlexibleUpdate(
    availability: Int,
    stalenessDays: Int?,
    flexibleAllowed: Boolean,
    thresholdDays: Int = UPDATE_STALENESS_DAYS_THRESHOLD,
    bypassStaleness: Boolean = BuildConfig.UPDATE_BYPASS_STALENESS,
): Boolean = availability == UpdateAvailability.UPDATE_AVAILABLE
    && flexibleAllowed
    && (bypassStaleness || (stalenessDays ?: -1) >= thresholdDays)
```

Release builds always honor the real threshold — `UPDATE_BYPASS_STALENESS` is wired only into the
debug build type, so a release AAB literally cannot ship with the bypass on.

### Decision: FLEXIBLE only, with staleness gate

Per sign-off above. Concretely:

```kotlin
if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
    && (info.clientVersionStalenessDays() ?: -1) >= UPDATE_STALENESS_DAYS_THRESHOLD
    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
) { … }
```

`UPDATE_STALENESS_DAYS_THRESHOLD = 3`. Rationale: lets a same-week patch reach users on day 0–2
without nagging; nudges anyone still on a week-old build. Easy to tune; not user-facing copy.

Any other `updateAvailability` value (e.g. `UPDATE_NOT_AVAILABLE`, `DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`)
is a silent no-op. Sideloaded debug builds return `UPDATE_NOT_AVAILABLE`, which is correct.

## Implementation steps

Ordered. Each numbered step is independently committable.

1. **Add the dependency.** `libs.versions.toml` + `app/build.gradle.kts`. Sync; verify no resolution
   conflicts (Play Core libs are well-isolated, no overlap with Firebase / Media3 / CameraX).

2. **Add strings.** `update_ready_message = "Update ready — restart to install."`,
   `update_ready_action = "Restart"`. English only (the rest of the app is English-only today).

3. **Create `AppUpdateController` (thin wrapper).** `io.github.stozo04.openloop.update`. Hides the
   Play API behind a small interface so MainActivity stays readable and unit-testable parts can be
   covered without the Play library on the JVM classpath. Surface:

   ```kotlin
   class AppUpdateController(private val activity: ComponentActivity) {
       fun register(): Unit          // registers the ActivityResultLauncher + InstallStateUpdatedListener; idempotent
       fun checkOnStart(): Unit      // called from onCreate after setContent
       fun checkOnResume(): Unit     // called from onResume — only fires the "downloaded" prompt
       fun onDownloadComplete: (() -> Unit)?  // set by MainActivity to trigger the snackbar
       fun completeUpdate(): Unit    // called when the user taps Restart
   }
   ```

   Listener lifecycle: `register()` in `onCreate`, `unregister` in `onDestroy`. The
   `InstallStateUpdatedListener` is the only long-lived thing; the `appUpdateInfoTask`
   listeners are one-shot.

4. **Wire into `MainActivity`.**
   - Instantiate `AppUpdateController` in `onCreate` before `setContent`.
   - Set `onDownloadComplete = { snackbarHostState.showSnackbar(...) }` inside the `setContent`
     scope (it captures the existing `SnackbarHostState`).
   - Call `controller.checkOnStart()` after `setContent`.
   - Add `controller.checkOnResume()` to the existing `onResume` override.
   - Add `controller.unregister()` in `onDestroy` next to `cameraManager.shutdown()`.

5. **Snackbar contract.** Use `SnackbarDuration.Indefinite` with an action — matches the
   restart-required semantics (the user must actively choose to restart). Reuses the existing
   styled snackbar; no new UI.

6. **Logcat tag.** Single `TAG = "AppUpdate"` on the controller, all failure paths logged at
   `Log.w` (e.g. `startUpdateFlowForResult` returning a non-OK result). No Crashlytics non-fatals —
   update failures are routine (no network, sideloaded build) and would only generate noise.

## Verification plan

Testing in-app updates is the awkward part of the API — the real Play flow only fires when Play
sees a newer build than the one running. Four tiers, cheapest first.

### T1 — JVM unit test on the gate function

**Proves:** decision logic — only prompts when availability=UPDATE_AVAILABLE **and** staleness ≥
threshold (or bypass is on) **and** FLEXIBLE allowed.
**How:** `app/src/test/java/.../update/AppUpdateGateTest.kt`. The gate is a pure function over
`(Int, Int?, Boolean, Int, Boolean)` — no Play types — so JUnit covers it directly. ~6 cases:
matrix of {AVAILABLE, NOT_AVAILABLE} × {null, 0, threshold-1, threshold} × {flexible allowed,
not allowed} × {bypass on, off}.
**Falls short of:** Activity wiring, snackbar plumbing.

### T2 — mockk-based wiring test

**Proves:** `AppUpdateController` correctly drives the listener — `attach()` registers a single
`InstallStateUpdatedListener`, that listener routes `DOWNLOADED` (and *only* `DOWNLOADED`) to
`onUpdateDownloaded`, `detach()` unregisters the same listener instance, `completeUpdate()`
delegates, and `attach()` is idempotent.
**How:** `app/src/test/java/.../update/AppUpdateControllerTest.kt`. Inject a mockk
`AppUpdateManager`, capture the registered listener with `slot<InstallStateUpdatedListener>()`,
drive it manually through every `InstallStatus` and verify the host callback fires exactly when
`DOWNLOADED` is observed.
**Why not Google's `FakeAppUpdateManager`:** its constructor requires a `Context`, which a plain
JVM unit test cannot provide. Adopting it would force either Robolectric (a big test-classpath dep
for one feature) or moving T2 to `androidTest` (needs an emulator on every run). Mockk catches the
same wiring contracts at a fraction of the cost. The end-to-end UI verification belongs in T3
anyway, where it's a real device against real Play servers.
**Falls short of:** real UI rendering and Play's confirmation dialog. By design — that's T3.

### T3 — Internal App Sharing (IAS)

**Proves:** real Play update flow on a real device against real Play servers — the only way to
visually confirm the snackbar appears and Restart actually relaunches on the new build before
publishing to a track.
**How:** with the debug-only staleness bypass on (release builds always honor the threshold):
1. Build release-signed AAB at current versionCode (22), upload to Play Console IAS, install on
   phone via the IAS link, open from launcher. Logcat: `AppUpdate: UPDATE_NOT_AVAILABLE`.
2. Bump versionCode to 23, upload that AAB to IAS, click the **new** IAS link on the phone —
   *do not install from the Play Store page it shows*. Open the *installed v22* from the launcher.
3. Expect: logcat `AppUpdate: UPDATE_AVAILABLE, starting FLEXIBLE flow` → Play's confirmation
   dialog → background download → snackbar **"Update ready — restart to install"** with
   **Restart** → tap Restart → app relaunches as v23.
4. Repeat step 2 with a release build (bypass off) to confirm the threshold genuinely gates the
   snackbar.

**Requirements:** Same applicationId + signing key; the device's Google account has previously
downloaded OpenLoop from Play. `inAppUpdatePriority` is not honored over IAS (irrelevant — we're
FLEXIBLE-only). `clientVersionStalenessDays()` is null over IAS (hence the bypass).

### T4 — Internal testing track

**Proves:** true production semantics, real staleness counter, real Play propagation.
**How:** publish v22 to internal track, install, publish v23, wait 15 min–several hours, cold-start.
**Falls short of:** fast iteration — Play propagation is slow and bumps versionCode for real.
Reserved for the post-merge "confirm `clientVersionStalenessDays` actually populates after 3 days"
observation noted under [Known limitations](#known-limitations).

### Off-Play (sideload) check

Build debug, install via `adb install`, cold-start. Logcat: `AppUpdate: UPDATE_NOT_AVAILABLE
(sideloaded)`. No snackbar, no exceptions. Confirms the silent no-op.

## Go/no-go checklist before opening the PR

In order:

1. `./gradlew :app:testDebugUnitTest` — T1 + T2 both green.
2. `./gradlew :app:lintDebug` — zero new errors over the baseline.
3. `./gradlew :app:assembleDebug` and `:app:bundleRelease` — both `BUILD SUCCESSFUL` with exit 0
   and zero `e:` errors.
4. Off-Play sideload check above.
5. T3 step 1 (current versionCode on IAS, expect UPDATE_NOT_AVAILABLE).
6. T3 steps 2–3 (new versionCode on IAS, expect snackbar → Restart → relaunch on v23).
7. T3 step 4 (release build, bypass off — confirm threshold genuinely gates).
8. Launch app on emulator, capture screenshot per DoD, attach to PR alongside an IAS screenshot
   of the snackbar in action.

## Known limitations

- **`clientVersionStalenessDays()` in production cannot be verified pre-merge.** IAS doesn't
  populate it. The only honest confirmation is a post-merge observation: ship to the internal
  testing track, wait 3+ days, install v_current on a fresh device, cold-start, and confirm the
  snackbar fires once the threshold is met. Noted on the PR as a known limitation, not as a
  shipped guarantee.

## Acceptance criteria

A change is done when:

1. Building a release AAB with a higher `versionCode`, uploading to internal testing, and
   cold-starting the previous build (after ≥ 3 days of staleness, or with the threshold
   temporarily set to 0) surfaces the styled snackbar with **"Update ready — restart to install"**
   and a **Restart** action.
2. Tapping **Restart** triggers the Play install flow and the app relaunches on the new build.
3. A debug install via `adb install` shows no snackbar at any point, with no exceptions in logcat.
4. `OpenLoopUiState` is unchanged. No new routed state, no `when` branch added to `OpenLoopNavHost`.
5. The `DEFINITION_OF_DONE.md` gate is clean: debug + release build green, lint clean (or
   baselined), instrumented + unit tests pass, app launched on emulator with screenshot attached
   to the PR.

## Open questions

- **Threshold value.** 3 days is a guess. We can ship at 3, watch a release cycle, and tune. Worth
  a `BuildConfig` field? Probably not — change-and-recompile is fine for a single integer that
  doesn't differ per build type. (The bypass *is* a `BuildConfig` field — see above.)
