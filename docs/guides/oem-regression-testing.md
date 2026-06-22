# OEM Regression Testing — API 34, Samsung, and LG

OpenLoop ships to billions of devices. Crashes and media bugs often appear on **one OEM or one
API level** and nowhere else. This guide documents the three regression lanes we added so those
bugs are caught **before** Crashlytics — without pretending a stock emulator is a Galaxy or an
LG phone when it isn't.

**Related docs:**

| Doc | Role |
|-----|------|
| [`docs/TEST_COVERAGE.md`](../TEST_COVERAGE.md) | Overall testing pyramid and inventory |
| [`docs/guides/robolectric-testing-explained.md`](robolectric-testing-explained.md) | Robolectric setup and when to use it |
| [`docs/guides/robolectric-test-catalog.md`](robolectric-test-catalog.md) | Inventory of all Robolectric tests, run commands, device complements |
| [`docs/guides/samsung-rtl-steps.md`](samsung-rtl-steps.md) | One-time Samsung Remote Test Lab (RTL) setup |
| [`docs/e2e/2026-06-22_082330-api34-fgs-fix.md`](../e2e/2026-06-22_082330-api34-fgs-fix.md) | First verified API-34 FGS fix run |
| [`docs/lessons_learned/024-fgs-type-constant-api-gating.md`](../lessons_learned/024-fgs-type-constant-api-gating.md) | FGS type must gate on the API that *added* the constant |

**Agent skills** (same content, runnable commands): `.claude/skills/run-e2e-pixel-sweep/SKILL.md`
(OEM lanes + 4-emulator sweep) and `.claude/skills/run-e2e/SKILL.md` (manual E2E + quick table).

---

## The core idea: match the lane to the bug class

Not every OEM bug can be reproduced the same way. Pick the lane that actually exercises the
failing layer:

| Bug class | Example | Lane | Runs on |
|-----------|---------|------|---------|
| **Stock Android framework** | FGS type rejected on API 34 (`InvalidForegroundServiceTypeException`) | **API 34 AVD** | Emulator |
| **App logic gated on `Build.MANUFACTURER`** | Samsung 480p preview cap, `c2.google` encoder first | **Robolectric + ShadowBuild** | JVM (seconds) |
| **Vendor MediaCodec stack** | Exynos/QC reverse wedge, Samsung RTL regressions | **Samsung RTL sweep** | Real Galaxy (cloud) |
| **Hardware codec init failure** | LG LM-X540 `IllegalArgumentException: start failed` | **Instrumented fault injection** | Any emulator/device |

**Critical honesty rule:** A green run on a **stock Google emulator does not prove** a Samsung or
LG codec bug is fixed. Emulator codecs are `c2.android` / goldfish software paths — not Exynos,
QTI, or LG hardware. We verified (2026-06-22) that `-prop ro.product.manufacturer=samsung` on
Google Play images is **ignored**; the device still reports `Google`.

---

## Lane 1 — Android 14 (API 34) foreground-service regression

### What it catches

