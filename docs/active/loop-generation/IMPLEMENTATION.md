# OpenRang Detailed Implementation Plan

This document serves as a step-by-step technical blueprint for building the core **"Loop"** feature of **OpenRang**—our open-source, device-agnostic, speed-controlled video-looping app. It is structured to allow progressive implementation and testing.

---

## 1. Architectural Structure

To keep the application modular and easily testable, we will organize the codebase into three main layers:

```
com.openrang.app/
│
├── camera/
│   └── CameraManager.kt       <-- Handles CameraX setup, viewfinder, and recording lifecycle
│
├── media/
│   └── VideoProcessor.kt      <-- Handles Media3 video reversing and concatenation
│
├── ui/
│   ├── OpenRangUiState.kt     <-- Defines camera, recording, processing, and preview states
│   ├── OpenRangViewModel.kt   <-- Connects CameraManager, VideoProcessor, and UI state
│   ├── CameraScreen.kt        <-- The active camera viewfinder & shutter Compose UI
│   └── PreviewScreen.kt       <-- ExoPlayer infinite loop & speed control Compose UI
│
└── MainActivity.kt            <-- Sets up permissions check and routes between screens
```

---

## 2. Step-by-Step Implementation Roadmap

### Phase 1: Camera Viewfinder & Permissions Check
* **Objective:** Implement a robust camera preview screen that works universally across standard aspect ratios and safely handles permission prompts.
* **Tasks:**
  1. Write runtime permission request logic in `MainActivity.kt` for `Manifest.permission.CAMERA` and `Manifest.permission.RECORD_AUDIO`.
  2. Implement `CameraManager.kt` to bind the CameraX `Preview` use case to the activity's lifecycle.
  3. Create `CameraScreen.kt` using Jetpack Compose's `AndroidView` to host CameraX's `PreviewView` so the user sees a live camera feed.

### Phase 2: Video Burst Capture (1.5 seconds)
* **Objective:** Record a brief, high-quality video clip and output a temporary MP4 file.
* **Tasks:**
  1. Add the CameraX `VideoCapture` use case to `CameraManager.kt` using `Recorder.Builder`.
  2. Implement a record trigger in `OpenRangViewModel.kt` that initiates recording into a temporary cache file (`raw_capture.mp4`).
  3. Program a self-stopping timer that automatically stops the recording after exactly 1.5 seconds (or lets the user manually release it).

### Phase 3: The "Loop" Generation (Media3 Transformer)
* **Objective:** Create the seamless forward-backward loop locally in under a second.
* **Tasks:**
  1. Create `VideoProcessor.kt` to load `raw_capture.mp4`.
  2. **Reversal Task:** Use Jetpack Media3's `Transformer` API to reverse the frame timestamps of `raw_capture.mp4`, generating a second temporary file (`reversed_capture.mp4`).
  3. **Concatenation Task:** Use Media3 `Composition` or `EditedMediaItem` utilities to join `raw_capture.mp4` and `reversed_capture.mp4` back-to-back.
  4. **Output:** Export the merged file as `openrang_output.mp4`.

### Phase 4: Dynamic Speed Player & Preview UI
* **Objective:** Display the looped video instantly and allow the user to modify speed dynamically using a UI slider.
* **Tasks:**
  1. Create `PreviewScreen.kt` to load `openrang_output.mp4` into a Jetpack Media3 `ExoPlayer` instance.
  2. Configure `ExoPlayer` to loop infinitely:
     ```kotlin
     player.repeatMode = Player.REPEAT_MODE_ALL
     ```
  3. Add a Compose `Slider` (0.5x to 3.0x, defaulting to 1.5x) to the UI.
  4. Connect the slider change dynamically to the player:
     ```kotlin
     player.setPlaybackSpeed(speedValue)
     ```

### Phase 5: Exporting & Saving with Speed Burn-In
* **Objective:** Persist the custom speed to the final video file so it can be shared with other apps.
* **Tasks:**
  1. When the user taps the **Save** button, read the current slider speed.
  2. Run the `openrang_output.mp4` through Media3 `Transformer` using a `SpeedChangingVideoEffect` and `SpeedChangingAudioProcessor` configured to the target speed.
  3. Save the resulting file into the public device gallery (`Environment.DIRECTORY_DCIM` or `MediaStore.Video.Media`) as `OpenRang_Capture_[timestamp].mp4`.
  4. Show a completion toast and return to the main viewfinder.

---

## 3. UI State Model (`OpenRangUiState`)

To keep state-handling simple, predictable, and fully reactive, we will manage the UI flow using a sealed interface in `OpenRangUiState.kt`:

```kotlin
sealed interface OpenRangUiState {
    object CheckingPermissions : OpenRangUiState
    object PermissionDenied : OpenRangUiState
    object ReadyToCapture : OpenRangUiState
    object Recording : OpenRangUiState
    object Processing : OpenRangUiState
    data class LoopingPreview(val videoPath: String, val playbackSpeed: Float) : OpenRangUiState
}
```

---

## 4. Verification & Testing Plan

### Automated Verification
* **Unit Tests:** Verify that state mutations in `OpenRangViewModel` occur in the correct logical sequence: `ReadyToCapture` -> `Recording` -> `Processing` -> `LoopingPreview`.
* **Media Processing Profile:** Set up local instrumentation tests to run the reversal pipeline and assert that `openrang_output.mp4` exists and has exactly double the duration of the original raw capture.

### Manual Verification Checklist
1. **Permission Test:** Revoke camera permission in device settings, open the app, and verify that the app gracefully prompts for permissions and displays a helpful screen if denied.
2. **Timing Test:** Tap the shutter button and ensure the capture stops automatically at the 1.5-second limit.
3. **The Loop Test:** Verify that the loop plays seamlessly in the player without stutter or black flash at the inflection points (transition from forward to backward, and transition from backward back to forward).
4. **Speed Shift Test:** Verify that adjusting the speed slider instantly speeds up or slows down the playback preview with zero lag.
5. **Share Test:** Save an export at `2.0x` speed, open it in the default system gallery app, and verify that it plays at double-speed natively.
