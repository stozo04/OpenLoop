# Android 16 — New Features & APIs

> **Source:** https://developer.android.com/about/versions/16/features · fetched 2026-05-28
> **Part of:** [docs/android-16/ hub](./README.md) — Android 16 (API 36) upgrade knowledge for OpenRang (Issue #7)

## Impact on OpenRang

**Verdict:** Partially applies — most of the surface (notifications, connectivity, health, TV, accessibility) is irrelevant to a 100% on-device camera/loop app, but the Camera, Media, and Graphics additions are directly in OpenRang's domain and worth tracking.

- **Camera (relevant, optional):** Hybrid auto-exposure (ISO / exposure-time priority), precise color temperature + tint (`COLOR_CORRECTION_MODE_CCT`), and night-mode scene detection are Camera2 features. OpenRang uses CameraX today, so these only matter if it ever drops to Camera2 interop for manual capture controls. Not required for the SDK 36 upgrade.
- **Media (relevant, optional):** UltraHDR HEIC (`ImageFormat.HEIC_ULTRAHDR`) and the APV professional video codec (`MediaFormat.MIMETYPE_VIDEO_APV`) touch OpenRang's video-processing/export domain (Media3 Transformer), but APV's multi-Gbps pro-codec target is far beyond a Boomerang app's needs. The embedded/cloud Photo Picker is more about picking media than exporting it.
- **Graphics (relevant, optional):** AGSL `RuntimeColorFilter` / `RuntimeXfermode` could power on-device video effects/filters in Compose-driven UI later; not needed now.
- **Adaptive UI (relevant):** Predictive-back updates and the accessibility improvements (outline high-contrast text, tri-state checkbox, expandable-element state) are general best-practice items that benefit any Compose Material 3 app, OpenRang included.
- **Not relevant:** Progress-centric notifications, richer haptics, live-wallpaper content, all Connectivity (Wi-Fi ranging, UWB, Companion Device), Health Connect, Privacy Sandbox, Key Sharing, TV quality framework, and internationalization (vertical text, measurement system) — OpenRang has zero network, no notifications, no accounts, and is not a Wear/TV/Auto target.
- **Versioning (relevant):** The new `SDK_INT_FULL` / `VERSION_CODES_FULL` constants and `Build.getMinorSdkVersion(int)` matter for the two-releases-per-year cadence and should be on the radar for runtime version checks during the SDK 36 work.

## Summary

The features page documents the new developer-facing capabilities and APIs introduced in Android 16 (API 36, codename **BAKLAVA**), grouped by category. The page also lists supporting references for full API diffs, behavior changes, and non-SDK restrictions.

### Core Functionality

#### Two Android API releases in 2025

Android 16 introduces a shift to two API releases per year:

- **Q2 2025 major release** — carries new developer APIs and the behavior changes tied to `targetSdkVersion`.
- **Q4 2025 minor release** — feature updates, optimizations, and bug fixes with no app-impacting behavior changes.
- **Q1 and Q3 updates** — incremental improvements between the API releases.

To support checking both the major and the new minor versions, Android 16 adds:

- `Build.Version.SDK_INT` — continue using for major-version checks.
- `Build.Version.SDK_INT_FULL` — new constant covering both major and minor versions.
- `Build.VERSION_CODES` — existing major-version enumeration.
- `Build.VERSION_CODES_FULL` — new enumeration for major/minor version codes.
- `Build.getMinorSdkVersion(int)` — retrieves the minor SDK version.

```kotlin
// Major version check (existing approach)
if (SDK_INT >= VERSION_CODES.BAKLAVA) {
    // Use APIs introduced in Android 16
}

// Major or minor version check (new approach)
if (SDK_INT_FULL >= VERSION_CODES_FULL.BAKLAVA) {
    // Use APIs introduced in Android 16
}

// Get minor SDK version
val minorSdkVersion = Build.getMinorSdkVersion(VERSION_CODES_FULL.BAKLAVA)
```

### User experience and system UI

#### Progress-centric notifications

A new `Notification.ProgressStyle` notification style lets apps track user-initiated, start-to-end journeys with visual progress indicators. Supporting nested classes:

- `Notification.ProgressStyle.Point` — denotes states and milestones in a journey.
- `Notification.ProgressStyle.Segment` — defines journey segments.

Intended use cases include rideshare tracking, delivery notifications, and navigation progress. (See OpenRang's dedicated `progress-centric-notifications.md` note for detail.)

#### Predictive back updates

Apps can register callbacks for predictive-back system animations in gesture navigation (for example, back-to-home). Key APIs:

- `OnBackInvokedDispatcher.registerOnBackInvokedCallback(int, OnBackInvokedCallback)` — register back callbacks.
- `OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER` — new priority constant for observing system navigation.
- `OnBackInvokedCallback.onBackInvoked()` — regular back-invocation callback.
- `SystemOnBackInvokedCallbacks.finishAndRemoveTaskCallback(Activity)` — finish-and-remove-task behavior.
- `SystemOnBackInvokedCallbacks.moveTaskToBackCallback(Activity)` — move-task-to-back behavior.

Apps can receive `onBackInvoked()` when the system handles back navigation (without disrupting normal flow), trigger specific behaviors, and play ahead-of-time animations when a back gesture is invoked.

#### Richer haptics

New APIs in the `android.os.vibrator` package let apps define the amplitude and frequency curves of haptic effects while abstracting away device-capability differences. This builds on Android 11's `VibrationEffect.Composition`, which exposed device-defined semantic primitives.

### Developer productivity and tools

#### Content handling for live wallpapers

New content APIs let developers build dynamic, user-driven live wallpapers:

- `WallpaperDescription` — identifies distinct instances of live wallpapers produced by the same service.
- `WallpaperInstance` — manages an individual wallpaper instance.
- `WallpaperManager` — uses the metadata to present wallpapers.

A representative use case is showing unique content on the home screen and lock screen at the same time.

### Performance and battery

#### System-triggered profiling

Building on `ProfilingManager` (introduced in Android 15), Android 16 lets the system automatically collect Perfetto profiling data for critical app flows without manual initiation. Apps register interest in triggers such as:

- Cold start events.
- `Activity.reportFullyDrawn()` completion.
- ANR (Application Not Responding) events.

The system starts and stops traces on the app's behalf and delivers results to the app's data directory.

#### Start component in ApplicationStartInfo

`ApplicationStartInfo.getStartComponent()` returns the component type that triggered the app start, helping developers optimize the startup flow. This extends the Android 15 `ApplicationStartInfo` API, which already provided start reasons, start type, start times, and throttling info.

#### Better job introspection

Improved insight into job-scheduling constraints and pending states:

- `JobScheduler.getPendingJobReason(int jobId)` — returns a single reason (existing, limited API).
- `JobScheduler.getPendingJobReasons(int jobId)` — **new**; returns multiple reasons a job is pending.
- `JobScheduler.getPendingJobReasonsHistory(int jobId)` — **new**; returns a list of recent constraint changes.

Reasons distinguish explicit constraints set by the developer from implicit constraints set by the system, helping debug why jobs aren't executing, diagnose reduced success rates, and identify latency.

#### Adaptive refresh rate (ARR) enhancements

Extends Android 15's ARR with new display APIs:

- `Display.hasArrSupport()` — check whether the display supports ARR.
- `Display.getSuggestedFrameRate(int)` — get a suggested frame rate.
- `Display.getSupportedRefreshRates()` — restored, to query available refresh rates.

Jetpack support includes `RecyclerView 1.4`, which adds built-in ARR support for fling and smooth-scroll settling, with more Jetpack libraries gaining ARR support. ARR reduces power consumption by adapting the display refresh rate to the content frame rate without jank-inducing mode switching.

#### Headroom APIs in ADPF (Android Dynamic Performance Framework)

Provides estimates of available CPU and GPU resources for games and resource-intensive apps:

- `SystemHealthManager.getCpuHeadroom(CpuHeadroomParams)` — available CPU resources.
- `SystemHealthManager.getGpuHeadroom(GpuHeadroomParams)` — available GPU resources.
- `CpuHeadroomParams` / `GpuHeadroomParams` — customize the computation window and select average vs. minimum availability.

These complement existing ADPF thermal-throttling detection and help reduce CPU/GPU usage for better UX and battery life.

### Accessibility

#### Improved accessibility APIs

A cluster of additions to the accessibility framework:

- **Outline text for maximum text contrast** — replaces high-contrast text with outline text for low-vision users. `AccessibilityManager.isHighContrastTextEnabled()` checks the mode; `AccessibilityManager.addHighContrastTextStateChangeListener(Executor, HighContrastTextStateChangeListener)` registers for changes. Aimed at UI-toolkit libraries (e.g., Jetpack Compose) and apps doing custom text rendering that bypasses `android.text.Layout`.
- **Duration added to TtsSpan** — new `TtsSpan.TYPE_DURATION` with `ARG_HOURS`, `ARG_MINUTES`, and `ARG_SECONDS` arguments, for accurate text-to-speech of time durations via TalkBack and other TTS services.
- **Support elements with multiple labels** — `AccessibilityNodeInfo.setLabeledBy(View)` and `getLabeledBy()` are deprecated in favor of list-based `addLabeledBy(View)`, `removeLabeledBy(View)`, and `getLabeledByList()` (common in web content).
- **Improved support for expandable elements** — `AccessibilityNodeInfo.setExpandedState(int)` conveys expanded/collapsed state; dispatch `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` with `CONTENT_CHANGE_TYPE_EXPANDED`. Applies to menus, expandable lists, and similar elements so screen readers announce state changes.
- **Indeterminate ProgressBars** — `AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INDETERMINATE` extends `RangeInfo` to cover indeterminate progress, giving TalkBack consistent feedback for all progress indicators.
- **Tri-state CheckBox** — `getChecked()` / `setChecked(int)` support checked, unchecked, and partially-checked states; the boolean `isChecked()` and `setChecked(boolean)` are deprecated.
- **Supplemental descriptions** — `View.setSupplementalDescription(CharSequence)` labels a `ViewGroup` (e.g., "Font Family") without overriding child descriptions (e.g., "Roboto").
- **Required form fields** — `AccessibilityNodeInfo.setFieldRequired(boolean)` informs accessibility services that input is required, so users can reliably identify and navigate required fields.

#### Phone as microphone input for voice calls with LEA hearing aids

LE Audio hearing-aid users can switch the microphone source during voice calls between the hearing-aid mics and the phone mic, improving performance in environments such as noisy settings.

#### Ambient volume controls for LEA hearing aids

LE Audio hearing-aid users can adjust the ambient sound volume picked up by the hearing-aid microphones, for better handling of background noise that is too loud or too quiet.

### Camera

#### Hybrid auto-exposure

Allows manual control of specific exposure aspects while the auto-exposure (AE) algorithm handles the rest, via:

- `CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY` — control ISO with AE.
- `CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY` — control exposure time with AE.

Supporting keys: `CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES`, `CaptureRequest.CONTROL_AE_MODE`, `CaptureRequest.CONTROL_AE_PRIORITY_MODE`, and `CaptureRequest.SENSOR_SENSITIVITY`.

```kotlin
fun setISOPriority() {
    val availablePriorityModes = mStaticInfo.characteristics.get(
        CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES
    )

    reqBuilder.set(
        CaptureRequest.CONTROL_AE_MODE,
        CameraMetadata.CONTROL_AE_MODE_ON
    )
    reqBuilder.set(
        CaptureRequest.CONTROL_AE_PRIORITY_MODE,
        CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY
    )
    reqBuilder.set(
        CaptureRequest.SENSOR_SENSITIVITY,
        TEST_SENSITIVITY_VALUE
    )
    val request: CaptureRequest = reqBuilder.build()
}
```

#### Precise color temperature and tint adjustments

Enables fine color-temperature and tint control for professional video, replacing the previous `CONTROL_AWB_MODE` preset-only options (Incandescent, Cloudy, Twilight). Key APIs:

- `CameraMetadata.COLOR_CORRECTION_MODE_CCT` — enable color-temperature control.
- `CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` — query the supported temperature range.
- `CaptureRequest.CONTROL_AWB_MODE` — set to OFF for manual mode.
- `CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE` — set a specific temperature.
- `CaptureRequest.COLOR_CORRECTION_COLOR_TINT` — apply a tint adjustment.

```kotlin
fun setCCT() {
    val colorTemperatureRange: Range<Int> =
        mStaticInfo.characteristics[CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE]

    reqBuilder[CaptureRequest.CONTROL_AWB_MODE] = CameraMetadata.CONTROL_AWB_MODE_OFF
    reqBuilder[CaptureRequest.COLOR_CORRECTION_MODE] = CameraMetadata.COLOR_CORRECTION_MODE_CCT
    reqBuilder[CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE] = 5000
    reqBuilder[CaptureRequest.COLOR_CORRECTION_COLOR_TINT] = 30

    val request: CaptureRequest = reqBuilder.build()
}
```

#### Camera night mode scene detection

`CaptureResult.EXTENSION_NIGHT_MODE_INDICATOR` (in Camera2's `CaptureResult`, when supported) helps apps detect when to switch between normal and night-mode camera sessions.

#### Motion photo capture intent actions

Standard Intent actions to request motion-photo capture:

- `MediaStore.ACTION_MOTION_PHOTO_CAPTURE`
- `MediaStore.ACTION_MOTION_PHOTO_CAPTURE_SECURE`

Provide the output Uri via `MediaStore.EXTRA_OUTPUT` or via `Intent.setClipData(ClipData)`. If ClipData is not set, it is populated when calling `Context.startActivity(Intent)`.

#### UltraHDR image enhancements

Extends UltraHDR with new formats and ISO standard parameters:

- `ImageFormat.HEIC_ULTRAHDR` — UltraHDR in HEIC format with an embedded gainmap (similar to the existing UltraHDR JPEG). AVIF support is in development.
- New **ISO 21496-1** parameters — colorspace specification for gainmap math, and support for HDR-encoded base images with SDR gainmaps.

### Graphics

#### Custom graphical effects with AGSL

Authoring of complex graphical effects with AGSL (Android Graphics Shading Language) for color filtering and blending:

- `RuntimeColorFilter` — AGSL-powered color filter extending `ColorFilter`.
- `RuntimeXfermode` — AGSL-based compositing/blending extending `Xfermode`.

These join the Android 13+ `RuntimeShader` (an AGSL-based `Shader`). Example effects include threshold, sepia, and hue/saturation.

```kotlin
private val thresholdEffectString = """
    uniform half threshold;

    half4 main(half4 c) {
        half luminosity = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
        half bw = step(threshold, luminosity);
        return bw.xxx1 * c.a;
    }"""

fun setCustomColorFilter(paint: Paint) {
   val filter = RuntimeColorFilter(thresholdEffectString)
   filter.setFloatUniform(0.5);
   paint.colorFilter = filter
}
```

### Connectivity

#### Ranging with enhanced security

Adds robust security for Wi-Fi location using Wi-Fi 6's 802.11az standard via `SecureRangingConfig`. Security features include AES-256-based encryption, protection against man-in-the-middle (MITM) attacks, higher accuracy and scalability than previous standards, and dynamic scheduling. Use cases include proximity-based unlocking of laptops and vehicle doors.

#### Generic ranging APIs

`RangingManager` provides unified APIs to determine distance and angle between local and remote devices across multiple technologies: BLE channel sounding, BLE RSSI-based ranging, Ultra Wideband (UWB), and Wi-Fi Round Trip Time (RTT). Apps can query the ranging supported by device hardware.

#### Companion device manager device presence

Automatic service binding based on a companion device's BLE/Bluetooth connectivity state:

- `CompanionDeviceManager.startObservingDevicePresence(ObservingDevicePresenceRequest)` — start presence observation.
- `CompanionDeviceService.onDevicePresenceEvent(DevicePresenceEvent)` — callback for presence events.
- `DevicePresenceEvent` — event data with presence states.

The service is bound when BLE is in range and Bluetooth is connected, and unbound when BLE is out of range or Bluetooth is disconnected.

### Media

#### Photo picker improvements

Enhancements to the built-in photo picker (supported since Android 4.4 / API 19 via Modular System Components and Google Play Services) for safer media selection:

- **Embedded photo picker** — new APIs in the `android.widget.photopicker` package let apps embed the picker in their own view hierarchy, giving an integrated feel while maintaining process isolation (no broad permission grants). A Jetpack library for cross-version compatibility is forthcoming.
- **Cloud search in photo picker** — new APIs enable searching cloud media providers, with full search functionality coming soon.

#### Advanced Professional Video (APV) codec support

Support for the APV codec, designed for professional-grade recording and post-production. Characteristics include perceptually lossless quality, low-complexity high-throughput intra-frame-only coding (no pixel-domain prediction), high bitrate up to several Gbps for 2K/4K/8K, frame tiling for immersive content and parallel processing, multiple chroma sampling formats and bit-depths, multiple encode/decode cycles without severe quality loss, multi-view and auxiliary video (depth, alpha, preview), and HDR10/10+ plus custom metadata.

Android 16 implementation:

- `MediaFormat.MIMETYPE_VIDEO_APV` — the APV MIME type.
- Profile: **APV 422-10** (YUV 422 color sampling, 10-bit encoding).
- Target bitrates: up to 2 Gbps.

### Privacy

#### Health Connect updates

- New `ACTIVITY_INTENSITY` data type — tracks moderate/vigorous activity per WHO guidelines, requiring start time, end time, and intensity level (moderate or vigorous).
- Medical records support — updated APIs to read/write medical records in FHIR (Fast Healthcare Interoperability Resources) format, requiring explicit user consent.

#### Privacy Sandbox on Android

Continued development of privacy-protective technologies, incorporating the newest Privacy Sandbox on Android protocols. The SDK Runtime lets SDKs run in a dedicated runtime environment separate from the host app, providing stronger safeguards around user-data collection and sharing. A developer beta program is available.

### Security

#### Key Sharing API

Apps can share access to Android Keystore keys with other apps:

- `KeyStoreManager.grantKeyAccess(String uid, int keyId)` — grant key access to another app.
- `KeyStoreManager.revokeKeyAccess(String uid, int keyId)` — revoke key access.
- Additional methods to access shared keys.

Access is specified by app UID (e.g., via `Process.myUid()`).

### Device form factors

#### Standardized picture and audio quality framework for TVs

The new `android.media.quality` package exposes standardized APIs for TV audio/picture profiles and hardware settings, enabling dynamic profile switching. Profile types include color-accuracy preference (wide-dynamic-range movies), brightness preference (narrow-dynamic-range live sports), and game mode (minimal latency, higher frame rates). Apps can query available hardware profiles and apply them dynamically; users can tune TV profiles for optimal viewing. Use cases include streaming, live-sports, and gaming apps.

### Internationalization

#### Vertical text

Low-level rendering and measurement support for vertical text (notably for languages such as Japanese):

- `Paint.VERTICAL_TEXT_FLAG` — new flag for vertical text rendering.
- `Paint.setFlags(int)` — apply the vertical flag.
- `Canvas` — renders text vertically when the flag is set; Paint text-measurement APIs then report vertical advances instead of horizontal.

High-level text APIs (Jetpack Compose `Text`, `TextView`, `Layout` classes) do **not** currently support vertical writing systems or `VERTICAL_TEXT_FLAG`.

```kotlin
val text = "「春は、曙。」"
Box(
    Modifier.padding(innerPadding).background(Color.White).fillMaxSize().drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { textSize = 64.sp.toPx() }
            // Draw text vertically
            paint.flags = paint.flags or VERTICAL_TEXT_FLAG
            val height = paint.measureText(text)
            canvas.nativeCanvas.drawText(
                text,
                0,
                text.length,
                size.width / 2,
                (size.height - height) / 2,
                paint
            )
        }
    }
) {}
```

#### Measurement system customization

Users can customize measurement systems (metric, imperial, etc.) under **Settings > System > Languages & region**. The preference is included in the locale code. Apps register a `BroadcastReceiver` on `Intent.ACTION_LOCALE_CHANGED` to handle changes and use `Formatter` to match the local experience. Example: "0.5 in" for English (US) versus "12,7 mm" for English (Denmark) or US with a metric preference.

### Additional references

The page links to supplementary resources:

- Full API diff — Beta 2 → API 36.
- Full API diff — API 35 → API 36.
- Behavior changes — all apps.
- Behavior changes — apps targeting Android 16.
- Non-SDK interface restrictions.

## See also
- Evergreen rules: [ANDROID_STANDARDS.md](../ANDROID_STANDARDS.md)
- Upgrade plan: [007 IMPLEMENTATION.md](../completed/007-target-sdk-upgrade/IMPLEMENTATION.md)
