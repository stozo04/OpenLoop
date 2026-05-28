# Lesson 006 — Always show permission rationale before re-requesting

## What was flagged

`MainActivity.checkPermissions()` goes straight from "not granted" → `requestPermissionLauncher.launch()` with no `shouldShowRequestPermissionRationale()` check. Consequences:

- Users who denied once get the system dialog again with no context — bad UX, often results in another denial.
- Users who denied with "Don't ask again" (or denied twice on Android 11+) get silently no-op'd — the dialog never appears and the app appears broken.

This will be visible in Play Store reviews the moment the app ships.

## Pattern

Standard Google permission flow:

```kotlin
when {
    ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED -> {
        // Already have it, proceed.
        onGranted()
    }
    shouldShowRequestPermissionRationale(permission) -> {
        // User denied at least once but hasn't selected "Don't ask again".
        // Show an in-app educational UI explaining why we need this permission,
        // then call requestPermissionLauncher.launch(...) after they acknowledge.
        showRationaleUi(onConfirm = { requestPermissionLauncher.launch(arrayOf(permission)) })
    }
    else -> {
        // First request, or user selected "Don't ask again".
        // Launch directly; the system handles the rest.
        // If the user has permanently denied, the result callback will receive false
        // for both granted and shouldShowRationale — guide them to Settings then.
        requestPermissionLauncher.launch(arrayOf(permission))
    }
}
```

For OpenRang specifically, the existing `PermissionDeniedScreen` composable can be lifted into the rationale slot — same content (icon, explanation, "Grant Permissions" button), just shown *before* the system dialog the second time.

## Detection checklist

- Every `requestPermissionLauncher.launch(...)` must be preceded by either a granted-check or a rationale UI.
  ```
  grep -rn "requestPermissionLauncher.launch\|registerForActivityResult" app/src --include="*.kt"
  ```
- Test the flow manually:
  1. Fresh install → grant prompt appears (no rationale needed).
  2. Deny once → next attempt should show the rationale UI before the system prompt.
  3. Deny again with "Don't ask again" → app must route to Settings, not silently fail.

## Reference

- [Request Runtime Permissions](https://developer.android.com/training/permissions/requesting)
- [App Permissions Best Practices](https://developer.android.com/training/permissions/usage-notes)
- PR #5 WARNING #5
