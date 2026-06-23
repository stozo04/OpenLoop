# Robolectric Test Catalog — OpenLoop

Quick-reference inventory of every device-free Robolectric and JVM-framework test in OpenLoop.
For the tutorial (what Robolectric is, when to use it, copy-paste recipes), see
[`robolectric-testing-explained.md`](robolectric-testing-explained.md).

Testing strategy and pyramid context: [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).
OEM / API-34 / Samsung RTL lanes: [`oem-regression-testing.md`](oem-regression-testing.md).

**Shipped in v1.0.26** (PR #81) — Phases 1–2 complete; Phase 3 (Robolectric Compose) deferred.

---

## Run commands

From the repo root (Windows: `gradlew.bat` instead of `./gradlew`):

```bash
# All Robolectric-named tests (~30 s first run; cached after)
./gradlew :app:testDebugUnitTest --tests "*RobolectricTest"

# JVM FGS mapping companion (no Robolectric runner — still fast)
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderNotificationsTest"

# Full local unit suite (plain JUnit + Robolectric)
./gradlew :app:testDebugUnitTest

# One class
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderWorkerRobolectricTest"

# One method
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderForegroundInfoRobolectricTest.android14_foregroundInfo_isDataSync_reproducesAndProvesFix"
```

**Requirements:** Gradle JDK **21+** (Robolectric loads the API-36 `android-all` jar). Android Studio → Settings → Build → Gradle JDK.

---

## Phase status

| Phase | Scope | Status |
|-------|--------|--------|
| **1 — Tier 1** | FGS type, notification channel/progress, import copy, DataStore round-trip | ✅ Shipped |
| **2 — Tier 2** | Worker guard paths, POST_NOTIFICATIONS gate, OEM `DeviceMediaHints` | ✅ Shipped |
| **3 — Tier 3** | Non-graphical Compose on JVM | ⏸ Deferred (no Robolectric Compose harness yet) |

---

## Master inventory

| Test class | Runner | Production code | `@Config(sdk=…)` | Robolectric shadow / harness | Device complement |
|------------|--------|-----------------|------------------|------------------------------|-------------------|
| `work.BoomerangRenderNotificationsTest` | Plain JVM | `BoomerangRenderNotifications.foregroundServiceTypeForSdk()` | N/A (passes `sdkInt` param) | None | `BoomerangRenderForegroundInfoRobolectricTest` |
| `work.BoomerangRenderForegroundInfoRobolectricTest` | Robolectric | `BoomerangRenderNotifications.createForegroundInfo()` | 34, 35, 36 | Real `ForegroundInfo` + resources | `BoomerangRenderWorkerTest` (real FGS on device) |
| `work.BoomerangRenderNotificationsRobolectricTest` | Robolectric | Channel, progress notification, `PendingIntent` | 34 (class default) | `ShadowContentResolver` N/A; real `NotificationManager` | Same |
| `work.BoomerangRenderWorkerRobolectricTest` | Robolectric | `BoomerangRenderWorker` guards only | 34 on `getForegroundInfo` | `TestListenableWorkerBuilder` + `setForegroundUpdater` | `BoomerangRenderWorkerTest` (encode) |
| `data.VideoImporterImportRobolectricTest` | Robolectric | `VideoImporterImpl.importToFile()` | Any | `ShadowContentResolver` | Gallery import E2E / manual |
| `data.UserPreferencesRepositoryImplRobolectricTest` | Robolectric | `UserPreferencesRepositoryImpl` + real DataStore file | Any | Real `preferencesDataStore` delegate | `OpenLoopViewModelTest` (fake repo) |
| `media.DeviceMediaHintsOemRobolectricTest` | Robolectric | `DeviceMediaHints.isSamsungDevice()`, preview cap, encoder order | Any | `ShadowBuild.setManufacturer` / `setBrand` | Samsung RTL sweep |
| `PostNotificationsGateRobolectricTest` | Robolectric | `shouldRequestPostNotificationsPermission()`, `shouldShowNotificationExportHint()` | 32, 33 | `ShadowApplication.grantPermissions` / `denyPermissions` | Manual QA on API 33+ device |

**Total:** 8 test classes · 37 test methods (including JVM companion).

---

## By production area

### Loop export / WorkManager / FGS

**Crashlytics 9663c743** — Android 14 rejected `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` on a `dataSync`-declared service. These tests lock the fix:

| Class | Test method | Asserts |
|-------|-------------|---------|
| `BoomerangRenderNotificationsTest` | `android14_usesDataSync_notMediaProcessing` | API 34 → `dataSync`, not `mediaProcessing` |
| | `android15AndAbove_usesMediaProcessing` | API 35+ → `mediaProcessing` |
| | `api29Through34_useDataSync` | Boundary sweep |
| | `belowApi29_isUntyped` | Pre-Q → type `0` |
| `BoomerangRenderForegroundInfoRobolectricTest` | `android14_foregroundInfo_isDataSync_reproducesAndProvesFix` | Real `ForegroundInfo` on API 34 |
| | `android15_foregroundInfo_isMediaProcessing` | API 35 |
| | `android16_foregroundInfo_isMediaProcessing` | API 36 |
| `BoomerangRenderNotificationsRobolectricTest` | `ensureChannel_createsSingleImportanceLowChannel` | Channel id + `IMPORTANCE_LOW` |
| | `ensureChannel_isIdempotent` | Second call is no-op |
| | `buildProgressNotification_clampsProgressAndSetsContent` | Progress clamped 0–100 |
| | `buildProgressNotification_clampsNegativeProgressToZero` | Negative → 0 |
| | `buildProgressNotification_usesImmutablePendingIntent` | `FLAG_IMMUTABLE` |
| | `buildCompleteNotification_usesImmutablePendingIntent` | Same on complete notification |
| `BoomerangRenderWorkerRobolectricTest` | `doWork_emptyInputData_returnsFailureWithoutEncode` | `Data.EMPTY` → failure |
| | `doWork_partialInputData_returnsFailureWithoutEncode` | Missing keys → failure |
| | `getForegroundInfo_returnsValidForegroundInfo` | Valid FGS info before encode |
| | `fgsPromotionDenied_failsGracefullyAndDeletesStalePartial` | Issue #67 F1 — denied FGS, partial deleted |

**Never Robolectric:** `VideoProcessor.renderBoomerang` — covered by `androidTest` `BoomerangRenderWorkerTest`.

### Library import

| Class | Test method | Asserts |
|-------|-------------|---------|
| `VideoImporterImportRobolectricTest` | `importToFile_copiesRegisteredContentUri` | `content://` copy → `true`, bytes match |
| | `importToFile_returnsFalseWhenSecurityExceptionOnRead` | Revoked URI → `false`, no throw |
| | `importToFile_returnsFalseWhenFileNotFound` | Unregistered URI → `false` |
| | `importToFile_returnsFalseWhenStreamThrowsOnRead` | `IOException` on read → `false` |

**Never Robolectric:** `VideoImporterImpl.probeDurationMs` (`MediaMetadataRetriever`).

### Preferences / onboarding persistence

| Class | Test method | Asserts |
|-------|-------------|---------|
| `UserPreferencesRepositoryImplRobolectricTest` | `freshStore_defaultsOnboardingIncomplete` | Default `hasCompletedOnboarding` = false |
| | `setOnboardingCompleted_persistsAndReadsBack` | Write true → read true |

Clears `filesDir/datastore/openloop_preferences.preferences_pb` in `@Before` to avoid order leakage.

ViewModel transitions with a **fake** repo stay in `OpenLoopViewModelTest`.

### POST_NOTIFICATIONS (API 33+)

Extracted pure gates in `MainActivity.kt`:

- `shouldRequestPostNotificationsPermission(sdkInt, notificationsGranted)`
- `shouldShowNotificationExportHint(sdkInt, notificationsGranted)`

| Class | Test method | `@Config` | Asserts |
|-------|-------------|-----------|---------|
| `PostNotificationsGateRobolectricTest` | `api32_deniedPermission_requestAndHintAreNoOp` | 32 | Both gates false |
| | `api32_grantedPermission_requestAndHintAreNoOp` | 32 | Both gates false |
| | `api33_deniedPermission_gateIsActive` | 33 | Both gates true when denied |
| | `api33_grantedPermission_gateIsInactive` | 33 | Both gates false when granted |

Camera permission **state** remains in `OpenLoopViewModelTest` — not duplicated here.

### OEM identity (Samsung / LG)

| Class | Test method | Asserts |
|-------|-------------|---------|
| `DeviceMediaHintsOemRobolectricTest` | `samsungManufacturer_isSamsungDevice_and480pPreviewCap` | Samsung manufacturer → cap |
| | `samsungBrand_isSamsungDevice_evenWhenManufacturerDiffers` | Brand-only match |
| | `lgeManufacturer_notSamsung_noPreviewCap` | LG not Samsung |
| | `googleEmulatorIdentity_notSamsung_noPreviewCap` | Default emulator identity |
| | `samsungIdentity_encoderTryOrderPrefersC2GoogleFirst` | Encoder ranking |
| | `lgeIdentity_encoderTryOrderDoesNotForceC2GoogleFirst` | Non-Samsung order |

**Never Robolectric:** vendor MediaCodec behavior — Samsung RTL + `VideoReverserTest` on device.

---

## What must stay on a device

| Code | Robolectric test covers | Device test owns |
|------|-------------------------|------------------|
| `BoomerangRenderWorker.doWork` (encode path) | Input parse, FGS info, denied FGS | Full render + scratch discard |
| `VideoProcessor`, `VideoReverser`, `MediaCodecLifecycle` | — | All encode/decode |
| `VideoImporter.probeDurationMs` | — | Duration probe |
| `CameraManager` | — | CameraX |
| Compose layout / screenshots | — | `OnboardingScreenTest`, `LoopifyingScreenshotTest` |

---

## Adding a new Robolectric test

1. Read [`robolectric-testing-explained.md`](robolectric-testing-explained.md) decision rule — if it needs real codecs, stop.
2. Put the class in `app/src/test/` with suffix `RobolectricTest` when it uses `@RunWith(RobolectricTestRunner)`.
3. Name `@Config(sdk=[…])` explicitly when behavior is version-gated.
4. Add a row to the **Master inventory** table in this file and a one-line row in [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).
5. Run `./gradlew :app:testDebugUnitTest` before opening a PR.

---

## Related files

| Path | Role |
|------|------|
| `app/build.gradle.kts` | `testImplementation` Robolectric, `androidx.test.core`, `work-testing`; JDK 21 test launcher |
| `app/src/test/java/io/github/stozo04/openloop/work/BoomerangRenderNotificationsTest.kt` | JVM FGS mapping |
| `app/src/androidTest/.../BoomerangRenderWorkerTest.kt` | Device encode + FGS integration |
| `docs/lessons_learned/024-fgs-type-constant-api-gating.md` | Why API 34 ≠ API 35 for FGS type |
| `docs/lessons_learned/017-androidtest-no-mockk-and-sweep-meaningful-mock-returns.md` | Why Robolectric stays in `src/test/` |