Crashlytics **9663c743** (Galaxy A55, Android 14, v1.0.23): every Loopify save crashed because
the app passed `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (8192) on API 34, but that type was
**added in API 35**. The OS rejected it as `Starting FGS with type unknown`.

### AVD: `Pixel_8_API34`

Create once (requires SDK image `system-images;android-34;google_apis_playstore;x86_64`):

```powershell
pwsh .claude/skills/run-e2e-pixel-sweep/scripts/create-api34-avd.ps1
emulator -list-avds   # should include Pixel_8_API34
```

### How to run

**Option A — part of the 4-device pixel sweep** (import path, scripted):

Order: `Pixel_6` → `Pixel_8` → `Pixel_10_Pro_Fold` → **`Pixel_8_API34`** (last = mandatory FGS gate).

See `.claude/skills/run-e2e-pixel-sweep/SKILL.md` for the full sweep.

**Option B — manual capture→save** (exercises camera + save):

See `.claude/skills/run-e2e/SKILL.md`. Cold-boot `Pixel_8_API34`, record a clip, change one
setting per editor tab, tap Save, confirm share sheet shows `boom_*.mp4`.

### Pass criteria

- `Worker result SUCCESS … BoomerangRenderWorker` in logcat
- **Zero** lines matching `InvalidForegroundServiceTypeException` or `Starting FGS with type unknown`
- (`scan-logcat.ps1` includes an **FGS type unknown (API 34 regression)** CRASH row — count must be 0)

### Unit / Robolectric proof (same bug, no device)

| Test | File |
|------|------|
| SDK → FGS type mapping | `BoomerangRenderNotificationsTest` |
| Real `ForegroundInfo` on API 34 | `BoomerangRenderForegroundInfoRobolectricTest` |

```powershell
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderNotificationsTest"
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.work.BoomerangRenderForegroundInfoRobolectricTest"
```

---

## Lane 2 — Samsung identity and codec policy (Robolectric)

### What it catches

App branches that read **`Build.MANUFACTURER` / `Build.BRAND`**:

- `isSamsungDevice()` → 480p preview reverse cap (`previewReverseMaxShortSideOrNull()`)
- Samsung-first AVC encoder try-order (`c2.google.avc.encoder`, vendor codecs excluded)

These are **policy** tests. They do not run real Exynos codecs.

### Test file

`app/src/test/java/io/github/stozo04/openloop/media/DeviceMediaHintsOemRobolectricTest.kt`

Uses Robolectric **`ShadowBuild.setManufacturer()` / `setBrand()`** to simulate Galaxy vs LGE vs
Google emulator identity on the JVM.

Companion JVM tests (explicit `isSamsung=true` parameter, no ShadowBuild):

- `SamsungReversePreviewRegressionTest` — encoder ranking regressions from Samsung RTL logcat

### How to run

```powershell
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.media.DeviceMediaHintsOemRobolectricTest"
./gradlew :app:testDebugUnitTest --tests "io.github.stozo04.openloop.media.SamsungReversePreviewRegressionTest"
```

### Pass criteria

All tests green. Samsung shadows → cap `480` and `c2.google.avc.encoder` first; LGE/Google → no cap,
no Samsung-only encoder pinning.

### When to run

- Any change to `DeviceMediaHints.kt`, `ReverseEncoderSelection.kt`, or ViewModel preview-reverse cap
- Before/after media-pipeline PRs that touch Samsung carve-outs

---

## Lane 3 — Samsung real hardware (RTL sweep)

### What it catches

**Vendor codec behavior** that emulators cannot model: Exynos/QC reverse wedges, preview timeouts,
real `ensureReversed.ok` / `Worker result SUCCESS` on Galaxy hardware.

### Prerequisites

1. Read [`samsung-rtl-steps.md`](samsung-rtl-steps.md) — reserve a Galaxy, start RDB, fix adb PATH
2. Build debug APK once
3. Canonical test video at repo root: `google-pro-fold-video.mp4` (gitignored — see pixel-sweep skill)

### How to run

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug

# adb devices must show localhost:<port> (port changes every RTL session)
pwsh .claude/skills/run-e2e-pixel-sweep/scripts/samsung-rtl-sweep.ps1 `
  -ArtifactDir $env:TEMP\openloop_rtl\s23

