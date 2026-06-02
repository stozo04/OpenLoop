# Firebase Crashlytics — preview reverse failures (“Trimming..”)

This doc explains how OpenLoop reports **stuck or failed preview reverse** (the “Trimming..” / “Couldn’t loop that clip” path) to Firebase Crashlytics, how **you** view reports, and how **testers** can help without Android Studio.

Official references:

- [Get started with Crashlytics for Android](https://firebase.google.com/docs/crashlytics/android/get-started)
- [Customize crash reports (custom keys, `recordException`)](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports)
- [Capture bug reports from devices](https://developer.android.com/studio/debug/bug-report) (system bug report — backup path)

---

## What the app sends

When preview reverse **times out** (120 s) or **throws**, `OpenLoopViewModel` calls `ReverseCrashlytics.reportPreviewFailure()`, which:

1. Logs a Crashlytics breadcrumb: `reverse_preview_failure: <outcome>`
2. Calls **`FirebaseCrashlytics.recordException(cause, customKeys)`** as a **non-fatal** event

Custom keys (searchable in the Firebase console) include:

| Key | Example |
|-----|---------|
| `reverse_outcome` | `Timed out after 120s` or `CodecException: …` |
| `app_version` / `app_version_code` | `1.0.5` / `5` |
| `video_mime` | `video/hevc` |
| `video_width` / `video_height` / `video_fps` | `1920` / `1080` / `60` |
| `hevc_or_hdr_normalize` | `true` if pre-normalize ran |
| `trim_window_ms` | length of trim selection |
| `source_bytes` | scratch file size |

Implementation: `app/src/main/java/io/github/stozo04/openloop/diagnostics/ReverseCrashlytics.kt`.

**Upload timing:** Non-fatal events are stored on-device and sent on the **next app launch** (or with the next fatal crash), not instantly. That is normal Crashlytics behavior.

---

## One-time setup (developer)

### 1. Create / open a Firebase project

1. Go to [Firebase console](https://console.firebase.google.com/)
2. Create a project (or use an existing one) for OpenLoop
3. Add an **Android app** with package name: `io.github.stozo04.openloop`

### 2. Download `google-services.json`

1. Firebase console → **Project settings** → **Your apps** → Android app
2. Download **`google-services.json`**
3. Place it at: **`app/google-services.json`** (repo root is `OpenRang/`, file lives under `app/`)

The file is **gitignored**. Use `app/google-services.json.template` as a reminder path only.

### 3. Build

Gradle applies the Google Services and Crashlytics plugins **only when** `app/google-services.json` exists:

```kotlin
// app/build.gradle.kts (bottom)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
```

Without the JSON file, the app still **compiles**; Crashlytics simply does not initialize (`ReverseCrashlytics` no-ops safely).

### 4. Release builds

Ship **release** AABs built **with** `google-services.json` present so Play testers report to your Firebase project. Play App Signing does not block Crashlytics.

Enable **deobfuscation** in Firebase if you use R8 (mapping files from Play Console or local `mapping.txt` upload).

---

## How you view data (Firebase console)

1. Open [Firebase console](https://console.firebase.google.com/) → your project
2. Go to **Crashlytics** (left nav: Build → Crashlytics, or “Run” / DevOps depending on console layout)
3. Look under **Non-fatals** (or filter issues by non-fatal)
4. Open an issue → event detail shows:
   - Stack trace (`PreviewReverseTimeoutException` for timeouts, or the real codec error)
   - **Keys** tab — custom keys listed above
   - **Logs** tab — breadcrumb `reverse_preview_failure: …`
5. Filter examples:
   - Search keys: `reverse_outcome`, `video_mime`, `app_version`
   - Filter by device model (e.g. Samsung SM-S926*) or Android version

**Tip:** Group by `reverse_outcome` and `video_mime` to see whether Brazil/S24+ reports are HEVC timeouts vs codec exceptions.

Crashlytics does **not** replace logcat for deep codec debugging, but it aggregates **how many** users hit which outcome on which devices.

---

## How testers send data (no SDK)

Testers have **three** paths, from easiest to heaviest:

### A. In-app “Send debug info” (immediate, recommended)

After the fix in PR [#51](https://github.com/stozo04/OpenLoop/pull/51):

1. Reproduce: record or import → Trim → enter editor (**Trimming..**)
2. Wait up to **~2 minutes** — overlay should change to **“Couldn’t loop that clip”**
3. Tap **SEND DEBUG INFO**
4. Share via WhatsApp, email, etc.

You receive plain text (device, app version, codec, trim window). No PC required.

### B. Firebase Crashlytics (automatic, no tester action)

If the build was compiled with `google-services.json`:

1. Tester uses the app normally; reverse fails or times out
2. Tester **fully closes and reopens** the app (or uses it until the next sync)
3. You see a **non-fatal** in Firebase console (section above)

Tell testers: *“If the app says it couldn’t loop, close the app completely and open it again once — that helps send the automatic report.”*

### C. Android system bug report (heavy backup)

For **infinite Trimming..** on an old build, or when Firebase is not configured:

1. Settings → About phone → tap **Build number** 7×
2. Settings → **Developer options** → **Take bug report**
3. Wait → share the zip when prompted

You can also use [SDK platform-tools](https://developer.android.com/tools/releases/platform-tools) only (`adb bugreport`) — no full Android Studio install required.

**Avoid** telling non-technical users to install “Logcat Reader” apps; most require `adb tcpip` setup first.

---

## Privacy note for testers

Crashlytics events include **device model**, **Android version**, **app version**, and **video format metadata** — not the video file itself. The in-app share sheet sends the same class of data in text form.

---

## Related

- [`trimming-loop.md`](../../trimming-loop.md) — full postmortem and fix history
- [`docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md`](../lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md)
