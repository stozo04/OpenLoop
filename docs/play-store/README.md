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

## Notes

- **GitHub URLs** point at `github.com/stozo04/OpenLoop` (the repo's current name). It was formerly
  `OpenLoop`; GitHub auto-redirects the old URLs, so any older links still resolve.
- Keep `data-safety.md` and the live privacy policy (`docs/privacy-policy.html` +
  `privacy-policy.md`) in sync with the merged manifest and Firebase SDKs before each release.