# Optional explicit serial:
# pwsh .../samsung-rtl-sweep.ps1 -Serial localhost:52172 -ArtifactDir ...
```

Scripts:

| Script | Purpose |
|--------|---------|
| `samsung-rtl-prep.ps1` | Install, `pm clear`, push video, logcat, launch (refuses non-Samsung) |
| `samsung-rtl-sweep.ps1` | prep → `drive-flow.ps1` → `quality-gate.ps1` → `scan-logcat.ps1` |

### Pass criteria

- All `drive-flow` steps `[PASS]`
- `quality-gate` PASS (fps, SSIM, no green/black frames)
- `scan-logcat`: **0** CRASH and TIMEOUT rows
- Logcat contains `ensureReversed.ok` and `Worker result SUCCESS … BoomerangRenderWorker`

### When to run

- Media pipeline / reverser / encoder selection changes
- Before release if Samsung Crashlytics issues are open
- **Not** required for pure UI changes with no media touch

---

## Lane 4 — LG `start failed` recovery (instrumented fault injection)

### What it catches

Crashlytics **47233ad7** (LG LM-X540): hardware AVC codec throws
`IllegalArgumentException: start failed` on first `MediaCodec.start()`. The app must retry on
**software** encoder + decoder and still produce a valid reversed clip.

LG has **no** public Remote Test Lab equivalent to Samsung RTL.

### Test

`VideoReverserTest.reverse_recoversFromCodecStartFailure_viaSoftwareFallback`

Injects the exact failure on attempt 0 via `VideoReverser.attemptHook`, then asserts recovery.

### How to run

```powershell
.\gradlew.bat :app:assembleDebug :app:installDebug

adb shell am instrument -w -r `
  -e class io.github.stozo04.openloop.media.VideoReverserTest#reverse_recoversFromCodecStartFailure_viaSoftwareFallback `
  io.github.stozo04.openloop.test/androidx.test.runner.AndroidJUnitRunner
```

Pass: `tests=1 failures=0 errors=0`.

**Literal LM-X540 hardware** is still the only way to prove the vendor codec actually rejects
`start()` — Test Lab rarely lists LG devices.

---

## Quick reference — which command when?

| You're changing… | Run at minimum |
|------------------|----------------|
| FGS / WorkManager / notifications | API 34 unit + Robolectric FGS tests; API 34 AVD save smoke |
| `DeviceMediaHints` / Samsung encoder order | `DeviceMediaHintsOemRobolectricTest` + `SamsungReversePreviewRegressionTest` |
| `VideoReverser` / reverse pipeline | 4-emulator pixel sweep + Samsung RTL if Samsung-specific |
| Anything touching save/render | Pixel sweep includes API 34; grep logcat for FGS CRASH row |
| LG codec fallback logic | `VideoReverserTest#reverse_recoversFromCodecStartFailure_viaSoftwareFallback` |

---

## File map (everything added for OEM regression)

| Path | Purpose |
|------|---------|
| `app/src/test/.../DeviceMediaHintsOemRobolectricTest.kt` | ShadowBuild Samsung/LG identity tests |
| `app/src/test/.../BoomerangRenderForegroundInfoRobolectricTest.kt` | API 34/35/36 FGS type on real `ForegroundInfo` |
| `app/src/test/.../BoomerangRenderNotificationsTest.kt` | Pure SDK → FGS type mapping |
| `app/src/main/.../DeviceMediaHints.kt` | `previewReverseMaxShortSideOrNull()` helper |
| `.claude/skills/run-e2e-pixel-sweep/scripts/create-api34-avd.ps1` | Create `Pixel_8_API34` AVD |
| `.claude/skills/run-e2e-pixel-sweep/scripts/samsung-rtl-prep.ps1` | RTL device prep |
| `.claude/skills/run-e2e-pixel-sweep/scripts/samsung-rtl-sweep.ps1` | Full RTL regression |
| `.claude/skills/run-e2e/scripts/scan-logcat.ps1` | Includes FGS-type-unknown CRASH signature |
| `docs/e2e/2026-06-22_082330-api34-fgs-fix.md` | First live API-34 pass report + screenshots |

---

## Adding a new OEM regression lane

When Crashlytics shows a **device- or API-specific** crash:

1. Classify it (framework vs identity-gated logic vs vendor codec vs injectable failure).
2. Add the **cheapest lane that reproduces the real failure layer** — don't default to "boot an emulator."
3. Document the lane here + add a row to the pixel-sweep / run-e2e skill OEM table.
4. If it's a new pattern, add a lesson in `docs/lessons_learned/`.
