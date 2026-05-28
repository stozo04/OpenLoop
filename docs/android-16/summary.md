# Android 16 — Release Summary

> **Source:** https://developer.android.com/about/versions/16/summary · fetched 2026-05-28
> **Part of:** [docs/android-16/ hub](./README.md) — Android 16 (API 36) upgrade knowledge for OpenRang (Issue #7)

## Impact on OpenRang

**Verdict:** Partially applies — only a handful of the targetSdk-36 behavior changes touch OpenRang's actual surface (edge-to-edge, predictive back, adaptive layouts, photo picker), and a few "all apps" changes are worth knowing; the large bulk of Android 16 (health, Bluetooth, notifications, TV, ranging, haptics, etc.) is N/A.

- **Edge-to-edge opt-out is removed for targetSdk 36** (`R.attr#windowOptOutEdgeToEdgeEnforcement` gone). OpenRang already calls `enableEdgeToEdge()` in `MainActivity`, so the job is to *verify* every screen (CameraScreen, OnboardingScreen, PreviewScreen, GalleryScreen) consumes window insets correctly under enforcement — nothing left behind the system bars or display cutout.
- **Predictive back becomes default-on for targetSdk 36** (`android:enableOnBackInvokedCallback` defaults to `true`; legacy `onBackPressed` / `KEYCODE_BACK` paths get ignored). OpenRang's back navigation is a sealed-interface state machine routed in `MainActivity` — confirm back gestures between states (Gallery to ReadyToCapture, LoopingPreview to ReadyToCapture) flow through the predictive-back/`OnBackInvokedCallback` path, not a swallowed key event.
- **Adaptive layouts: orientation/aspect-ratio/resizability manifest restrictions are ignored for targetSdk 36.** If OpenRang locks the camera screen to portrait via manifest/`setRequestedOrientation`, that lock stops being honored on large screens — must verify the viewfinder and gallery behave under forced resizability rather than relying on a portrait lock.
- **App-owned photos in the photo picker (targetSdk 36):** N/A today — OpenRang writes loops to storage but does not currently launch the system photo picker. Flag for the future if a "import a clip" flow is ever added.
- **Local Network Permission (targetSdk 36):** N/A — OpenRang makes zero network calls and has no LAN features, so the new declaration requirement does not apply.
- **N/A categories:** Health and fitness (`android.permissions.health`, Health Connect/FHIR), Bluetooth bond-loss intents, progress-centric and all notifications, ranging/Wi-Fi 802.11az, TV `MediaQuality`, richer haptics, live wallpapers, key sharing, Privacy Sandbox — none map to OpenRang's on-device, no-network, no-notification, single-Activity camera surface. Camera2/Media items below are *opt-in* APIs, not forced changes.

## Summary

The Android 16 summary page (API level **36**, last updated 2026-03-03 UTC) catalogs every feature and behavior change in a single filterable table. Each row carries a **Category**, a **Type**, and a **Name**. The three Types are the load-bearing distinction:

- **Change (all apps)** — applies on Android 16 devices regardless of your `targetSdkVersion`.
- **Change (apps targeting Android 16 or higher)** — gated behind `targetSdk` 36; only triggers once you raise the target.
- **New features and APIs** — opt-in additions available to use; they do not force any behavior on you.

The page also notes that 2025 has **two** Android API releases: a **Q2 2025 major release** (the only 2025 release carrying planned behavior changes tied to `targetSdkVersion`) and a **Q4 2025 release** that adds new developer APIs without those behavior changes.

The categories on the page are: Accessibility, Camera, Connectivity, Core functionality, Graphics, Health and fitness, Internationalization, Device form factors, Media, Performance and battery, Privacy, Security, and User experience and system UI.

### Core functionality

**Change (all apps)**

- **ART internal changes.** Android Runtime updates improve performance and add Java feature support, delivered to Android 12 (API 31+) devices via Google Play system updates. Apps or libraries that rely on ART internal structures may break on Android 16 (or earlier devices that receive the updated ART module).
- **JobScheduler quota optimizations.** `JobScheduler` adjusts regular and expedited job runtime quota based on the app's standby bucket, whether the job starts while the app is in the top state, and whether it runs while a foreground service is active.
- **Abandoned empty jobs stop reason.** The system adds a new stop reason, `STOP_REASON_TIMEOUT_ABANDONED`, to detect and reduce abandoned jobs (it is assigned in place of `STOP_REASON_TIMEOUT` in those cases).
- **Ordered broadcast priority scope is no longer global.** Broadcast delivery order set via `android:priority` on an `<intent-filter>` (or `IntentFilter#setPriority(int)`) is now only respected for ordered broadcasts within the same application process, not across processes.
- **16 KB page size compatibility mode.** Android 15 introduced 16 KB memory pages; Android 16 adds a compatibility mode so apps built for 4 KB pages can still run on 16 KB-page devices.

**Change (apps targeting Android 16 or higher)**

- **Fixed-rate work scheduling optimization.** For `ScheduledExecutorService#scheduleAtFixedRate(...)`, at most one missed execution is run immediately when the app returns to a valid lifecycle state (rather than firing all the backlog).

**New features and APIs**

- **Two Android API releases in 2025**, as described above — Q2 (behavior changes) and Q4 (new APIs).

### User experience and system UI

**Change (all apps)**

- **Deprecating disruptive accessibility announcements.** `View#announceForAccessibility(CharSequence)` and the `AccessibilityEvent#TYPE_ANNOUNCEMENT` event are deprecated.
- **Support for 3-button navigation.** Predictive back support is extended to 3-button navigation for apps that have properly migrated to predictive back.
- **Automatic themed app icons.** Android 16 can automatically theme app icons for a cohesive home-screen appearance.

**Change (apps targeting Android 16 or higher)**

- **Elegant font APIs deprecated and disabled.** The `elegantTextHeight` attribute is deprecated and ignored for apps targeting Android 16+.
- **Edge-to-edge opt-out going away.** The `R.attr#windowOptOutEdgeToEdgeEnforcement` attribute is removed; apps targeting Android 16+ must handle window insets themselves rather than opting out of edge-to-edge.
- **Migration or opt-out required for predictive back.** System back animations (back-to-home, cross-task, cross-activity) appear by default because `android:enableOnBackInvokedCallback` now defaults to `true`. As a result, `onBackPressed` and `KeyEvent.KEYCODE_BACK` handling is ignored — apps must migrate to predictive back or explicitly opt out.

**New features and APIs**

- **Predictive back updates.** New APIs to drive predictive back system animations in gesture navigation, including `SystemOnBackInvokedCallbacks#finishAndRemoveTaskCallback(Activity)` and `SystemOnBackInvokedCallbacks#moveTaskToBackCallback(Activity)`.
- **Richer haptics.** New haptic APIs (in `android.os.vibrator.*`) let apps define amplitude and frequency curves while abstracting away device capability differences.
- **Progress-centric notifications.** A new notification type for tracking user-initiated, start-to-end journeys, with upgraded visibility on system surfaces and top ranking in the notification drawer. (See the hub's dedicated page on this.)
- **Content handling for live wallpapers.** A new content API for the live wallpaper framework, addressing the challenges of dynamic, user-driven wallpapers.

### Security

**Change (all apps)**

- **Improved security against Intent redirection attacks.** By-default hardening against Intent redirection exploits.
- **Companion apps no longer notified of discovery timeouts.** The Companion Device Manager (CDM) no longer notifies the app when a device is not found.

**Change (apps targeting Android 16 or higher)**

- **MediaStore version lockdown.** `MediaStore#getVersion()` now returns a value unique to each app.
- **Safer Intents.** Security improvements to Android's Intent resolution mechanism.
- **GPU syscall filtering.** A high-level SEPolicy provides fine-grained IOCTL control for GPU access.

**New features and APIs**

- **Key sharing API.** APIs to share access to Android Keystore keys with other apps.

### Device form factors

**Change (all apps)**

- **Virtual device owner overrides.** Virtual device owners (limited to select trusted/privileged apps) can override app settings on managed devices.

**Change (apps targeting Android 16 or higher)**

- **Adaptive layouts.** The platform ignores manifest attributes and runtime APIs that restrict screen orientation, aspect ratio, and resizability — apps targeting Android 16+ are treated as resizable/adaptive across form factors.

**New features and APIs**

- **Standardized picture and audio quality framework for TVs.** A new `android.media.quality.*` (`MediaQuality`) package with standardized APIs for accessing audio/picture profiles and hardware settings, so streaming apps can query profiles and apply them dynamically.

### Connectivity

**Change (all apps)**

- **Improved bond loss handling.** Better handling of Bluetooth bond-loss events.

**Change (apps targeting Android 16 or higher)**

- **New intents to handle bond loss and encryption changes.** Two new intents for bond-loss events and for encryption changes.
- **New way to remove a Bluetooth bond.** A new `removeBond` API for removing Bluetooth bonds.

**New features and APIs**

- **Ranging with enhanced security.** Support for robust security in Wi-Fi location (Wi-Fi 6 802.11az on supported devices): higher accuracy, greater scalability, dynamic scheduling, AES-256-based encryption, and protection against man-in-the-middle attacks — via `SecureRangingConfig`.
- **Companion device manager device presence.** New APIs to bind a companion app service; the service is bound when BLE is in range and Bluetooth is connected, and unbound when BLE goes out of range or Bluetooth disconnects.
- **Generic ranging APIs.** A new `RangingManager` for determining distance and angle between local and remote devices.

### Health and fitness

**Change (apps targeting Android 16 or higher)**

- **Health and fitness permissions.** A transition to a more granular permission set under the `android.permissions.health` namespace (used by Health Connect) for apps targeting Android 16+ that use health/fitness data.

**New features and APIs**

- **Health Connect updates.** A new `ACTIVITY_INTENSITY` data type aligned with WHO guidelines for moderate/vigorous activity, plus new APIs for reading/writing health records in HL7 FHIR format with explicit user consent (early access program available via sign-up).

### Privacy

**Change (apps targeting Android 16 or higher)**

- **Local Network Permission.** The platform requires apps targeting Android 16+ to declare a permission to access the local network.
- **App-owned photos.** The photo picker pre-selects an app's own photos/videos; users can deselect to revoke the app's future access to them.

**New features and APIs**

- **Health Connect updates.** APIs for reading and writing medical records in FHIR format with explicit user consent (early access program).
- **Privacy Sandbox on Android.** The latest version of Privacy Sandbox is incorporated, advancing technologies that protect user privacy.

### Performance and battery

**New features and APIs**

- **Start component in ApplicationStartInfo.** `ApplicationStartInfo#getStartComponent()` distinguishes which component type triggered the app start, helping optimize startup flow.
- **Adaptive refresh rate.** New APIs for Adaptive Refresh Rate (ARR): `Display#hasArrSupport()`, `Display#getSuggestedFrameRate(int)`, and the restored `Display#getSupportedRefreshRates()`.
- **Better job introspection.** `JobScheduler#getPendingJobReasons()` returns multiple reasons a job is pending (both explicit developer constraints and implicit system constraints), and `JobScheduler#getPendingJobReasonsHistory()` returns recent changes to those pending reasons.
- **System-triggered profiling.** `ProfilingManager` can be triggered by the system on events such as cold start (`Activity#reportFullyDrawn()`) and ANRs; the system starts/stops the trace on the app's behalf and delivers results to the app's data directory.
- **Headroom APIs in ADPF.** `SystemHealthManager#getCpuHeadroom(CpuHeadroomParams)` and `SystemHealthManager#getGpuHeadroom(GpuHeadroomParams)` estimate available CPU/GPU resources for games and resource-intensive apps.

### Media

**New features and APIs**

- **Photo picker improvements.** New APIs to embed the photo picker directly into the view hierarchy, plus APIs for cloud media provider searching.
- **Advanced Professional Video (APV) codec support.** A new codec for professional-grade, high-quality video recording and post-production.

### Camera

**New features and APIs**

- **Precise color temperature and tint adjustments.** Fine color temperature and tint control for professional video recording (Camera2).
- **Hybrid auto-exposure.** New hybrid AE modes let an app manually control specific exposure aspects while the AE algorithm handles the rest (Camera2).
- **Motion photo capture intent actions.** Standard Intent actions `MediaStore#ACTION_MOTION_PHOTO_CAPTURE` and `MediaStore#ACTION_MOTION_PHOTO_CAPTURE_SECURE` — a camera app captures a motion photo and returns it.
- **Camera night mode scene detection.** A new `CaptureResult#EXTENSION_NIGHT_MODE_INDICATOR` constant helps an app decide when to switch to or from a night mode camera session (Camera2).
- **UltraHDR image enhancements.** Support for UltraHDR images in the HEIC file format.

### Internationalization

**New features and APIs**

- **Vertical text.** Low-level support for rendering and measuring text vertically — foundational support for libraries handling vertical writing systems.
- **Measurement system customization.** Users can customize the measurement system under regional preferences in Settings.

### Accessibility

**New features and APIs**

- **Improved accessibility APIs.** Additional APIs to enhance UI semantics for consistency across accessibility services such as TalkBack.
- **Phone as microphone input for voice calls with LEA hearing aids.** Users of LE Audio hearing aids can switch between the hearing aids' built-in microphones and the phone microphone for voice calls.
- **Ambient volume controls for LEA hearing aids.** Users of LE Audio hearing aids can adjust the volume of ambient sound picked up by the hearing aid microphones.

### Graphics

**New features and APIs**

- **Custom graphical effects with AGSL.** New classes `RuntimeColorFilter` and `RuntimeXfermode` let apps author complex graphical effects (for example Threshold, Sepia, Hue/Saturation) and apply them to draw calls.

### Additional resources

The page also links to API diffs (Beta 2 to 36 incremental, and API 35 to API 36), compatibility framework change documentation, the app compatibility guides, the restrictions on non-SDK interfaces, the platform dashboards, QPR (Quarterly Platform Release) information, migration guides, SDK setup, factory/OTA images, and Generic System Images (GSI).

## See also
- Evergreen rules: [ANDROID_STANDARDS.md](../ANDROID_STANDARDS.md)
- Upgrade plan: [007 IMPLEMENTATION.md](../active/007-target-sdk-upgrade/IMPLEMENTATION.md)
