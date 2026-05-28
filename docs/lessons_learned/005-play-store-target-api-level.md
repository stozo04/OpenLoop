# Lesson 005 — Track current Play Store target API level requirements

## What was flagged

`compileSdk = 34` and `targetSdk = 34`. Google Play requires **API 35** (Android 15) for new apps and updates as of August 31, 2025 (extension deadline November 1, 2025). API 34 will be rejected at submission time. `docs/ANDROID_STANDARDS.md` Section 8 already documents this gap.

The trap: targetSdk feels like a passive setting until the first Play Store submission, at which point it becomes a hard blocker.

## Pattern

- Before every release, check [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk) — Google bumps the required level annually.
- When bumping `targetSdk`, **always** review the corresponding Android version's [behavior changes](https://developer.android.com/about/versions/) page. Recent significant changes include:
  - API 35 (Android 15): edge-to-edge enforcement (your app draws under system bars by default), predictive back gesture, foreground service type enforcement, 16 KB page size support.
  - API 34 (Android 14): foreground service types, broadcast receiver flags, partial photo picker access.
- Bump in a **dedicated PR** — do not bundle SDK upgrades with feature work. Behavior changes can require fixes across multiple unrelated screens, and an isolated PR makes the bisect trivial if regressions appear.
- Update `compileSdk` first; only update `targetSdk` after all known behavior changes have been audited.

## Detection checklist

- `app/build.gradle.kts` — `compileSdk` and `targetSdk` should match the latest Play requirement.
- `docs/ANDROID_STANDARDS.md` Section 8 should reflect the current state.
- Before tagging a release, re-check the Target API Level Requirements page — don't rely on cached knowledge.

## Reference

- [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Android 15 Behavior Changes (all apps)](https://developer.android.com/about/versions/15/behavior-changes-all)
- PR #5 WARNING #4
