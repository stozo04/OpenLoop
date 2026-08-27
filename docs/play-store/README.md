# Play Store submission pack — OpenLoop

Everything needed to publish **OpenLoop** (`io.github.stozo04.openloop`) to Google Play. The forms
themselves live in the Play Console (web), so each doc here is the **source-of-truth text to paste
in**. Keep these in sync if the app's behavior changes.

| Doc                                                                                           | What it's for                                                                                 | Where it goes in the Console                 |
| --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- | -------------------------------------------- |
| [`privacy-policy.md`](privacy-policy.md) + [`../privacy-policy.html`](../privacy-policy.html) | Privacy policy (live at the Pages URL)                                                        | App content → Privacy policy + store listing |
| [`data-safety.md`](data-safety.md)                                                            | Data safety answers (Firebase Analytics + Crashlytics **declared** — not "no data collected") | App content → Data safety                    |
| [`content-rating.md`](content-rating.md)                                                      | IARC questionnaire answers (expected: Everyone/PEGI 3)                                        | App content → Content ratings                |
| [`store-listing.md`](store-listing.md)                                                        | Title, descriptions, asset specs, screenshot checklist                                        | Store presence → Main store listing          |
| [`release-signing-and-aab.md`](release-signing-and-aab.md)                                    | Generate the upload key + build the signed `.aab`                                             | Release → upload bundle                      |

---

## Pre-submission checklist

### Done in the repo ✅

- [x] **App Bundle build** wired (`./gradlew :app:bundleRelease` → signed `.aab` once `keystore.properties` is set).
- [x] **`targetSdk 36`** — exceeds Play's API-35 floor for new apps.
- [x] **16 KB-aligned native libs** (uncompressed packaging).
- [x] **Minimal permissions** — `CAMERA`; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING` + `FOREGROUND_SERVICE_DATA_SYNC` for the render worker (Play Console FGS declaration needed for both types); legacy `WRITE_EXTERNAL_STORAGE` ≤ API 28 for the MediaStore publish; `INTERNET` only for Firebase Analytics/Crashlytics; no `POST_NOTIFICATIONS`; photo import uses Photo Picker (no storage permission).
- [x] **applicationId `io.github.stozo04.openloop`** — permanent, verified unused on Play.
- [x] **Store-listing graphics** — [`play_store_icon_512.png`](play_store_icon_512.png) (512×512, no alpha, no baked corners) and [`main-image.png`](main-image.png) (1024×500, no alpha) ready to upload. Brand-asset overview in the root [`README.md` → Brand Assets](../../README.md#brand-assets).

### You do — developer account & build (can start in parallel; account verification takes days)

- [ ] Create a **Google Play Developer account** ($25 + identity verification).
- [ ] Generate the **upload keystore** and fill `keystore.properties` (see `release-signing-and-aab.md`).
- [ ] `./gradlew :app:bundleRelease` → grab `app/build/outputs/bundle/release/app-release.aab`.

### You do — Play Console (paste from these docs)

- [ ] Create the app: name **OpenLoop**, default language, **App**, **Free**.
- [ ] Enable **Play App Signing**.
- [ ] **Privacy policy** URL -> `https://stozo04.github.io/OpenLoop/privacy-policy.html` (auto-hosted via GitHub Pages from docs/privacy-policy.html).
- [ ] **Data safety** → declare Firebase Analytics/Crashlytics collection per `data-safety.md` (not "no data collected").
- [ ] **Content rating** questionnaire (`content-rating.md`).
- [ ] **Store listing**: title, short + full description (`store-listing.md`), **app icon 512×512**, **feature graphic 1024×500**, **≥2 phone screenshots** (capture during device QA — checklist in `store-listing.md`).
- [ ] **App category** = Video Players & Editors; **Contains ads** = No; **In-app purchases** = No; **Target audience** = 13+.
- [ ] Upload the `.aab` to **Internal testing**, verify on-device, then promote to **Production**.

### Strongly recommended before production

- [ ] **Real-device QA pass** across a few API levels (26 / 29 / 30 / 33 / 36) and chipsets — capture, import (incl. a 10-bit HDR and a 4K clip), render, share. The riskiest paths (HDR/4K transcode, reverse) are device-dependent.

---

## Technical quality requirements (enforced Feb / Apr 2027)

Play's [technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799)
gained three memory/code rules and one sign-in rule on 2026-08-26 ([announcement](https://android-developers.googleblog.com/2026/08/app-quality-memory-optimization-secure-onboarding.html)).
Missing one costs store visibility and publishing capabilities. The audit and its follow-ups live in
[Issue #148](https://github.com/stozo04/OpenLoop/issues/148); this is the standing checklist.

| Rule (P90 over 28 days, phones + tablets)                                              | Threshold for **apps**                                                                      | OpenLoop                                                                                                                                           |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Code optimization (Feb 2027) — apps with > 10 MB DEX                                   | ≥ 25 % optimization, obfuscation **and** shrinking                                          | ✅ Bundle 48: 97 / 97 / 97 %, R8 full mode (App bundle explorer → *App optimization*). Release DEX is 8.6 MB, likely below the scope floor anyway.  |
| Memory usage, anonymous RSS + swap (Feb 2027)                                          | 4 GB tier: 2 GB foreground / 1 GB user-perceived services / 1 GB background; scales by tier | ⚠️ No vitals data yet (install base too small). Measure locally — Issue #148 item 2.                                                               |
| Bitmap memory usage (Feb 2027)                                                         | 200 MB user-perceived services & background, 400 MB cached; none in foreground              | ✅ Trim filmstrip decodes at tile size (Issue #149): 4K import on Trim, Pixel 8 emulator, `Bitmap (malloced)` 253 MB → 1.2 MB after GC.             |
| Zero-tap sign-in restoration (Apr 2027) — apps with any user sign-in                   | Restore sign-in on a new device via the Restore Credentials API                             | ✅ N/A — no accounts or sign-in. Activates the day sign-in is added.                                                                                |

**Before every release, and monthly from Feb 2027:** Play Console → Monitor and improve → Android vitals →
Overview → *Memory* rows (*Memory usage (anonymous RSS and swap)*, *Bitmap memory usage*) — P90 per
process state under the numbers above, and P90 / P50 below 3.5× (Play's leak signal). Then App bundle
explorer → the live bundle → *App optimization* stays *High*. Reproduce the optimization number locally
with `./gradlew :app:analyzeReleaseR8Config` (`app/build/reports/r8/r8-config-analyzer-release.html`);
its percentages are a pre-optimization keep-rule proxy, Play's are the compliance figure.

---

## Notes

- **GitHub URLs** point at `github.com/stozo04/OpenLoop` (the repo's current name). It was formerly
  `OpenLoop`; GitHub auto-redirects the old URLs, so any older links still resolve.
- Keep `data-safety.md` and the live privacy policy (`docs/privacy-policy.html` +
  `privacy-policy.md`) in sync with the merged manifest and Firebase SDKs before each release.
