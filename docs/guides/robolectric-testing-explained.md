# Robolectric Testing — Explained Like You're Five

A plain-English guide to the new test tool OpenLoop just gained — what Robolectric is,
when to reach for it (and when *not* to), the exact setup already wired into this repo, a
minimal copy-pasteable example, and a prioritized list of the highest-value things in *this*
codebase that Robolectric can finally let us test.

No prior knowledge needed. Technical words are defined in the [Glossary](#glossary) at the bottom.

> **OEM regression (Samsung / LG / API 34):** Robolectric is one lane in a larger matrix.
> See **[`oem-regression-testing.md`](oem-regression-testing.md)** for the full picture (emulator
> sweeps, Samsung RTL, LG fault injection, and when each applies).
>
> **Test inventory (run commands, every class/method):** **[`robolectric-test-catalog.md`](robolectric-test-catalog.md)**.

---

## The one-sentence version

**Robolectric lets a unit test run real Android framework code — `Notification`,
`NotificationManager`, `ServiceInfo`, `Uri`, `ContentResolver`, `Build.VERSION` — on your
laptop's JVM, in milliseconds, with no emulator and no phone, and even *pretend to be a
specific Android version* with one annotation.**

That last part is the magic. A lot of OpenLoop's nastiest bugs are **version-specific** (the
Loop-export crash only happened on Android 14; notification permission only exists on Android
13+). Before Robolectric, the only way to test "does this behave right on Android 14?" was to
boot an Android-14 emulator. Now we can test all of those, instantly, from a normal unit test.

---

## Three kinds of test (and how to choose)

OpenLoop now has **three** places a test can run. Picking the right one is the whole game.

