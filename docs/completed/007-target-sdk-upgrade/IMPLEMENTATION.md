# 007 — Target SDK Upgrade (Android 14 → Android 16) for Google Play

**GitHub Issue:** [#7 — Upgrade targetSdk to meet Google Play requirements](https://github.com/stozo04/OpenLoop/issues/7)
**Branch:** implementation landed on `feature/target-sdk-36` (PR #15). The docs-prep that preceded it shipped on `feature/update-api` (PR #13) — see the [doc-prep record](./DOC-PREP.md), now archived in this same folder.
**Status:** IMPLEMENTED on branch `feature/target-sdk-36` — ready for review. Build (debug + release/R8) is green, unit (24) + instrumented (10) tests pass, and native libs verified 16 KB-aligned. On-device ≥600dp large-screen visual pass still recommended (see "Implementation outcome" below). Behavior-change research is captured in the [Android 16 hub](../../android-16/README.md).
**Last updated:** 2026-05-28

---

## Goal

Raise OpenLoop from `targetSdk 34` (Android 14) to `targetSdk 36` (Android 16) so the app
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
| OpenLoop bundles native `.so` libs via **CameraX** and **Media3** → 16 KB rule applies. | dependency inspection |

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
  `OpenLoopUiState` state machine (per `ANDROID_STANDARDS.md` §10/§11) — do **not** add an ad-hoc back boolean.
- [ ] **Intent redirect hardening, JobScheduler quotas, health permissions** — reviewed, **N/A**
  for OpenLoop's current surface (no exported intents beyond LAUNCHER, no jobs, no health perms).

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
  - **Flip (normative — these state *current* status):** `docs/ANDROID_STANDARDS.md` §8 OpenLoop status note + the four §11 `Status: pending — Issue #7` markers → mark satisfied or remove; `CLAUDE.md` (Tech Stack table + SDK status note) and `README.md` (SDK levels) → change 34 → 36. This is the convergence step that keeps docs and code in agreement ([Lesson 007](../../lessons_learned/007-standards-doc-must-match-code.md)).
  - **Keep (provenance — these record what was *planned/done*):** this file, [`DOC-PREP.md`](./DOC-PREP.md), and the [`docs/android-16/`](../../android-16/README.md) hub. Leave their Issue #7 / "pending" history intact — that's correct linkage, not stale debt to scrub.
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
- **Internal:** OpenLoop Android 16 knowledge hub — [`docs/android-16/`](../../android-16/README.md)

---

## Implementation outcome (what actually shipped, vs. this plan)

Verified against `developer.android.com` + Google Maven on 2026-05-28 at implementation time:

- **Build tooling was already current.** AGP `8.13.2` and Gradle `9.0.0` were already on `main` (bumped in earlier PRs), and AGP 8.13 supports max API 36.1 — so no AGP/Gradle change was needed. The plan's "current state" table (AGP 8.3.2 / Gradle 8.x) was stale.
- **Dependencies went to latest stable, not the plan's numbers.** CameraX `1.3.1 → 1.6.1`, Media3 `1.3.0 → 1.10.1` (the plan named 1.4.2 / 1.7.1, which had been superseded).
- **D2 escalation fired — Kotlin 2.x migration (owner-approved).** The latest CameraX/Media3 are compiled with Kotlin 2.1 metadata, which the Kotlin 1.9.22 compiler cannot read. Resolved by bumping Kotlin `1.9.22 → 2.3.21`, moving the Compose compiler to the `org.jetbrains.kotlin.plugin.compose` Gradle plugin (removing `kotlinCompilerExtensionVersion`), bumping Compose BOM `2024.02.02 → 2026.05.01`, and migrating `kotlinOptions` → the `compilerOptions` DSL.
- **16 KB packaging.** `jniLibs.useLegacyPackaging` flipped `true → false` so native libs are stored uncompressed and 16 KB page-aligned; `zipalign -c -P 16` then reports real `(OK)` alignment (with legacy packaging it was a vacuous "compressed" pass). See [Lesson 011](../../lessons_learned/011-16kb-uncompressed-native-libs.md).
- **R8/shrinking enabled (owner-approved).** `isMinifyEnabled` + `isShrinkResources` = true; added `app/proguard-rules.pro` (relies on CameraX/Media3 consumer rules). Building release surfaced that the three `onboarding_*.png` drawables were actually JPEGs — renamed to `.jpg` (resource refs are extension-agnostic).
- **Phase 7 cleanup was stale.** The `proguardFiles` block was already a single correct call and the manifest had no `tools:targetApi` — nothing to fix.
- **Open questions resolved by owner:** Phase 6 Play release spun off to its own issue (with full prep detail); deps pinned to latest stable; R8 on now; large screens made adaptive (no opt-out).
- **Verification done:** `assembleDebug` + `assembleRelease` green; 24 unit + 10 instrumented tests pass; 16 KB alignment confirmed; onboarding render confirmed on an emulator. **Not yet done:** on-device walkthrough on an API-36/16 KB device and a ≥600dp large-screen visual pass (the only local AVD boots as API 34 on a phone).
