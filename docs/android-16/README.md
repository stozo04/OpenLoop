# Android 16 (API 36) — Upgrade Knowledge Hub

Durable reference for OpenRang's move to **`targetSdk 36` / Android 16**. This folder is the
single place an agent or contributor should look to understand what Android 16 changes, what it
means for OpenRang specifically, and where the official Google source lives — without re-running a
Google search.

This hub is **evergreen reference**: unlike a feature folder, it does **not** move to
`docs/completed/` when the upgrade ships. The actionable, ordered upgrade plan lives separately in
[`docs/active/007-target-sdk-upgrade/IMPLEMENTATION.md`](../active/007-target-sdk-upgrade/IMPLEMENTATION.md)
(GitHub Issue [#7](https://github.com/stozo04/OpenRang/issues/7)).

> **Verified 2026-05-28.** Google's pages change. Every file records the source URL and fetch date
> in its header. **Re-verify against the live page before acting** — see the dated note in each file.

## How to read this hub

Each document leads with:
1. **Source** — the exact `developer.android.com` URL it summarizes, plus the fetch date.
2. **Impact on OpenRang** — an *Applies / Partially applies / N/A* verdict with reasons tied to
   OpenRang's actual surface (on-device, zero-network, no notifications/health/Bluetooth, single
   Activity, Compose + CameraX + Media3).
3. **Summary** — a faithful, verbose summary of the page.

## Documents

| Document | What it covers | OpenRang verdict |
|----------|----------------|------------------|
| [behavior-changes-targeting-16.md](./behavior-changes-targeting-16.md) | Behavior changes for apps **targeting** API 36 (verbatim Google page + provenance header) | **Applies** — edge-to-edge, predictive back, adaptive layouts |
| [summary.md](./summary.md) | The full Android 16 change catalog (all-apps vs targeted vs new APIs) | Partially applies |
| [migration.md](./migration.md) | Google's two-phase migration process (compat first, then re-target) | **Applies** — this is the Issue #7 workflow |
| [features.md](./features.md) | New developer features & APIs in Android 16 | Partially applies — Camera/Media/Graphics are in-domain (optional) |
| [progress-centric-notifications.md](./progress-centric-notifications.md) | `Notification.ProgressStyle` for tracked journeys | N/A today (no notifications) |
| [api-diff-36.md](./api-diff-36.md) | The framework API delta (API 35 → 36) — how to use it | Reference only — additive; AndroidX libs not listed here |

## Source URLs (for quick access)

- Behavior changes (targeting 16): https://developer.android.com/about/versions/16/behavior-changes-16
- Behavior changes (all apps): https://developer.android.com/about/versions/16/behavior-changes-all
- Summary: https://developer.android.com/about/versions/16/summary
- Migration: https://developer.android.com/about/versions/16/migration
- Features: https://developer.android.com/about/versions/16/features
- Progress-centric notifications: https://developer.android.com/about/versions/16/features/progress-centric-notifications
- Framework API diff (35 → 36): https://developer.android.com/sdk/api_diff/36/changes
- Google Play target API requirement: https://developer.android.com/google/play/requirements/target-sdk

## Related

- **Evergreen rules:** [`docs/ANDROID_STANDARDS.md`](../ANDROID_STANDARDS.md) — the version-agnostic
  "how we build" standards. Android-16-specific rules there carry a `Status: pending — Issue #7`
  marker until the code actually targets 36.
- **Upgrade plan & checklist:** [`docs/active/007-target-sdk-upgrade/IMPLEMENTATION.md`](../active/007-target-sdk-upgrade/IMPLEMENTATION.md)
- **This docs-prep effort:** [`docs/active/android-16-doc-prep/IMPLEMENTATION.md`](../active/android-16-doc-prep/IMPLEMENTATION.md)