| | Plain JUnit unit test | **Robolectric unit test** | Instrumented test |
|---|---|---|---|
| **Lives in** | `app/src/test/` | `app/src/test/` | `app/src/androidTest/` |
| **Runs on** | Your laptop's JVM | Your laptop's JVM | A real device / emulator |
| **Speed** | Milliseconds | Tens–hundreds of ms | Seconds (build + install) |
| **Can touch Android APIs?** | No (unless you mock them) | **Yes — simulated** | Yes — the *real* thing |
| **Can fake an Android version?** | No | **Yes — `@Config(sdk=[34])`** | No (you get the device's version) |
| **Real pixels / real codecs / real camera?** | No | **No** | **Yes** |
| **Good for** | Pure logic, math, state machines | Framework *objects & branching* without a device | Real UI rendering, real MediaCodec, real CameraX |

### The decision rule

1. **Does the code touch zero Android APIs?** (pure Kotlin: math, string parsing, state
   transitions, list sorting) → **Plain JUnit.** Fastest. This is most of `media/BoomerangSequence.kt`,
   `media/MediaFormatUtils.kt`'s pure functions, and every `OpenLoopViewModel` state-transition test
   we already have.

2. **Does it touch the Android framework, but you only care about the *logic and the objects it
   builds* — not real pixels or real audio/video bytes?** (Did it pick the right `ServiceInfo` type?
   Did it build a `Notification` with the right channel? Did it copy a `content://` stream and return
   `false` on failure? Does it behave differently on API 33 vs 32?) → **Robolectric.**

3. **Do you need to *see* something real happen — a Composable actually drawing on screen, a real
   `MediaCodec` actually encoding video, a real camera?** → **Instrumented test on a device.**

> **The golden boundary for OpenLoop:** Robolectric *simulates* the framework. It does **not** run
> real codecs. Our entire media pipeline — `VideoReverser`, `VideoProcessor`, `MediaCodecLifecycle`,
> `ReverseOutputValidator` — produces and inspects *actual encoded video frames*. Robolectric would
> hand those classes a fake `MediaCodec` that encodes nothing, so a "passing" Robolectric test would
> prove **nothing** about whether the loop actually rendered. **Never move the media pipeline to
> Robolectric.** Those tests live in `app/src/androidTest/` for a reason and must stay there.

---

## The setup that's already in this repo

You don't have to configure anything — the wiring is done. Here's what's there and *why*, so you
understand it.

### 1. The dependencies (`app/build.gradle.kts`)

```kotlin
// Testing
testImplementation(libs.junit)
testImplementation(libs.mockk)
testImplementation(libs.kotlinx.coroutines.test)
// Robolectric — run real Android framework code on the JVM at a chosen API level
testImplementation(libs.robolectric)        // org.robolectric:robolectric:4.16.1
testImplementation(libs.androidx.test.core) // ApplicationProvider, etc.
testImplementation(libs.androidx.test.ext.junit) // AndroidJUnit4 runner
testImplementation(libs.androidx.work.testing) // TestListenableWorkerBuilder (Tier 2 #4)
```

All three are `testImplementation` — they're on the **unit-test** classpath (`src/test/`), *not*
the instrumented one. (Remember Lesson 017: `src/androidTest/` can't see these.)

### 2. Resources are turned on (`app/build.gradle.kts`)

```kotlin
testOptions {
    unitTests {
        // Robolectric needs the merged manifest + resources on the unit-test classpath so a test
        // can build real framework objects (notifications, ForegroundInfo, etc.) on the JVM.
        isIncludeAndroidResources = true
    }
}
```

Without this, a Robolectric test that calls `context.getString(R.string.…)` (which our notification
builders do) would not find the resource. This flag hands Robolectric the merged manifest + `res/`.

### 3. Tests run on JDK 21 (the part that trips people up)

The app **compiles** to Java 17 (`compileOptions { sourceCompatibility = VERSION_17 }`). But the JVM
that **runs** the unit tests is pinned to JDK 21:

```kotlin
// app/build.gradle.kts
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}
```

**Why:** Robolectric defaults to the project's `targetSdk` (36), and to simulate API 36 it loads
Android's "android-all" jar for SDK 36 — which Google compiled with **Java 21**. A JDK-17 launcher
**cannot load** that jar and Robolectric tests fail to start. Running Java-17 test bytecode on a
JDK-21 launcher is fully supported, so this unblocks per-API testing without changing the app's
Java-17 toolchain.

> If you ever see a Robolectric test fail with a class-version / "unsupported major.minor" error,
> the cause is almost always a JDK older than 21 running the tests. The toolchain block above
> prevents that on the command line; in Android Studio make sure the Gradle JDK is 21+.

---

## A minimal annotated example

Here's the smallest real Robolectric test, modeled on
`BoomerangRenderForegroundInfoRobolectricTest` (see the [Implemented tests](#implemented-tests-synced-with-appsrc-test) table):

```kotlin
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 1. Run this class under Robolectric (it stands in for AndroidJUnit4 on the JVM).
@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    // 2. A REAL android.content.Context — backed by Robolectric, no device needed.
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34]) // 3. Pretend to be Android 14. Build.VERSION.SDK_INT == 34 inside this test.
    fun behavesCorrectlyOnAndroid14() {
        // 4. Call the REAL production code. It runs the real framework branching for API 34.
        val info = BoomerangRenderNotifications.createForegroundInfo(context, progressPercent = 0)

        // 5. Assert on the real object the framework produced.
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
    }
}
```

The five things every Robolectric test needs:

1. `@RunWith(RobolectricTestRunner::class)` on the class (or `@RunWith(AndroidJUnit4::class)`, which
   resolves to Robolectric in `src/test/` — both work; this repo's Robolectric tests use
   `RobolectricTestRunner` directly).
2. `ApplicationProvider.getApplicationContext()` for a real `Context`.
3. `@Config(sdk = [...])` to choose the Android version. You can pass several:
   `@Config(sdk = [29, 34, 35])` runs the test once per version.
4. Call the **actual production method** — that's the point; you're exercising real framework code.
5. Assert on the real result.

---

## Implemented tests (synced with `app/src/test/`)

Eight test classes cover Phases 1–2 (Tier 1 + Tier 2). Seven use `@RunWith(RobolectricTestRunner)`;
`BoomerangRenderNotificationsTest` is a **plain JVM** companion that pins the FGS SDK mapping without
building framework objects (cheaper, but it cannot catch resource/manifest wiring bugs).

**Full method-level inventory, run-all command, and device complements:**
[`robolectric-test-catalog.md`](robolectric-test-catalog.md).

| Test class | Runner | Tier | What it validates |
|------------|--------|------|-------------------|
| `work/BoomerangRenderForegroundInfoRobolectricTest` | Robolectric | 1 (FGS) | Real `ForegroundInfo.foregroundServiceType` under `@Config(sdk=[34/35/36])` — reproduces Crashlytics 9663c743 |
| `work/BoomerangRenderNotificationsTest` | Plain JVM | 1 (FGS) | `foregroundServiceTypeForSdk()` mapping: API 34 → `dataSync`, API 35+ → `mediaProcessing`, below Q → untyped (`0`) |
| `work/BoomerangRenderNotificationsRobolectricTest` | Robolectric | 1 (channel/progress) | `ensureChannel` idempotency + `IMPORTANCE_LOW`; progress clamp 0–100; `PendingIntent.FLAG_IMMUTABLE` |
| `data/VideoImporterImportRobolectricTest` | Robolectric | 1 (import) | `importToFile` copy success; `false` on `IOException`, `SecurityException`, missing file |
| `data/UserPreferencesRepositoryImplRobolectricTest` | Robolectric | 1 (DataStore) | Real `preferencesDataStore` round-trip: default `false`, write `true` → read `true` |
| `media/DeviceMediaHintsOemRobolectricTest` | Robolectric | 2 (#6) | `ShadowBuild` Samsung/LG identity → `isSamsungDevice()`, preview cap, encoder try-order |
| `work/BoomerangRenderWorkerRobolectricTest` | Robolectric | 2 (#4) | Guard-only `doWork` (invalid input → failure; FGS denied → failure + partial cleanup); `getForegroundInfo` |
| `PostNotificationsGateRobolectricTest` | Robolectric | 2 (#5) | API 32 no-op vs API 33+ gate via `shouldRequestPostNotificationsPermission` / `shouldShowNotificationExportHint` |

Run any of them with `./gradlew :app:testDebugUnitTest --tests "…fully.qualified.ClassName"`.

---

## The highest-value Robolectric opportunities in *this* app

Ranked by value. Each entry says **what** to test, **why Robolectric unlocks it** (which framework
dependency blocks a plain JUnit test today), and **which `@Config(sdk=[…])` levels matter**.

### Tier 1 — do these first

**1. ✅ `work/BoomerangRenderNotifications.kt` — notifications, channel, and FGS type**
- *What:* That `createForegroundInfo` picks `dataSync` below API 35 and `mediaProcessing` on API 35+
  (the exact Android-14 crash, Crashlytics 9663c743); that `ensureChannel` creates a single
  `IMPORTANCE_LOW` channel and is idempotent; that `buildProgressNotification` clamps progress to
  0–100 and sets the right title/text; that the `PendingIntent` is `FLAG_IMMUTABLE`.
- *Why Robolectric:* every line touches `NotificationManager`, `NotificationChannel`,
  `Notification`, `PendingIntent`, `ServiceInfo`, `Build.VERSION.SDK_INT`, and string resources —
  none constructible in a plain JUnit test.
- *`@Config`:* `[26]` (below Q → untyped FGS, value `0`), `[34]` (`dataSync`), `[35]` and `[36]`
  (`mediaProcessing`).
- *Status:* **✅ Done** — `BoomerangRenderForegroundInfoRobolectricTest` + `BoomerangRenderNotificationsTest`
  (FGS type) + `BoomerangRenderNotificationsRobolectricTest` (channel, progress clamp, immutability).

**2. ✅ `data/VideoImporter.kt` (`VideoImporterImpl.importToFile`) — the import error contract**
- *What:* That copying a readable `content://` URI into a dest file succeeds and returns `true`, and
  that an unreadable / revoked URI (`IOException` / `SecurityException`) returns **`false`** instead
  of throwing — the contract the ViewModel relies on to show a friendly snackbar instead of crashing.
- *Why Robolectric:* a plain JUnit test cannot build a working `Uri` (`Uri.parse` is a stub that
  throws in unit tests) or a `ContentResolver`. Robolectric's `ShadowContentResolver` lets you
  register fake stream contents for a URI and drive the real copy logic on the JVM.
- *`@Config`:* not version-sensitive; any SDK is fine.
- *Boundary:* `probeDurationMs` uses `MediaMetadataRetriever` (native decode) — that part stays a
  device test; only the stream-copy contract is a Robolectric win.
- *Status:* **✅ Done** — `VideoImporterImportRobolectricTest` (`ShadowContentResolver`).

**3. ✅ `data/UserPreferencesRepositoryImpl.kt` — real DataStore round-trip, device-free**
- *What:* Write `setOnboardingCompleted(true)` then read `hasCompletedOnboarding.first()` → `true`;
  a fresh store defaults to `false`. This is the **real** `Context.dataStore` delegate end-to-end.
- *Why Robolectric:* `TEST_COVERAGE.md` currently lists this class under *Coverage Gaps* as "needs
  instrumented test with real DataStore." Robolectric supplies the `Context` the
  `preferencesDataStore` delegate needs, so the real DataStore can run on the JVM against a temp file
  — turning a device-only gap into a fast unit test.
- *`@Config`:* not version-sensitive.
- *Boundary:* the existing JVM tests fake this repository with a `MutableStateFlow`; those stay (they
  test the ViewModel). This new test would cover the *real implementation* that the fakes stand in for.
- *Status:* **✅ Done** — `UserPreferencesRepositoryImplRobolectricTest`.

### Tier 2 — strong, with a small caveat

**4. ✅ `work/BoomerangRenderWorker.kt` — orchestration guards only**
- *What:* `doWork()` returns `Result.failure()` on missing/invalid input; `getForegroundInfo()`
  returns a valid `ForegroundInfo`; the FGS-promotion-denied path (`setForeground` throws
  `IllegalStateException`) ends in `Result.failure()` + partial-output cleanup, not a crash.
- *Why Robolectric:* `CoroutineWorker` needs a `Context` and WorkManager's `WorkerParameters`;
  `androidx.work.testing`'s `TestListenableWorkerBuilder` can build and run it on the JVM under
  Robolectric.
- *Caveat / boundary:* `doWork` calls `videoProcessor.renderBoomerang`, which is **real Media3
  Transformer + MediaCodec** — that cannot run under Robolectric and must not be faked. So Robolectric
  covers the *guards around* the render (input parsing, foreground info, the denied-FGS branch), while
  the actual encode stays in `androidTest` (`BoomerangRenderWorkerTest`). `work-testing` is also on the
  `testImplementation` classpath (see `app/build.gradle.kts`).
- *Status:* **✅ Done** — `BoomerangRenderWorkerRobolectricTest` (guard paths only; never reaches encode).

**5. ✅ `MainActivity` permission decisions — the API-33 `POST_NOTIFICATIONS` gate**
- *What:* `maybeRequestPostNotificationsPermission()` / `rememberNotificationExportHint()` do nothing
  below API 33 and only ask/hint when the permission is missing on 33+. The camera-permission
  branching (`granted → ready`, `denied-once → rationale`, `else → request`) is the other piece.
- *Why Robolectric:* `ContextCompat.checkSelfPermission`, `shouldShowRequestPermissionRationale`, and
  the `Build.VERSION.SDK_INT >= TIRAMISU` gate need a real `Context`/Activity and a settable API
  level. Robolectric's `ShadowApplication.grantPermissions(...)` / `denyPermissions(...)` plus
  `@Config(sdk=[32])` vs `[33]` make the branches deterministic.
- *`@Config`:* `[32]` (no POST_NOTIFICATIONS → no-op) vs `[33]` (gate active).
- *Caveat:* the camera-branch logic lives inside an Activity method tied to permission launchers, so
  testing it cleanly may want a small extraction into a pure decision function first. The pure
  *state* transitions are already covered in `OpenLoopViewModelTest`.
- *Status:* **✅ Done** — extracted `shouldRequestPostNotificationsPermission` /
  `shouldShowNotificationExportHint`; `PostNotificationsGateRobolectricTest`.

**6. ✅ `media/DeviceMediaHints.kt` (`isSamsungDevice`)**
- *Tests:* `DeviceMediaHintsOemRobolectricTest` (ShadowBuild Samsung/LG identity, preview cap,
  encoder order). See [`oem-regression-testing.md`](oem-regression-testing.md) lane 2.
- *What:* The manufacturer-OR-brand, case-insensitive Samsung check that gates the Samsung reverse
  workarounds.
- *Why Robolectric:* `Build.MANUFACTURER` / `Build.BRAND` are final statics that read empty in plain
  JUnit. Robolectric's `ShadowBuild.setManufacturer("samsung")` / `setBrand(...)` make both branches
  testable deterministically.
- *`@Config`:* not version-sensitive. (Small win, but trivially cheap.)

### Tier 3 — possible later, not urgent

**7. Move *non-graphical* Compose interaction tests to Robolectric for speed.** Robolectric can run
Compose UI tests on the JVM, which could take some of our `androidTest` onboarding/editor interaction
checks off the emulator and make them much faster. This adds configuration and can be flaky around
graphics, so it's a "when we have time" item — and **screenshot tests
(`LoopifyingScreenshotTest`) and benchmarks stay on a real device.**

*Evaluated June 2026:* skipped for now. The repo has no Robolectric Compose harness yet (would need
`ui-test-junit4` on `testImplementation` + graphics shadow tuning). The only low-hanging candidates
are two `GetStartedButton` tests in `OnboardingScreenTest` — not worth the setup risk until more
non-graphical Compose tests accumulate.

---

## Copy-paste recipes (Tier 1 — shipped)

The recipes below match the checked-in Phase 1 tests. Use them as templates for Tier 2 work.

### Reference: `VideoImporter.importToFile` (ShadowContentResolver)

Implemented in `VideoImporterImportRobolectricTest`. Key patterns:

Plain JUnit cannot construct a working `content://` `Uri` or `ContentResolver`. Robolectric's
`ShadowContentResolver` lets you register fake stream bytes for a URI and drive the real
`VideoImporterImpl.importToFile` copy logic on the JVM.

```kotlin
package io.github.stozo04.openloop.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class VideoImporterImportRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun importToFile_copiesRegisteredContentUri() = runTest {
        val uri = Uri.parse("content://test/import.mp4")
        val payload = "fake-mp4-bytes".toByteArray()
        shadowContentResolver().registerInputStreamSupplier(uri) {
            ByteArrayInputStream(payload)
        }

        val dest = File(context.cacheDir, "imported.mp4")
        val importer = VideoImporterImpl(context)

        assertTrue(importer.importToFile(uri, dest))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun importToFile_returnsFalseWhenUriHasNoRegisteredStream() = runTest {
        val uri = Uri.parse("content://test/revoked.mp4")
        // No registration → openInputStream fails during copy → importToFile returns false (never throws)
        val dest = File(context.cacheDir, "imported.mp4")
        val importer = VideoImporterImpl(context)

        assertFalse(importer.importToFile(uri, dest))
    }

    @Test
    fun importToFile_returnsFalseWhenStreamThrowsOnRead() = runTest {
        val uri = Uri.parse("content://test/corrupt.mp4")
        shadowContentResolver().registerInputStream(uri, object : InputStream() {
            override fun read(): Int = throw java.io.IOException("simulated revoked URI")
        })

        val dest = File(context.cacheDir, "imported.mp4")
        val importer = VideoImporterImpl(context)

        assertFalse(importer.importToFile(uri, dest))
    }

    private fun shadowContentResolver(): ShadowContentResolver =
        Shadows.shadowOf(context.contentResolver)
}
```

> Use `registerInputStreamSupplier` (not `registerInputStream`) when a test opens the same URI more
> than once — Robolectric closes a registered stream after the first read. Unregistered `content://`
> URIs return Robolectric's `UnregisteredInputStream` (not real device behavior) — register explicit
> streams for failure cases instead.

### Reference: `UserPreferencesRepositoryImpl` (DataStore round-trip)

Implemented in `UserPreferencesRepositoryImplRobolectricTest`. Key patterns:

Robolectric supplies the real `Context` the top-level `preferencesDataStore` delegate needs, so
the production `UserPreferencesRepositoryImpl` can run against a temp file on the JVM — turning the
"needs instrumented test with real DataStore" gap in `TEST_COVERAGE.md` into a fast unit test.

```kotlin
package io.github.stozo04.openloop.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserPreferencesRepositoryImplRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDataStoreFile() {
        // preferencesDataStore(name = "openloop_preferences") → this path under filesDir/datastore/
        File(context.filesDir, "datastore/openloop_preferences.preferences_pb").delete()
    }

    @Test
    fun freshStore_defaultsOnboardingIncomplete() = runTest {
        val repo = UserPreferencesRepositoryImpl(context.dataStore)

        assertFalse(repo.hasCompletedOnboarding.first())
    }

    @Test
    fun setOnboardingCompleted_persistsAndReadsBack() = runTest {
        val repo = UserPreferencesRepositoryImpl(context.dataStore)

        repo.setOnboardingCompleted(true)

        assertTrue(repo.hasCompletedOnboarding.first())
    }
}
```

> The `@Before` delete matters: DataStore is a process-wide singleton keyed by file name. Without
> clearing it, test order can leak state between methods.

---

## What must NEVER move to Robolectric

These are real-device tests for a real reason. Faking them would produce green tests that prove
nothing:

| Code | Why it must stay on a device |
|---|---|
| `media/VideoReverser.kt`, `media/VideoProcessor.kt`, `media/MediaCodecLifecycle.kt`, `media/ReverseOutputValidator.kt` | Real `MediaCodec` encode/decode + Media3 Transformer. Robolectric's `MediaCodec` shadow encodes nothing — the output loop wouldn't exist to validate. |
| `camera/CameraManager.kt` | Real CameraX + camera hardware. |
| `MediaMetadataRetriever` paths (`VideoStorageRepositoryImpl` thumbnails/duration, `VideoImporterImpl.probeDurationMs`) | Native frame decode; no real frames under Robolectric. |
| `LoopifyingScreenshotTest`, `media/LoopifyingBenchmarkTest.kt` | Real pixels / real performance numbers. |

And the flip side — **already pure JUnit, leave them alone** (don't add Robolectric where it buys
nothing): `media/BoomerangSequence.kt`, the lambda-based pure functions in `media/MediaFormatUtils.kt`
(`resolveFrameRate` / `resolveRotationDegrees` were *deliberately* designed to be testable without a
real `MediaFormat`), `work/BoomerangRenderWorkerInput.kt` parsing, the `VideoStorageRepositoryImpl`
file-path/timestamp/migration logic (already JVM-tested with `TemporaryFolder` — Lesson 008), and all
the `OpenLoopViewModel` state-transition tests.

---

## Common pitfalls

- **Don't fake the media pipeline.** (Said three times on purpose.) If a test's value depends on real
  encoded video, it belongs on a device. A Robolectric `MediaCodec` is a hollow shell.
- **SDK 36 needs JDK 21.** A class-version / "unsupported major.minor" failure at test startup means
  the tests are running on a JDK older than 21. The toolchain block in `build.gradle.kts` handles the
  CLI; set Android Studio's Gradle JDK to 21+ too.
- **Pick the `@Config(sdk=[…])` that matters — don't just default.** OpenLoop's bugs are
  version-specific. If the behavior under test is version-gated, *name the versions* on both sides of
  the boundary (e.g. `[34]` and `[35]` for FGS type; `[32]` and `[33]` for POST_NOTIFICATIONS). A
  test with no `@Config` runs at `targetSdk` (36) only and would have missed the Android-14 crash.
- **Resources need `isIncludeAndroidResources = true`.** Already set. If you split modules later and
  resource lookups (`getString`) start returning nothing in a Robolectric test, this flag is the
  first thing to check.
- **`src/test/` only.** Robolectric, `mockk`, and the JVM fakes are invisible to `src/androidTest/`
  (Lesson 017). Don't try to import a Robolectric test helper into an instrumented test.

---

## How to run a single Robolectric test

It's a normal unit test, so the normal unit-test commands work (it just happens to run framework
code). From the repo root:

```bash
# Run one test CLASS
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderForegroundInfoRobolectricTest"

# Run one test METHOD
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderForegroundInfoRobolectricTest.android14_foregroundInfo_isDataSync_reproducesAndProvesFix"

# Run every unit test (plain + Robolectric) in the debug variant
./gradlew :app:testDebugUnitTest

# Run every *Robolectric-named* test class only
./gradlew :app:testDebugUnitTest --tests "*RobolectricTest"
```

On Windows use `gradlew.bat` instead of `./gradlew`. In Android Studio you can also click the green
gutter arrow next to the test — just confirm the Gradle JDK is 21+ first (see the pitfalls above).

> First run is slow: Robolectric downloads the "android-all" jar for each `@Config` SDK level once,
> then caches it. Subsequent runs are fast.

---

## Glossary

| Term | Plain meaning |
|------|---------------|
| **Robolectric** | A library that runs *simulated* Android framework code inside a normal JVM unit test — no device. Can pretend to be any Android version. |
| **JVM** | The "Java Virtual Machine" — what runs your Kotlin/Java on your laptop. |
| **Unit test (`src/test/`)** | A fast test that runs on the JVM, no device. |
| **Instrumented test (`src/androidTest/`)** | A test that runs on a real device/emulator with the real Android framework. |
| **`@Config(sdk=[34])`** | A Robolectric annotation that makes the test pretend to run on a specific Android API level (here, Android 14). |
| **`ApplicationProvider.getApplicationContext()`** | The Robolectric-friendly way to get a real `Context` in a test. |
| **Shadow** | Robolectric's stand-in for a framework class (e.g. `ShadowBuild`, `ShadowContentResolver`) you configure from the test. |
| **FGS** | Foreground Service — a long-running, user-visible background task (OpenLoop uses one to export loops). |
| **`@Config`** | Robolectric annotation for per-test config — most importantly the SDK level(s). |
| **`testImplementation`** | A dependency available only to `src/test/` (unit tests), not the app or instrumented tests. |
| **targetSdk / compileSdk** | The Android version the app targets (36 here). Robolectric defaults its simulated version to this. |
| **MediaCodec / Media3 Transformer** | Android's real video encode/decode engines. Robolectric does *not* run these for real — that's why the media pipeline stays on a device. |

---

## Want the deeper version?

- Robolectric tests in this repo:
  - `app/src/test/java/io/github/stozo04/openloop/work/BoomerangRenderForegroundInfoRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/work/BoomerangRenderNotificationsRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/data/VideoImporterImportRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/data/UserPreferencesRepositoryImplRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/media/DeviceMediaHintsOemRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/work/BoomerangRenderWorkerRobolectricTest.kt`
  - `app/src/test/java/io/github/stozo04/openloop/PostNotificationsGateRobolectricTest.kt`
- Plain-JVM companion (same FGS contract, no framework objects):
  `app/src/test/java/io/github/stozo04/openloop/work/BoomerangRenderNotificationsTest.kt`
- The class under test for notifications: `app/src/main/java/io/github/stozo04/openloop/work/BoomerangRenderNotifications.kt`.
- The testing strategy and pyramid: `docs/TEST_COVERAGE.md`.
- Why JVM File tests use a real temp dir and one dispatcher: `docs/lessons_learned/008-jvm-test-file-and-dispatcher-pitfalls.md`.
- Why `mockk` and JVM fakes can't be used in `androidTest`: `docs/lessons_learned/017-androidtest-no-mockk-and-sweep-meaningful-mock-returns.md`.
- Robolectric's official docs: [robolectric.org](https://robolectric.org/) · Google's testing guidance: [Build local unit tests](https://developer.android.com/training/testing/local-tests).
