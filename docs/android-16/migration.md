# Android 16 — Migration Guide

> **Source:** https://developer.android.com/about/versions/16/migration · fetched 2026-05-28
> **Part of:** [docs/android-16/ hub](./README.md) — Android 16 (API 36) upgrade knowledge for OpenRang (Issue #7)

## Impact on OpenRang

**Verdict:** Applies — OpenRang must run both migration phases the page describes (compatibility first, then re-target to 36), even though its tiny surface keeps the work small.

- **Phase 1 (compatibility, no `targetSdkVersion` change):** Run OpenRang's existing published build against an Android 16 device/emulator, work through every flow — camera capture, looping preview, gallery, delete, onboarding — and fix anything the all-apps behavior changes break. Per the page, this phase needs no change to `targetSdkVersion` or `compileSdkVersion`.
- **Phase 2 (re-target to 36):** Update `targetSdkVersion` and `compileSdkVersion` to 36 in the Gradle config, recompile, then re-test focusing on the changes that apply *only* when targeting Android 16. This is the substance of Issue #7.
- **Non-SDK interface check applies even to us:** Audit OpenRang and its libraries (CameraX, Media3, DataStore, Compose) for restricted non-SDK interface usage. Watch logcat warnings and consider wiring `StrictMode.VmPolicy.Builder.detectNonSdkApiUsage()` into a debug build during testing.
- **Third-party SDK testing matters here:** OpenRang ships CameraX, Media3 (ExoPlayer + Transformer), DataStore, and Compose. The page requires fully testing every library on Android 16 and updating to the latest versions if any misbehaves.
- **Already done:** `enableEdgeToEdge()` is already called in `MainActivity` — relevant to the edge-to-edge enforcement that lands when targeting 36, but that specific change lives in the behavior-changes docs, not this migration page.
- **Largely N/A for us:** No notifications, foreground services, health/Bluetooth/LAN, Wear/TV/Auto, accounts, network, or exported intents beyond LAUNCHER — so the privacy and targeted-change surface to review (Phase 2 Step 3) is narrow.

## Summary

This page gives a high-level view of the typical development and testing phases for getting an app ready for Android 16, aligned with the platform release timeline. Android 16 introduces new features and behavior changes intended to make the platform more helpful, secure, and performant. Because users can receive the new platform as soon as its source is released to AOSP, apps should be ready — and ideally take advantage of the new features and APIs.

The page frames the work as two phases that can run concurrently:

- **Phase 1 — Ensuring app compatibility**, targeted for the Android 16 final release.
- **Phase 2 — Targeting new platform features and APIs**, done as soon as possible after the final release.

### Ensure compatibility with Android 16

The goal of this phase is to confirm that your existing app keeps working on Android 16. You can usually adjust the app and publish it **without changing `targetSdkVersion`**, and you shouldn't need to use new APIs or change `compileSdkVersion` (whether you do depends on your build approach and the platform functionality your app uses).

Before testing, familiarize yourself with the **behavior changes for all apps**. These changes can affect your app even if you don't change `targetSdkVersion`.

The compatibility testing workflow:

1. **Get Android 16.** Flash an Android 16 system image onto a device, or download a system image for the Android emulator.
2. **Review changes.** Review the system behavior changes to identify areas where your app might be affected.
3. **Test.** Install your current published app on a device or emulator running Android 16, run your tests, focus on the system behavior changes, and work through all your app flows.
4. **Update.** Make only the code changes required to adapt to behavior changes or resolve issues, and **recompile with the same API level the app originally targeted** — you do not need to target Android 16 in this phase.
5. **Publish.** Sign, upload, and publish the updated Android App Bundle or APK.

#### Perform compatibility testing

Compatibility testing is much like ordinary app testing — a good time to review the [core app quality guidelines](https://developer.android.com/docs/quality-guidelines/core-app-quality) and the [best practices for testing](https://developer.android.com/training/testing). Install your current published app on a device running Android 16 and work through all flows and functionality, looking for issues.

Focus areas:

1. **Behavior changes for all apps.** Review the behavior changes for all apps — they can affect how your app functions or even cause it to crash.
2. **Restricted non-SDK interfaces.** Review and test for any use of restricted non-SDK interfaces; replace each one with a public SDK or NDK equivalent. Watch for logcat warnings that highlight these accesses, and use the `StrictMode` method `detectNonSdkApiUsage()` (on `StrictMode.VmPolicy.Builder`) to catch them programmatically.
3. **Third-party libraries and SDKs.** Fully test every library and SDK in your app to make sure they work as expected on Android 16 and follow best practices for privacy, performance, UX, data handling, and permissions. If you find an issue, try updating to the latest SDK version or reach out to the SDK developer.

Publish the compatible app right away after testing and updates. Doing so lets users test it early and helps ensure a smooth transition as they update to Android 16.

### Update the app's targeting and build with new APIs

This is the next step after publishing a compatible version. You can make these updates whenever you're ready, keeping in mind the [Google Play requirements](https://developer.android.com/distribute/play-policies) for targeting a new platform.

Review the **behavior changes that affect apps targeting Android 16**. These targeted behavior changes might cause functional issues that require significant development work, so the page recommends learning about and addressing them as early as possible. To identify which targeted changes affect you, use the **compatibility toggles** (described in the next section) to test your app with selected changes enabled.

The full support workflow:

1. **Get the Android 16 SDK.** Install the latest Android Studio preview to build with Android 16, make sure you have an Android 16 device or emulator, and update `targetSdkVersion` and other build configurations.
2. **Review behavior changes.** Review the behavior changes that apply to apps targeting Android 16, identify affected areas, and plan how to support them.
3. **Check against new privacy changes.** Make the code and architecture changes needed to support Android 16's user privacy changes.
4. **Adopt Android 16 features.** Take advantage of Android 16 APIs to bring new features and capabilities to your app, and recompile for Android 16.
5. **Test.** Test on an Android 16 device or emulator, focusing on areas where behavior changes might affect the app and on functionality that uses new APIs. Provide platform and API feedback and report any platform, API, or third-party SDK issues.
6. **Final update.** Once the Android 16 APIs are final, update `targetSdkVersion` and other build configurations again, make any additional updates, and test the app.
7. **Publish.** Sign, upload, and publish the updated Android App Bundle or APK.

#### Get the SDK, change targeting, build with new APIs

Use the latest preview version of Android Studio to download the Android 16 SDK and other tools, update the app's `targetSdkVersion` and `compileSdkVersion`, and re-compile the app. See the [SDK setup guide](https://developer.android.com/about/versions/16/setup-sdk) for details.

#### Test your Android 16 app

Some behavior changes apply **only** when your app targets the new platform, so review the **behavior changes for apps targeting Android 16** before testing, then work through all flows and functionality looking for issues.

Focus areas:

1. **Behavior changes for apps targeting Android 16** — the primary focus.
2. **Core app quality guidelines** — check your app against them.
3. **Testing best practices** — follow the recommended approach.

Also review and test for uses of **restricted non-SDK interfaces** that may apply. Watch for logcat warnings highlighting these accesses, and use the `StrictMode` method `detectNonSdkApiUsage()` to catch them programmatically. As in Phase 1, fully test all libraries and SDKs to ensure they work on Android 16 and follow best practices for privacy, performance, UX, data handling, and permissions; update to the latest SDK version or contact the developer if you find an issue.

### Test using app compatibility toggles

App compatibility toggles make it easier to test a debuggable app against the targeted behavior changes. They provide four capabilities:

1. **Test targeted changes without changing `targetSdkVersion`** — force-enable specific targeted behavior changes to evaluate their impact on your existing app.
2. **Focus testing on specific changes only** — rather than addressing all targeted changes at once, disable every targeted change except the ones you want to test.
3. **Manage toggles through adb** — use adb commands to enable and disable toggleable changes, which works in an automated test environment.
4. **Debug faster using standard change IDs** — each toggleable change has a unique ID and name, letting you quickly debug a root cause in the log output.

For detailed information, see the [Compatibility framework changes (Android 16)](https://developer.android.com/about/versions/16/reference/compat-framework-changes) reference.

### API and tooling references mentioned

- **`StrictMode`** / **`StrictMode.VmPolicy.Builder`** with `detectNonSdkApiUsage()` — programmatic detection of restricted non-SDK API usage.
- **`targetSdkVersion`** and **`compileSdkVersion`** — the build configuration properties changed (or deliberately left unchanged) across the two phases.
- **Android Studio** (latest preview), the **Android 16 SDK**, an **Android 16 device or emulator**, **adb** (toggle management), and **logcat** (non-SDK warnings).
- Output artifacts: **Android App Bundle** and **APK**.
- Two behavior-change categories are implied by the page structure: **all-apps behavior changes** (apply regardless of `targetSdkVersion`, tested in Phase 1) and **targeted behavior changes** (apply only when targeting Android 16, toggleable for testing).

_Page last updated 2026-05-18 UTC per the source._

## See also
- Evergreen rules: [ANDROID_STANDARDS.md](../ANDROID_STANDARDS.md)
- Upgrade plan: [007 IMPLEMENTATION.md](../active/007-target-sdk-upgrade/IMPLEMENTATION.md)
