# Android 16 — Progress-centric Notifications

> **Source:** https://developer.android.com/about/versions/16/features/progress-centric-notifications · fetched 2026-05-28
> **Part of:** [docs/android-16/ hub](./README.md) — Android 16 (API 36) upgrade knowledge for OpenLoop (Issue #7)

## Impact on OpenLoop

**Verdict:** N/A today — OpenLoop posts no notifications and runs no user-initiated, start-to-end background journeys, which is the only thing this style is for (revisit if background video export ever needs a progress UI).

- This style targets user-initiated journeys like rideshare, delivery, and navigation; OpenLoop has nothing of that shape today.
- The app is 100% on-device with no background or long-running user-visible jobs, so there is no progress to surface.
- The one realistic future fit: if loop generation / video export ever moves to a long-running background job, a `Notification.ProgressStyle` notification could show export progress with segments and a tracker icon.

## Summary

### What progress-centric notifications are

Progress-centric notifications are a new notification style introduced in Android 16 that help users track user-initiated, start-to-end journeys. They visually represent progress through a journey using customizable visual elements: segments, points, and a tracker icon.

### Key APIs and classes

The primary classes are:

- `Notification.ProgressStyle` — the main notification style class.
- `Notification.ProgressStyle.Segment` — denotes states with duration.
- `Notification.ProgressStyle.Point` — denotes states and milestones.

Core `Notification.Builder` methods used alongside `ProgressStyle`:

- `setSubText()` — header subtext.
- `setWhen()` — header time.
- `setContentTitle()` — content title.
- `setContentText()` — content text.
- `addAction()` — action buttons.
- `setLargeIcon()` — vehicle/tracker image (via `NotificationCompat.Builder`).

`ProgressStyle`-specific methods:

- `setStyledByProgress(boolean)` — control styling behavior.
- `setProgress(int)` — set the current progress value.
- `setProgressTrackerIcon(Icon)` — set the tracker/vehicle icon image.
- `setProgressSegments(List<Segment>)` — define colored segments.
- `setProgressPoints(List<Point>)` — define milestone points.

### Typical use cases

Google lists these primary use cases:

- Rideshare
- Delivery
- Navigation

### Constraints and best practices

- **Promoted visibility** — set the right fields to meet promoted visibility requirements.
- **Visual elements** — use vehicle images and accurate colors for the journey type.
- **Text clarity** — use concise language; critical information includes time of arrival, driver name, and journey state.
- **Actionable items** — provide relevant actions (e.g., tip options, adding items to an order).
- **Segment/point usage** — use segments for states with duration (e.g., traffic conditions with colors), points for milestones.
- **Frequent updates** — update progress frequently and accurately to reflect actual journey progression.

### Example

```kotlin
var ps = Notification.ProgressStyle()
    .setStyledByProgress(false)
    .setProgress(456)
    .setProgressTrackerIcon(Icon.createWithResource(appContext, R.drawable.ic_car_red))
    .setProgressSegments(
        listOf(
            Notification.ProgressStyle.Segment(41).setColor(Color.BLACK),
            Notification.ProgressStyle.Segment(552).setColor(Color.YELLOW),
            Notification.ProgressStyle.Segment(253).setColor(Color.WHITE),
            Notification.ProgressStyle.Segment(94).setColor(Color.BLUE)
        )
    )
    .setProgressPoints(
        listOf(
            Notification.ProgressStyle.Point(60).setColor(Color.RED),
            Notification.ProgressStyle.Point(560).setColor(Color.GREEN)
        )
    )
```

## See also
- Evergreen rules: [ANDROID_STANDARDS.md](../ANDROID_STANDARDS.md)
- Upgrade plan: [007 IMPLEMENTATION.md](../completed/007-target-sdk-upgrade/IMPLEMENTATION.md)
