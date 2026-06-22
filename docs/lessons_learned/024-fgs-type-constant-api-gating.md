# 024 — Gate a foreground-service type on the API level that ADDED it, not one below

## What went wrong

The Loopify render runs under a WorkManager foreground service. `BoomerangRenderNotifications`
picked the FGS type like this:

```kotlin
val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34 — WRONG
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING                            // value 8192
} else 0
```

`FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` was **added in API 35 (Android 15)**, not 34. The
constant happily *compiles* against `compileSdk 36`, so nothing flagged it — but it is inlined to the
literal `8192`, and on an actual **Android 14 (API 34)** device the platform's service validator does
not recognize that type bit. With `targetSdk 36`, an unrecognized type is fatal:

```
java.lang.RuntimeException: Unable to start service SystemForegroundService …
Caused by: android.app.InvalidForegroundServiceTypeException:
    Starting FGS with type unknown … targetSDK=36 has been prohibited
```

Real crash: Crashlytics `9663c743…`, Samsung Galaxy A55 (SM-A556E), Android 14, v1.0.23 — 100% of
Loopify saves on every Android-14 device.

## Pattern

- A new `FOREGROUND_SERVICE_TYPE_*` constant is usable **only from the API level where it was added**,
  even though it compiles against any newer `compileSdk`. Gate on that exact level and fall back to a
  type that exists on older OSes.
- For media/transcode work the broadly-valid fallback is `dataSync`
  (`FOREGROUND_SERVICE_TYPE_DATA_SYNC` = 1, added API 29 — Google's documented type for
  "import/export … transfer" and what WorkManager's long-running-worker sample uses).

```kotlin
fun foregroundServiceTypeForSdk(sdkInt: Int): Int = when {
    sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING // 35+
    sdkInt >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC                        // 29–34
    else -> 0                                                                                                // < 29
}
```

- Declare **every** type the app can request on the `<service>` in the manifest
  (`android:foregroundServiceType="dataSync|mediaProcessing"`) plus each type's permission. The
  platform requires the requested type to be a subset of the declared set.
- The type passed to `startForeground()` comes from the `ForegroundInfo` you hand WorkManager — the
  manifest declaration alone is not what's validated at runtime.

## Detection checklist

- `grep -rn "FOREGROUND_SERVICE_TYPE_" app/src/main` — for each constant, confirm the gate uses the
  API level it was **added** in (`UPSIDE_DOWN_CAKE`=34, `VANILLA_ICE_CREAM`=35), not an adjacent one.
- Any FGS type requested at runtime must also appear in `android:foregroundServiceType` AND have its
  `FOREGROUND_SERVICE_*` permission in the manifest.
- Extract the SDK→type choice into a pure `fun(sdkInt): Int` so it's unit-testable without Robolectric
  (the constants inline) — see `BoomerangRenderNotificationsTest`.

## Reference

- ServiceInfo (per-constant "Added in API level"):
  https://developer.android.com/reference/android/content/pm/ServiceInfo
- Changes to foreground service types for Android 15:
  https://developer.android.com/about/versions/15/changes/foreground-service-types
- Origin: Crashlytics issue `9663c7436eb21e7ec4219eba1125416f` (Galaxy A55 / Android 14, v1.0.23).
