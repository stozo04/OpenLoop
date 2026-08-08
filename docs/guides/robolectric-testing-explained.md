# Robolectric in OpenLoop — when to use it, and what must never move to it

Robolectric runs real Android framework code (`Notification`, `ServiceInfo`, `Uri`,
`ContentResolver`, `Build.VERSION`) on the JVM in milliseconds, and can **pretend to be a specific
Android version** via `@Config(sdk=[34])`. That last part is why it earns its place here: OpenLoop's
worst bugs have been version-specific (the Loop-export crash only fired on Android 14 — Lesson 024).

General Robolectric usage: [robolectric.org](https://robolectric.org/). This file covers only what is
specific to *this* repo.

OEM / API-34 / Samsung RTL lanes: [`oem-regression-testing.md`](oem-regression-testing.md).
Testing strategy and pyramid: [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).

---

## Which of the three test types to use

| | Plain JUnit (`test/`) | **Robolectric** (`test/`) | Instrumented (`androidTest/`) |
|---|---|---|---|
| Runs on | Laptop JVM | Laptop JVM | Device / emulator |
| Speed | ms | tens–hundreds of ms | seconds (build + install) |
| Android APIs | No | **Yes — simulated** | Yes — **real** |
| Fake an OS version | No | **Yes — `@Config(sdk=[…])`** | No |
| Real pixels / codecs / camera | No | **No** | **Yes** |

1. **Touches zero Android APIs** (math, parsing, state transitions) → **plain JUnit**.
2. **Touches the framework, but you only care about the objects and branching it produces** — did it
   pick the right `ServiceInfo` type? build the right notification channel? behave differently on API
   33 vs 32? → **Robolectric**.
3. **Needs something real to happen** — a Composable actually drawing, a real `MediaCodec` actually
   encoding → **instrumented, on a device**.

---

## What must NEVER move to Robolectric

Faking these produces green tests that prove nothing:

| Code | Why it stays on a device |
|---|---|
| `media/VideoReverser.kt`, `VideoProcessor.kt`, `MediaCodecLifecycle.kt`, `ReverseOutputValidator.kt` | Real `MediaCodec` + Media3 Transformer. Robolectric's `MediaCodec` shadow **encodes nothing**, so there would be no output loop to validate. |
| `camera/CameraManager.kt` | Real CameraX + camera hardware. |
| `MediaMetadataRetriever` paths (`VideoStorageRepositoryImpl` thumbnails/duration, `VideoImporterImpl.probeDurationMs`) | Native frame decode; no real frames under Robolectric. |
| `LoopifyingScreenshotTest`, `media/LoopifyingBenchmarkTest.kt` | Real pixels / real performance numbers. |

This is the same vacuous-pass trap as Lesson 011 (`zipalign` reporting OK on compressed libs) and
Lesson 023 (a zero-sample mux exiting "cleanly"): the check runs, stays green, and verifies nothing.

**Flip side — already pure JUnit, don't add Robolectric:** `media/BoomerangSequence.kt`, the pure
functions in `media/MediaFormatUtils.kt`, `work/BoomerangRenderWorkerInput.kt` parsing, the
`VideoStorageRepositoryImpl` path/timestamp logic (JVM-tested with `TemporaryFolder` — Lesson 008),
and all `OpenLoopViewModel` state-transition tests.

---

## Pitfalls

- **Pick the `@Config(sdk=[…])` that matters — don't default.** If behavior is version-gated, name the
  versions on **both sides** of the boundary (`[34]`/`[35]` for FGS type; `[32]`/`[33]` for
  POST_NOTIFICATIONS). No `@Config` runs at `targetSdk` (36) only — which would have missed the
  Android-14 crash entirely.
- **SDK 36 needs JDK 21.** Robolectric loads Google's `android-all` jar for SDK 36, compiled with
  Java 21; a JDK-17 launcher cannot load it. `app/build.gradle.kts` pins the test launcher to 21 for
  the CLI — set Android Studio's **Gradle JDK to 21+** as well. A class-version / "unsupported
  major.minor" failure at startup is always this.
- **Resources need `isIncludeAndroidResources = true`.** Already set; check it first if `getString`
  starts returning nothing.
- **`src/test/` only.** Robolectric, `mockk`, and the JVM fakes are invisible to `src/androidTest/`
  (Lesson 017).

---

## Run commands

From the repo root (Windows: `gradlew.bat`):

```bash
# Every Robolectric-named test class
./gradlew :app:testDebugUnitTest --tests "*RobolectricTest"

# One class
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderWorkerRobolectricTest"

# One method
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderForegroundInfoRobolectricTest.android14_foregroundInfo_isDataSync_reproducesAndProvesFix"

# Full local unit suite (plain JUnit + Robolectric)
./gradlew :app:testDebugUnitTest
```

The live inventory is the suite itself — `--tests "*RobolectricTest"` lists what exists. Phase 3
(non-graphical Compose on the JVM) remains deferred: no Robolectric Compose harness is wired up.
