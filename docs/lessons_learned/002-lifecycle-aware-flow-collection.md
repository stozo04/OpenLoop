# Lesson 002 — Use `collectAsStateWithLifecycle()` for Flow collection in Compose

## What went wrong

PR #5 used `viewModel.uiState.collectAsState()` in `MainActivity.kt`. This continues collecting the Flow even when the app is in the background (stopped/paused), wasting resources and battery. Google explicitly recommends `collectAsStateWithLifecycle()` as the standard way to collect Flows in Compose on Android — it pauses collection when the lifecycle drops below `STARTED`.

The bug is invisible for a `StateFlow<UiState>` (no heavy work happens on emission), but the moment a real-time flow gets added — a speed slider, a video processing progress flow, a sensor stream — collecting in the background causes real performance and battery problems.

## Pattern

For every `StateFlow` / `Flow` collected inside an `@Composable`, import
`androidx.lifecycle.compose.collectAsStateWithLifecycle` and collect with it:

```kotlin
val state by viewModel.someFlow.collectAsStateWithLifecycle()
```

Required dependency in `gradle/libs.versions.toml`:

```toml
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleKtx" }
```

And in `app/build.gradle.kts`:

```kotlin
implementation(libs.androidx.lifecycle.runtime.compose)
```

Override the default `STARTED` threshold only with a clear reason:

```kotlin
val state by flow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
```

## Detection checklist

- `collectAsState(` (with opening paren) should not appear anywhere — every match is a bug.
  ```
  grep -rn "collectAsState(" app/src --include="*.kt"
  ```
- `import androidx.compose.runtime.collectAsState` should not appear at all.
- Every new Flow exposed by a ViewModel must be collected via `collectAsStateWithLifecycle()` at the UI layer.

## Reference

- [Integrate Lifecycle with Compose](https://developer.android.com/topic/libraries/architecture/compose)
- PR #5 FAIL #2
