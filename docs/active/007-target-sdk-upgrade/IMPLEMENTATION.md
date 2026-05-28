# 007 — Target SDK Upgrade (Android 14 → Android 16) for Google Play

**GitHub Issue:** [#7 — Upgrade targetSdk to meet Google Play requirements](https://github.com/stozo04/OpenRang/issues/7)
**Branch:** implementation lands on a dedicated future branch. The current `feature/update-api` branch is **docs-prep only** (see [android-16-doc-prep](../android-16-doc-prep/IMPLEMENTATION.md)) — no build or code changes happen there.
**Status:** PLANNING — awaiting sign-off before any build-file changes. Behavior-change research is captured in the [Android 16 hub](../../android-16/README.md).
**Last updated:** 2026-05-28

---

## Goal

Raise OpenRang from `targetSdk 34` (Android 14) to `targetSdk 36` (Android 16) so the app
can be submitted to / updated on Google Play, and meet the related **16 KB page size**
requirement, while handling every behavior change the bump introduces — verified against
`developer.android.com`, not training data.

## Why now (verified this session against Google)

| Fact | Source |
|------|--------|
| Since **Aug 31, 2025**, new apps + updates must target **API 35 (Android 15)** or higher. | developer.android.com/google/play/requirements/target-sdk |
| The extension allowing API 34 closed **Nov 1, 2025**. | same |
| Today is **2026-05-28** → both dates passed. At `targetSdk 34` we **cannot publish**. | — |
| Since **Nov 1, 2025**, apps targeting API 35+ **with native libraries** must support **16 KB page sizes**. | developer.android.com/guide/practices/page-sizes |
| OpenRang bundles native `.so` libs via **CameraX** and **Media3** → 16 KB rule applies. | dependency inspection |

**Decision (confirmed with owner):** go straight to **API 36 (Android 16)**, the latest, to
avoid repeating this when Google raises the floor to 36 (expected ~Aug 2026). This means we
absorb both the 34→35 and 35→36 behavior changes in one pass.

---

## Current → Target state

| Item | Current (verified in repo) | Target |
|------|----------------------------|--------|
| `compileSdk` | 34 | **36** |
| `targetSdk` | 34 | **36** |
| `minSdk` | 26 (Android 8.0) | **26** (unchanged) |
| AGP | 8.3.2 | **8.9.0+** (latest stable verified: **8.13.0**) |
| Gradle wrapper | (verify) | **8.13+** (min for AGP 8.13) |
| Kotlin | 1.9.22 | **1.9.22** (keep — see Decision D2) |
| Compose BOM | 2024.02.02 | **keep** (see Decision D2) |
| CameraX | 1.3.1 | **1.4.2** (16 KB-aligned; latest stable verified) |
| Media3 | 1.3.0 | **1.7.1** (16 KB-aligned; latest stable verified) |
| JDK | 17 | 17 (unchanged — already correct) |

> Version numbers are the latest **stable** confirmed via Google's Maven metadata this
> session. At implementation time, re-check Maven for any newer stable and pin that.

---

## Decisions / scope boundaries

- **D1 — Target 36, not 35.** One upgrade instead of two. (Owner-confirmed.)
- **D2 — Do NOT bump Kotlin or Compose BOM unless the build forces it.** Compose has no
  native libraries, so it is irrelevant to the 16 KB rule and not required by Play. Keeping
  Kotlin 1.9.22 + Compose compiler 1.5.8 avoids a Kotlin 2.0 + Compose-compiler-plugin
  migration, shrinking blast radius. Revisit only if AGP 8.13 rejects Kotlin 1.9.22.
- **D3 — minSdk stays 26.** No reason to drop older-device support.
- **D4 — Play Store release mechanics** (signing, .aab, Data Safety form, privacy policy)
  are tracked in Phase 6 but are arguably a *separate* deliverable from the SDK bump. Flag
  to owner whether #7 should include them or spin off a new issue.

---

## Behavior changes to handle (verified against Google)

### From targeting API 35 (Android 15)
- [ ] **Edge-to-edge displayed by default.** App already calls `enableEdgeToEdge()` in
  `MainActivity.onCreate` — good, but every screen must consume insets correctly.
- [ ] **Foreground service timeouts / new `mediaProcessing` type.** **N/A today** — manifest
  declares no foreground services. ⚠️ If the upcoming "loop generation" / Media3 Transformer
  work runs as a foreground service, it must use the `mediaProcessing` FGS type. Note for later.

### From targeting API 36 (Android 16)
- [ ] **Edge-to-edge is now MANDATORY** — the opt-out (`windowOptOutEdgeToEdgeEnforcement` /
  `setDecorFitsSystemWindows`) is removed. We cannot fall back; insets must be handled in code.
- [ ] **Large-screen resizability/orientation restrictions ignored** (smallest width ≥ 600dp):
  the system forces portrait+landscape+resizable. A camera UI that assumes fixed portrait may
  break on tablets/foldables. Temporary opt-out exists (`PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`)
  but is explicitly temporary — prefer making the UI adaptive.
- [ ] **Predictive back default-on.** Verify back navigation. Back must route through the
  `OpenRangUiState` state machine (per `ANDROID_STANDARDS.md` §10/§11) — do **not** add an ad-hoc back boolean.
- [ ] **Intent redirect hardening, JobScheduler quotas, health permissions** — reviewed, **N/A**
  for OpenRang's current surface (no exported intents beyond LAUNCHER, no jobs, no health perms).

---

## Checklist (work top to bottom)

### Phase 0 — Pre-flight
- [ ] Confirm on branch `feature/update-api`.
- [ ] Record a baseline: `./gradlew clean assembleDebug` succeeds **before** changes.
- [ ] Confirm local Android Studio + SDK Platform 36 + an Android 16 emulator image are installed.
- [ ] Install/locate a **16 KB page-size emulator** image and a **large-screen (≥600dp)** emulator
  for later testing.

### Phase 1 — Build tooling (do first; SDK 36 needs newer AGP/Gradle)
- [ ] Bump Gradle wrapper to **8.13+** (`gradle/wrapper/gradle-wrapper.properties`).
- [ ] Bump AGP `8.3.2 → 8.13.0` in root `build.gradle.kts`.
- [ ] Confirm Kotlin `1.9.22` is still accepted by AGP 8.13. If rejected → escalate to owner
  (triggers the D2 Kotlin/Compose migration sub-task).
- [ ] `./gradlew clean` then sync. (Lesson 003: always clean after Gradle changes.)

### Phase 2 — SDK levels
- [ ] `app/build.gradle.kts`: `compileSdk = 36`.
- [ ] `app/build.gradle.kts`: `targetSdk = 36`.
- [ ] Leave `minSdk = 26`.

### Phase 3 — Dependency upgrades for the 16 KB requirement
- [ ] CameraX `1.3.1 → 1.4.2` (all four artifacts: core, camera2, lifecycle, view).
- [ ] Media3 `1.3.0 → 1.7.1` (exoplayer, ui, transformer).
- [ ] *(Optional, recommended)* bump `androidx.core:core-ktx`, `activity-compose`, `lifecycle-*`
  to current stable to silence compileSdk-36 compat warnings. Mark optional — not required.
- [ ] Re-check Maven for newer stable than the pinned numbers; update if present.
- [ ] `./gradlew clean assembleDebug` — fix any API-removal/deprecation compile errors from the
  CameraX/Media3 jumps (review their release notes for breaking changes across 1.3→1.4 / 1.3→1.7).

### Phase 4 — Behavior-change code work
- [ ] **Edge-to-edge:** audit `CameraScreen`, `OnboardingScreen`, `PreviewScreen`, `GalleryScreen`.
  Ensure interactive elements (shutter, home, gallery delete, onboarding arrows) use
  `WindowInsets` padding so nothing sits under the status/navigation bars. Camera viewfinder may
  stay full-bleed intentionally.
- [ ] **Predictive back:** set `android:enableOnBackInvokedCallback="true"` on `<application>`;
  verify back gestures animate and route through the state machine (Lesson 002).
- [ ] **Large screen:** launch on a ≥600dp emulator; confirm camera preview + controls survive
  landscape/resizing. If broken and out-of-scope to fix now, apply the temporary opt-out property
  and file a follow-up issue (note it here).

### Phase 5 — 16 KB verification
- [ ] Build a release `.aab`/`.apk` and verify native libs are 16 KB-aligned
  (`zipalign -c -P 16 -v <apk>` or Google's `check_elf_alignment.sh`).
- [ ] Run the app on the **16 KB emulator** image; smoke-test camera capture + preview playback.

### Phase 6 — Play Store release prep (confirm scope — see D4)
- [ ] Decide `versionCode`/`versionName` bump (currently `versionCode = 1`).
- [ ] Configure Play App Signing / upload key.
- [ ] Build signed **App Bundle (.aab)**, not APK.
- [ ] Data Safety form + privacy policy (camera usage).
- [ ] Review `release` buildType: `isMinifyEnabled = false` — decide if shrinking is wanted.

### Phase 7 — Cleanup spotted during audit (low risk, fix while here)
- [ ] **Malformed `proguardFiles` block** in `app/build.gradle.kts:26-32` — `proguardFiles(...)`
  is nested inside another `proguardFiles(...)`. Collapse to a single correct call.
- [ ] **Manifest `tools:` namespace:** `app/src/main/AndroidManifest.xml` uses `tools:targetApi="31"`
  but the `<manifest>` tag declares only `xmlns:android`. Verify it builds; add `xmlns:tools` (or
  remove the attribute) as needed.

### Phase 8 — Test & validate
- [ ] `./gradlew clean` then full build.
- [ ] Unit tests + instrumented tests pass.
- [ ] Manual: onboarding → permission → capture → preview → gallery → delete, on **Android 16**,
  **16 KB**, and **large-screen** emulators.
- [ ] Update `docs/TEST_COVERAGE.md` if test surface changed.

### Phase 9 — Wrap-up
- [ ] Add a `docs/lessons_learned/NNN-*.md` entry if the upgrade surfaced a reusable pattern
  (e.g., the 16 KB native-lib gotcha).
- [ ] **Re-verify `docs/android-16/`:** confirm each page summary still matches Google's live page; re-fetch and refresh the fetch/verification dates if stale.
- [ ] Update this file's **Status** line; on merge to `main`, move folder to `docs/completed/`.
- [ ] Close issue #7.
- [ ] **Final step — reconcile the docs with the shipped code.** Once the build actually targets 36, sort every affected doc reference into one of two buckets:
  - **Flip (normative — these state *current* status):** `docs/ANDROID_STANDARDS.md` §8 OpenRang status note + the four §11 `Status: pending — Issue #7` markers → mark satisfied or remove; `CLAUDE.md` (Tech Stack table + SDK status note) and `README.md` (SDK levels) → change 34 → 36. This is the convergence step that keeps docs and code in agreement ([Lesson 007](../../lessons_learned/007-standards-doc-must-match-code.md)).
  - **Keep (provenance — these record what was *planned/done*):** this file, [`android-16-doc-prep/IMPLEMENTATION.md`](../android-16-doc-prep/IMPLEMENTATION.md), and the [`docs/android-16/`](../../android-16/README.md) hub. Leave their Issue #7 / "pending" history intact — that's correct linkage, not stale debt to scrub.
  - The source of truth for the version numbers stays `app/build.gradle.kts`; the docs only echo it, so there are just a handful of echoes to update — all listed above.

---

## Open questions for owner
1. Does #7's scope include **Phase 6 (Play release mechanics)**, or should that be a separate issue?
2. Acceptable to ship without R8/minify (`isMinifyEnabled = false`) for the first release?
3. If a large-screen tablet/foldable layout is non-trivial, OK to use the temporary
   resizability opt-out + follow-up issue, rather than block this upgrade?

## Reversibility note
All changes here are local and version-controlled; revert = `git restore` / branch reset. The
only externally-visible, hard-to-reverse step is **submitting to Google Play (Phase 6)** — that
will get an explicit "proceed" before execution per the project reversibility protocol.

## Sources (verified 2026-05-28)
- Play target API requirement — developer.android.com/google/play/requirements/target-sdk
- Android 16 behavior changes — developer.android.com/about/versions/16/behavior-changes-16
- Android 15 behavior changes — developer.android.com/about/versions/15/behavior-changes-15
- 16 KB page sizes — developer.android.com/guide/practices/page-sizes
- Versions — Google Maven metadata (AGP 8.13.0, CameraX 1.4.2, Media3 1.7.1, Compose BOM 2025.06.01)
- **Internal:** OpenRang Android 16 knowledge hub — [`docs/android-16/`](../../android-16/README.md)
