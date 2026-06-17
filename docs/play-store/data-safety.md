# OpenLoop — Data Safety section answers

Transcribe these into **Play Console → Policy → App content → Data safety**. The Data safety form is
a Play Console web form (it can't live in the repo), so this file is the source-of-truth for the
answers to enter. Keep it in sync if the app's behavior ever changes.

> **Why this app DOES collect data.** As of versionCode 22 (1.0.22) OpenLoop bundles **Google
> Analytics for Firebase** (`firebase-analytics`) and **Firebase Crashlytics** (`firebase-crashlytics`).
> Both transmit data off-device to Google, which Play's policy defines as *collection*. The merged
> release manifest therefore carries `INTERNET` + `ACCESS_NETWORK_STATE` (auto-merged by the
> measurement SDK — see `app/src/main/AndroidManifest.xml` and the build's
> `merged_manifest/release/.../AndroidManifest.xml`). The earlier "no data collected" declaration is
> **no longer accurate** and must not be re-used.
>
> **Video stays on-device.** Captured/imported video and the boomerangs you export are still processed
> **100% on-device** and are **never** transmitted by the app. Only limited, pseudonymous usage
> analytics and crash diagnostics leave the device. Sharing is user-initiated through Android's system share sheet
> (the user picks the destination; Android performs the transfer) and is **not** OpenLoop collecting or
> sharing data.
>
> **Advertising ID is NOT collected.** The `com.google.android.gms.permission.AD_ID` permission is
> stripped via `tools:node="remove"` and ad-ID collection is disabled with
> `google_analytics_adid_collection_enabled=false`. The merged manifest confirms AD_ID is absent. So
> **do not** declare "Advertising ID" — declaring it would be inaccurate the other direction.
>
> Sources: [Provide info for the Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469),
> [Prepare for Play's data disclosure requirements (Firebase)](https://firebase.google.com/docs/android/play-data-disclosure),
> [Google Analytics for Firebase data disclosure](https://support.google.com/analytics/answer/11582702).

---

## Top-level form answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** — Firebase transmits over HTTPS/TLS ([source](https://firebase.google.com/docs/android/play-data-disclosure)). |
| Do you provide a way for users to request that their data be deleted? | **No.** The diagnostics are pseudonymous and not linked to any name, email, or account, so there is no way to locate an individual user's records to action a per-user deletion request. The controls that exist: uninstalling stops all collection; clearing app storage (or uninstalling) resets the random installation identifiers; Firebase auto-deletes the data after its retention window. *(See "Open decision" below.)* |

For **every** declared type below: **Collected = Yes, Shared = No** (Google acts as OpenLoop's data
processor for Analytics/Crashlytics — using these Google SDKs is "collection" but not third-party
"sharing"), **Processed ephemerally = No** (Firebase retains the data), **Users can choose whether
it's collected = No / required** (there is no in-app opt-out toggle yet — see "Open decision").

---

## Data types to declare

### From Google Analytics for Firebase

| Category | Data type | Purpose(s) | Source of the data |
|---|---|---|---|
| **App activity** | **App interactions** | Analytics | Automatic events (`first_open`, `session_start`, `screen_view`) + the app's custom events (export success/failure, editor direction/speed/filter changes, gallery/import events) per `docs/active/firebase-analytics/IMPLEMENTATION.md` §4. |
| **Location** | **Approximate location** | Analytics | Coarse location Google Analytics derives from the (masked) IP address. Not GPS; the app holds no location permission. |
| **Device or other IDs** | **Device or other IDs** | Analytics | Firebase **App Instance ID** (pseudonymous). |

> **Advertising ID — NOT declared.** Disabled in the manifest (see header note). Do not check it.
> **In-app purchases — NOT declared.** OpenLoop has no IAP, so no purchase events are generated.

### From Firebase Crashlytics

| Category | Data type | Purpose(s) | Source of the data |
|---|---|---|---|
| **App info and performance** | **Crash logs** | App functionality, Analytics | Stack traces + app state at crash time; custom keys/logs OpenLoop attaches (e.g. the reverse-preview non-fatals `ReverseCrashlytics` records). |
| **App info and performance** | **Diagnostics** | App functionality, Analytics | Point-in-time device metadata/performance state captured with each crash report. |
| **Device or other IDs** | **Device or other IDs** | App functionality, Analytics | Crashlytics **installation UUID** (counts users affected by a crash). Same category as the Analytics App Instance ID above — declare the category once and cover both. |

---

## Consolidated checklist (what to tick in the Console)

- [x] **App activity → App interactions** — Collected, not shared, not ephemeral, required. Purpose: Analytics.
- [x] **App info and performance → Crash logs** — Collected, not shared, not ephemeral, required. Purpose: App functionality, Analytics.
- [x] **App info and performance → Diagnostics** — Collected, not shared, not ephemeral, required. Purpose: App functionality, Analytics.
- [x] **Device or other IDs → Device or other IDs** — Collected, not shared, not ephemeral, required. Purpose: Analytics, App functionality.
- [x] **Location → Approximate location** — Collected, not shared, not ephemeral, required. Purpose: Analytics.
- [ ] Advertising ID — **NOT collected** (disabled in manifest).
- [ ] Photos and videos — **NOT collected** (stay on-device; share sheet is user-initiated).
- [ ] Personal info / Financial info / Contacts / Calendar / Messages / Audio / Files / Health — **NOT collected.**

---

## Open decision — data-deletion answer & opt-out toggle

The deletion answer above is **No**, and that is the honest position: the diagnostics carry **no
personal identifier** (a real Crashlytics record is just device brand/model, free RAM/disk,
OS version, app version, and a crash timestamp — keyed only to a random install UUID). With nothing
that maps a request to a person, there is no way to find and delete "this user's" logs, so promising
per-user deletion would be a promise that can't be kept. "No" is permitted by Play and is the
truthful answer for pseudonymous-only telemetry.

If you later want to offer real user control, the parked in-app opt-out toggle
(`docs/active/firebase-analytics/IMPLEMENTATION.md` §7) — a Settings switch calling
`FirebaseAnalytics.setAnalyticsCollectionEnabled(false)` +
`FirebaseCrashlytics.setCrashlyticsCollectionEnabled(false)` — would let you flip "users can choose
whether this data is collected" to **Yes** for every type above. That is an opt-out of *collection*,
which is cleaner and more honest than a deletion workflow you can't action. Schedule it for a future
release; it is not required to ship today.

---

## Keep this honest

This declaration is accurate **only while** the app ships Analytics + Crashlytics with ad-ID
collection disabled and no other off-device data paths. If a future version adds an ad SDK, accounts,
cloud backup, remote config that sends user data, re-enables the advertising ID, or adds any new
custom event that captures a new data type, **re-review this file before that version ships.**
