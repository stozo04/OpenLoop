# Lesson 014 — The UI-state router `when` must be exhaustive with NO `else` (extract a testable NavHost)

> Origin: PR #19 (slice 01 review) — finding WARNING-1 in `MainActivity`.

## What went wrong

The state router in `MainActivity.setContent` ended with a catch-all:

```kotlin
when (uiState) {
    is OpenRangUiState.ReadyToCapture,
    is OpenRangUiState.Recording -> CameraScreenHost(uiState) { CameraScreen(...) }
    // … other states …
    else -> {                      // ← the problem
        CameraScreen(viewModel = viewModel, cameraManager = cameraManager)
    }
}
```

Two compounding harms:

1. **It defeated the compile-time guard.** `OpenRangUiState` is a **sealed interface** chosen
   specifically (PRD Decision Log #1) so the compiler forces every state to be handled. An `else`
   silently absorbs any state you forgot to route — exactly what sealed types exist to prevent.

2. **It re-opened the Lesson 012 bug.** The unhandled `Processing` state fell through `else` into a
   **bare `CameraScreen(...)`** — a *second* camera call site with no `CameraScreenHost`. That is
   the precise seam the `ERROR_SOURCE_INACTIVE` fix closed. `grep "CameraScreen(" MainActivity.kt`
   returned **2**, breaking Lesson 012's own detection check.

## Pattern

- **No `else` in the state router.** List every state. Adding a new `OpenRangUiState` must then
  *fail to compile* until it is routed — that failure is the feature, not a nuisance.
- **Extract the router into a stateless `@Composable` so it's unit-testable** (mirrors the
  project's `OnboardingNavigation` extract-for-testability pattern). Pass Activity-bound effects
  (permission launch, open-settings) in as lambdas so the composable holds no `ComponentActivity`:
  ```kotlin
  @Composable
  fun OpenRangNavHost(
      uiState: OpenRangUiState,
      viewModel: OpenRangViewModel,
      cameraManager: CameraManager,
      onCheckPermissions: () -> Unit,
      onRationaleAcknowledged: () -> Unit,
      onOpenAppSettings: () -> Unit,
  ) {
      when (uiState) {
          is OpenRangUiState.Initializing      -> InfinityLoadingScreen()
          // … one branch per state, capture states share the single CameraScreenHost branch …
          is OpenRangUiState.Processing        -> InfinityLoadingScreen() // TODO(slice-02): real surface
          // NO else.
      }
  }
  ```
- **A not-yet-built state still needs a real branch.** Route it to a safe placeholder (here the
  neutral loader) with a `// TODO(slice-NN)` — never let it ride an `else`.

> **Slice 02 note:** routing now lives in `OpenRangNavHost`, not inline in `setContent`.
> `Processing` already exists with the loader placeholder — *replace* it with the real
> ProcessingScreen and *add* a `Trim` branch **in `OpenRangNavHost`**, keeping the `when`
> exhaustive and the `ReadyToCapture, Recording` pair on their single `CameraScreenHost` branch.

## Detection checklist

- Grep the router for a catch-all: `grep -n "else ->" MainActivity.kt` (and any `*NavHost*`) → expect **none**.
- Lesson 012 check still holds: `grep -n "CameraScreen(" MainActivity.kt` → exactly **1**.
- After adding a state to `OpenRangUiState`, a clean build must error with a non-exhaustive-`when`
  message until you route it. (Kotlin 2.x enforces exhaustiveness for `when` over a sealed type
  even as a statement.) Sanity-check by commenting out one branch locally — the build should fail;
  restore it (don't commit the broken state).
- Covering test: mount the NavHost with the new/placeholder state and assert it renders that
  screen and **not** a camera-bound one (`OpenRangNavHostTest`).

## Reference

- [Kotlin sealed classes & exhaustive `when`](https://kotlinlang.org/docs/sealed-classes.html).
- PRD Decision Log #1 (sealed interface for UI state → exhaustive matching at compile time).
- [[012-camera-bound-screen-single-call-site]] — the bug an `else` re-opens.
